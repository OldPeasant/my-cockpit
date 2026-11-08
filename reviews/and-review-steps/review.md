# Code Review

Date: 2026-11-08

Task: and-review-steps - Android weekly review: waiting, projects, next, someday steps

Scope: `git diff 7d2195e9e305b8c3fc4b3c8ee2f30e89a43c2407..125c22729 -- android` (final code, production and tests, at revision 125c227295ba07e573b0a8af91b2883169914871). This re-review follows the first review at 6e4b34948/ecc89bbeb (CR-1 to CR-5), the fixer's report `reviews/and-review-steps/fixes.md` (CR-2 to CR-5, commit 4c24ba45b) and the test-writer's report `reviews/and-review-steps/tests.md` (commits 4c24ba45b and 125c22729).

Requirements:
- tasks/and-review-steps.md acceptance criteria and recorded assumptions
- SPEC.md (weekly review, §4.2 ETag/If-Match), docs/ARCHITECTURE.md, AGENTS.md "Project notes"
- Web parity reference: web/src/app/features/review/steps/{waiting,projects,next-actions,someday}-step.ts and step-list.ts

Git range:
- Branch: main
- HEAD: 125c227295ba07e573b0a8af91b2883169914871
- Base/start: 7d2195e9e305b8c3fc4b3c8ee2f30e89a43c2407
- Commits reviewed: 7 (2a4ab706c, 60e4871fe, 52934c122, ecc89bbeb, 4c24ba45b, 9b0bc97e3 merge, 125c22729)
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Status of earlier findings

- **CR-1 (P1, tests missing): resolved.** The final revision adds these tests:
  - `ReviewActionsTest`: request mapping, `If-Match` on complete, move, delete and status, the 204 delete, task vs project routing for someday activate and delete, the two calls of `addNextStep` and its `taskCreated` flag in all three outcomes, and the Offline/Failed mapping.
  - `ReviewDecisionsViewModelTest`: every decision of the four steps, the busy guard, Offline/403/null versus 404/409/412 handling, a decision or keep during a running load, the late decision after moving on, and the `addNextStep` partial failures.
  - `ReviewActionStepsTest` (Compose): the choices per row, disabled while busy, empty, failed and loading states, the status menu, the next-step field via button and IME, every decision text, the banner messages, German texts, and parity of the English and German keys.
  - `ProjectOpenRequestsTest`: shows that `ProjectsViewModel` opens the requested project's detail, whether it was created before or after the request.
  - `ReviewWizardRouteInAppTest`: the in-app navigation.
  The assertions check behavior, not only calls.
- **CR-2 (P2, a reload undid keeps): resolved.** `decided` (ReviewViewModel.kt:144) now records keeps too (line 467). A reload is filtered against this set when its result arrives (line 534). The public `loadStep` clears the set (line 333), while the `reloadStep` that `failed()` calls keeps it (line 516). This matches web `StepList`, which resets `decided` on load. Tests are at ReviewDecisionsViewModelTest.kt:172, 215, 231 and 492.
- **CR-3 (P3, late decision announced in another step): resolved.** `shownStep` gates the `decision` in `removeItem` (line 502) and in the `addNextStep` success path (line 420). `LaunchedEffect(step)` in ReviewWizard.kt:78 calls `loadStep` each time a step is shown, so `shownStep` tracks the visible step. Tests are at lines 243 and 471.
- **CR-4 (P3, partial failure said "try again"): resolved.** When the task was created but the next step was not set, `NEXT_STEP_NOT_SET` replaces the generic message (line 426). A conflict still reloads the projects step. The string exists in English and German. Tests are at lines 352 and 367, and in the banner test.
- **CR-5 (P3, accessibility of the status button and title): resolved.** The status button sets `stateDescription` to the current status (ReviewActionSteps.kt:300). The title has `Role.Button` and `heightIn(min = 48.dp)` (lines 114 to 117). The Compose test checks both (ReviewActionStepsTest.kt:230, 279).

I also checked these, with no findings:
- All four steps offer exactly the actions in the criteria, and project rows open the project detail through the `ProjectOpenRequests` singleton (`compareAndSet` lets only one collector take a request).
- Every server-side change sends the loaded version as `If-Match`, and the project returned by `setProjectNextStep` replaces the row.
- `load` moved to file level and the visibility changes (`internal`) do not alter its behavior.
- The removed `review_open_list`/`review_list_hint` strings and `OPEN_LIST` tag have no remaining references.
- No secrets, and the test data is fictitious.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-6 | A refused decision that finishes after the user moved on shows its banner in the new step**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewViewModel.kt:516`
    - Category: Correctness
    - Problem: CR-3 was fixed only for the success announcement (`decision`). `failed()` still sets `message` whatever step is shown, and `Banner` (ReviewScreen.kt:251) shows it for the whole wizard. Example: the user taps "Done" on a waiting item, then "Next". The move clears `message` (`save`, line 577). If the completion then returns 412, `failed()` reloads the waiting step in the background and sets `ITEM_CHANGED`. The Projects step then shows "It was changed on another device, so this step was loaded again." The Projects step was not loaded again. `ACTION_FAILED` and `SAVE_OFFLINE` appear the same way without saying which item failed.
    - Why it matters: The message is wrong for the visible step, and the user is not told that the item in the earlier step is still undecided. This is minor: it needs a slow request and a fast step change, and the item stays in its step to decide on later.
    - Suggested fix: In `failed()` (and in the `NEXT_STEP_NOT_SET` path, if wanted), set the step-specific message only when `step == shownStep`. Otherwise, use a message that does not refer to "this step".

## Review conclusion

No material issues found. CR-1 to CR-5 are resolved in the final code. CR-6 is a low-priority follow-up and does not block completing the task.
