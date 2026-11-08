# Code Review

Date: 2026-11-08

Task: and-review-steps - Android weekly review: waiting, projects, next, someday steps

Scope: `git diff ea6a911c4b8279c718ffd7598cab9a856d6cdf4a..6e4b34948 -- android` (production code at 6e4b34948; tests the test-writer adds in parallel are not part of this review)

Requirements:
- tasks/and-review-steps.md acceptance criteria and recorded assumptions (`python tasks.py assumptions`)
- SPEC.md (weekly review, §4.2 ETag/If-Match), docs/ARCHITECTURE.md, docs/PARITY.md, AGENTS.md "Project notes"
- Web parity reference: web/src/app/features/review/steps/{waiting,projects,next-actions,someday}-step.ts, step-list.ts

Git range:
- Branch: main
- HEAD: 6e4b34948
- Base/start: ea6a911c4b8279c718ffd7598cab9a856d6cdf4a
- Commits reviewed: 3 (b3a2ae118, 53020de54, 6e4b34948)

Summary: 0 P0, 1 P1, 1 P2, 3 P3

What was checked and found in order: all four steps offer the actions the criteria list (waiting keep/follow-up/done,
projects next step/status/keep plus the row that opens the detail, stale next actions keep/someday/delete, someday
activate/keep/delete). Every change sends `If-Match` with the loaded version. The project returned by
`setProjectNextStep` replaces the row, so a later status change uses the new version. All new strings exist in
`values/` and `values-de/`, and the removed `review_open_list`/`review_list_hint` are no longer referenced. Hilt gets
`ProjectOpenRequests` as a `@Singleton` in both view models. `compareAndSet` in `take` lets only one collector consume
a request. `decidedDuringLoad` handles a server-side decision that finishes while a reload is running, both when the
load finishes first and when the decision finishes first. `addNextStep` clears the draft once the task exists, so a
retry cannot create the task twice (same as web).

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | New decision behavior has no tests at the reviewed revision**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewViewModel.kt:381`
    - Category: Tests
    - Problem: At 6e4b34948 the only test changes in the task commits are constructor updates (an extra `mockk()` argument), removed `ListStep` tests, and one in-app test, `ReviewWizardRouteInAppTest`, which opens a project. Nothing tests `ReviewActions` (request mapping, `If-Match`, the two-call `addNextStep` and its `taskCreated` flag, someday task vs project routing), the view-model decisions (`decide`, `failed` mapping Offline/404/409/412/other, the busy guard, `decidedDuringLoad`, the `addNextStep` partial failure), or the Compose steps (choices per row, disabled while busy, empty/failed/loading states, the status menu, the next-step field). `ProjectsViewModel` taking a request is checked only indirectly, through `projectRequests.requests.value == null`. The test does not check that the detail of that project is shown.
    - Why it matters: Acceptance criterion 6 ("Unit/Compose tests cover the new behavior") is not met by the reviewed code. The concurrency paths are exactly the ones unit tests should pin down.
    - Suggested fix: The test-writer should add tests for `ReviewActions` (MockWebServer or mocked APIs), for the `ReviewViewModel` decision and race paths (including a decision finishing during a load, and an `addNextStep` where create succeeds and set-next-step fails), Compose tests for the four steps, and a `ProjectsViewModel` test showing that a pending request selects the project.

## P2 Medium

- [ ] **CR-2 | Reloading a step after a conflict undoes all earlier "keep" decisions in that step**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewViewModel.kt:482`
    - Category: Correctness
    - Problem: Keep decisions (`keepWaiting`, `keepProject`, `keepNextAction`, `keepSomeday`, and in effect `followUp`, which leaves the waiting task as it is) only remove the item locally (`decide` with `action == null`, line 444, and `removeItem`). When any other decision in the step fails with 404/409/412, `failed()` calls `loadStep(step)`. That loads the list from the server again and replaces it, so every item kept earlier in the step comes back. The same thing happens when a keep is tapped while a load of the step is running: the load result (line 513 via `loadInto`) overwrites the local removal, because only successful server decisions are recorded in `decidedDuringLoad` (line 457).
    - Why it matters: One item changed on another device makes the user decide again on every item kept so far. The web wizard does not reload automatically on a failed decision (step-list.ts keeps the list and shows the error), so this is an Android-only regression against parity, and it hits the "go through each item" flow the step exists for.
    - Suggested fix: Record client-only decisions as well, for example a per-step set of decided ids that is kept across reloads triggered by `failed()` (web's `decided` set) and filled for keep decisions too. Apply it when a load result arrives. Alternatively, on a conflict remove or refresh only the affected item instead of reloading the whole step.

## P3 Low

- [ ] **CR-3 | A decision that finishes after the user moved to another step is announced in the new step**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewViewModel.kt:478`
    - Category: Correctness
    - Problem: `removeItem` always writes `decision` into the single shared field, whatever step is shown. If the user taps "Done" on a waiting item and then "Next" before the call returns, `loadStep(PROJECTS)` clears `decision`, and the late result then sets "Completed “X”." above the Projects list. The polite live region announces it there.
    - Why it matters: The user sees, and hears through the live region, a message that does not belong to the visible step.
    - Suggested fix: Store the step with the decision (or check `state.value.current?.step == step`) and set or show it only for the current step.

- [ ] **CR-4 | `addNextStep` partial failure tells the user to "try again" although the task was already created**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewViewModel.kt:396`
    - Category: Correctness
    - Problem: When `createTask` succeeds and `setProjectNextStep` fails with a non-conflict status, the draft is cleared (correct), but the banner shows `review_action_failed` ("The server refused the change. Try again.") or `review_save_offline`. The project stays stalled with an empty field. Retyping and adding creates a second next action in the project. `ProjectService` derives `stalled` from the next-step pointer, so the first task stays orphaned as the next step.
    - Why it matters: Duplicate next actions in a project, and the message gives no hint that one was already added. Web behaves the same, so this is a resilience gap and not a parity break.
    - Suggested fix: When `outcome.taskCreated` is true and `result` failed, either retry only `setProjectNextStep` with the created task id (keep it in state), or show a specific message saying the task was added but not set as the next step.

- [ ] **CR-5 | Status button hides the current status from TalkBack; project title link has a small touch target**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewActionSteps.kt:286`
    - Category: Quality
    - Problem: `StatusChoice` sets `contentDescription = "Status of <title>"` on the button. For a merged button node TalkBack reads the content description instead of the visible text, so the current status (for example "Active") is not announced. The web `<select>` announces its label and the selected value. Separately, the clickable project title (line 108) has only 4 dp vertical padding around one `bodyLarge` line, about 32 dp, which is below the 48 dp minimum touch target. It also has no `Role`.
    - Why it matters: Accessibility. Screen-reader users cannot tell a project's status before opening the menu, and the link is hard to hit.
    - Suggested fix: Use `stateDescription = statusName(project.status)` (or include the status in the description). Give the title `Modifier.minimumInteractiveComponentSize()` or `heightIn(min = 48.dp)`, and `role = Role.Button` in `clickable`.

## Review conclusion

Material issues found; fix before completing the task.
