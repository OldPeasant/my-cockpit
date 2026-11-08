# Code Review

Date: 2026-11-09

Task: and-task-reminders - Reminders on tasks (Android)

Scope: final review of the task at revision 4be818e65. Latest delta: `git diff abb292178..4be818e65 -- android` (one test line, Android lint fix). The previous review covered abb292178. Earlier full re-review: `git diff 362ff9a53..1a1937346 -- android`, which covered the task's production commits (reminders section, ktlint format, notification opens the task detail), the test commit 2232208de (TaskRemindersTest.kt, TaskRemindersContentTest.kt, OpenItemRequestTaskTest.kt, ReminderUiTest.kt) and the fixer's Hilt guard in TaskRemindersSection.kt (1a1937346, TW-1). Then `git diff 1a1937346..abb292178 -- android`, which only added one new test in TaskRemindersContentTest.kt (the Hilt guard test). Outside `android/`, these ranges only change `reviews/` reports.

Requirements:
- tasks/and-task-reminders.md (acceptance criteria and recorded assumptions)
- SPEC.md (reminders, §4.2 API conventions)
- docs/ARCHITECTURE.md, AGENTS.md project notes
- reviews/and-task-reminders/fixes.md (fixer report for TW-1 and CR-1)

Git range:
- Branch: main
- HEAD (reviewed revision): 4be818e65e48e61f0a11a60151fa4619b7478c23
- Base/start: 362ff9a5303b6e31a9dc200c705e7e8b4b0d437c
- Commits reviewed: all task commits in the range. The delta since the previous review is one test commit (4be818e65). Before that came one test commit (abb292178) and report commits.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

This was a static review only. As instructed, this reviewer did not run Gradle. The test results come from the test-writer's report (527 tests, 0 failures).

## Delta review (abb292178..4be818e65)

- One test change in `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskRemindersContentTest.kt:202-203` fixes the Android lint error ViewModelConstructorInComposable. `TaskRemindersViewModel(source)` is now built in the test body and passed into `setContent`. Before, it was constructed inside the composable lambda.
- Behavior is unchanged. The same single view model, with the same `source`, reaches `TaskRemindersSection`, and the assertions did not change. Building it outside composition is also the more correct form, because a recomposition can no longer create a new instance.
- The delta makes no production code changes and does not touch other tests. Outside `android/`, only this report changed.

## Earlier delta review (1a1937346..abb292178)

- New test `without a view model outside a Hilt activity the section renders nothing` in `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskRemindersContentTest.kt:238`. `createComposeRule()` hosts a plain `ComponentActivity`, which is not a `GeneratedComponentManagerHolder`. So `TaskRemindersSection(taskId, anchors, true)` with the default `viewModel = null` reaches the early return in `TaskRemindersSection.kt:71`, and the test exercises the guard path it names.
- The assertions check that the "Reminders" title, `TaskReminderTags.SECTION` (the root column tag, `TaskRemindersSection.kt:109`) and `TaskReminderTags.CUSTOM` all have zero nodes. These are the observable results of the guard. Without the guard, `hiltViewModel()` would throw in a non-Hilt activity, so the test would also catch the guard being removed. The existing test that wires the section to an explicit view model gives the positive counterpart.
- All imports (`assertCountEquals`, `onAllNodesWithTag`, `onAllNodesWithText`, `UUID`) and the `anchors` fixture were already in the file. The test uses fictitious data and does not change any other test.
- That delta made no production code changes.

## Status of earlier findings

- CR-1 (P1, no unit tests for the new reminder behavior): **resolved**. Every gap CR-1 listed now has a test:
  - Preset computation and `availablePresets` (09:00 default, hour-before only with a due time, a preset in the past or exactly now is hidden, DST, a task without dates): `TaskRemindersTest.kt`.
  - `reminderAnchors` with an unreadable due time: `TaskRemindersTest.kt`.
  - `TaskReminderRepository` against MockWebServer with the real generated `RemindersApi`. The tests check the HTTP method, path, query, a UTC `remindAt` in the POST body, 400/422 mapped to INVALID, 404 on delete counted as success, 500 and an unreadable body mapped to FAILED, an unreachable server mapped to OFFLINE, an empty 201 body, and `ReminderSync.sync()` called exactly once on success and never on failure or list.
  - `TaskRemindersViewModel`: open and reopen, switching task, load error and retry, an unexpected exception, the busy guard, past and exactly-now times, sorted insert, remove, and failed add or remove keeping the list.
  - `TaskRemindersContent` (Robolectric/Compose): listing, the sent marker, empty, offline, invalid and failed states, the past-time message without retry, preset chips, the custom date then time flow, cancel, busy, read-only, and the section wired to a view model and to the task detail panel.
  - `OpenItemRequest.taskId` (a valid id, a malformed or empty id, other types, a lower-case type): `OpenItemRequestTaskTest.kt`. The shell opening the task detail on top of Next and going back to Next: `ReminderUiTest.kt`.
- TW-1 (Hilt-only section broke plain-activity screen tests): the fix is correct. `TaskRemindersSection` now takes `viewModel: TaskRemindersViewModel? = null` and renders nothing when there is no view model and `LocalActivity.current` is not a `GeneratedComponentManagerHolder`. This is the same pattern as `LinkedItemsSection` in `core/links/LinkedItemsPanel.kt`. Production (`TaskDetailRoute` in the Hilt `MainActivity`) still goes through `hiltViewModel()`. The test added in abb292178 covers the case with no Hilt and no view model.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

Notes (checked, no finding):
- `ReminderUiTest.kt` changed two existing tests: "tapped notification opens its screen" and "taken once". Both used a TASK reminder that opened Next. They now use PROJECT reminders, and a new test covers TASK opening the detail. This follows from the intended behavior change in criterion 2. It does not weaken the tests: the screen-opening and take-once checks are still asserted.
- When two task notifications are tapped one after the other, each opens Next and then the detail, because `Next` is a More-section destination and `navigate(route) { launchSingleTop = true }` only collapses when Next is on top. The back stack grows by one Next and one detail per tap. Back navigation still works, and this matches how a manual tap from the list behaves, so there is no finding.
- `TaskRemindersViewModel` has no request identity for `list` results if `open` were called with a different task while a load is running. In production each task detail back-stack entry has its own view model store, so one view model only ever serves one task. A test covers switching tasks at the view-model level. There is no finding.
- A stale list result cannot overwrite an add or remove: `canEdit` disables the chips and the remove buttons while `loading` or `busy`. The retry button only shows when `error` is set, and add and remove clear `error` when they start.
- `ReminderSync.sync()` handles its own errors and only rethrows cancellation, so calling it after create or delete cannot crash the view model's coroutine.
- English and German `strings_task_detail.xml` have the same keys and format arguments.
- The changes to `ui/CockpitShell.kt` and `reminders/ReminderOpenRequests.kt` are outside the task's touches but are needed for criterion 2. They are recorded as an assumption in the task file.
- Nothing logs data. The comment on the catch-all in `TaskReminderRepository.call` explains why the body is not logged.
- Criterion 2 (a real notification fires and opens the task) is marked skipped with a reason (no emulator). The tap path is covered by unit tests only, and the skip is recorded correctly.

## Review conclusion

No material issues found. CR-1 is resolved by the added tests. The TW-1 Hilt guard is correct, matches the existing pattern, and is covered by a dedicated test (abb292178). The final delta (4be818e65) only moves one test's view model construction out of the composable to satisfy lint, and behavior does not change. No correctness, integration, security or test-quality defects were found in the final revision 4be818e65.
