# Code Review

Date: 2026-11-09

Task: and-task-reminders - Reminders on tasks (Android)

Scope: re-review of the final revision, `git diff 362ff9a53..1a1937346 -- android`. This covers the task's production commits (reminders section, ktlint format, notification opens the task detail), the test commit 2232208de (TaskRemindersTest.kt, TaskRemindersContentTest.kt, OpenItemRequestTaskTest.kt, ReminderUiTest.kt) and the fixer's Hilt guard in TaskRemindersSection.kt (1a1937346, TW-1).

Requirements:
- tasks/and-task-reminders.md (acceptance criteria and recorded assumptions)
- SPEC.md (reminders, §4.2 API conventions)
- docs/ARCHITECTURE.md, AGENTS.md project notes
- reviews/and-task-reminders/fixes.md (fixer report for TW-1 and CR-1)

Git range:
- Branch: main
- HEAD: 1a1937346a88c80ef27b87f9345726fd84f6d36b
- Base/start: 362ff9a5303b6e31a9dc200c705e7e8b4b0d437c
- Commits reviewed: all task commits in the range (11 files under android changed, +1366 / -8)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

This was a static review only. Gradle was not run, as instructed, because the test-writer was running it at the same time. Test results come from the fixer's report (526 tests in the focused suites, 0 failures), not from a run by this reviewer.

## Status of earlier findings

- CR-1 (P1, no unit tests for the new reminder behavior): **resolved**. Every gap CR-1 listed now has a test:
  - Preset computation and `availablePresets` (09:00 default, hour-before only with a due time, a preset in the past or exactly now is hidden, DST, a task without dates): `TaskRemindersTest.kt`.
  - `reminderAnchors` with an unreadable due time: `TaskRemindersTest.kt`.
  - `TaskReminderRepository` against MockWebServer with the real generated `RemindersApi`. The tests check the HTTP method, path, query, a UTC `remindAt` in the POST body, 400/422 mapped to INVALID, 404 on delete counted as success, 500 and an unreadable body mapped to FAILED, an unreachable server mapped to OFFLINE, an empty 201 body, and `ReminderSync.sync()` called exactly once on success and never on failure or list.
  - `TaskRemindersViewModel`: open and reopen, switching task, load error and retry, an unexpected exception, the busy guard, past and exactly-now times, sorted insert, remove, and failed add or remove keeping the list.
  - `TaskRemindersContent` (Robolectric/Compose): listing, the sent marker, empty, offline, invalid and failed states, the past-time message without retry, preset chips, the custom date then time flow, cancel, busy, read-only, and the section wired to a view model and to the task detail panel.
  - `OpenItemRequest.taskId` (a valid id, a malformed or empty id, other types, a lower-case type): `OpenItemRequestTaskTest.kt`. The shell opening the task detail on top of Next and going back to Next: `ReminderUiTest.kt`.
- TW-1 (Hilt-only section broke plain-activity screen tests): the fix is correct. `TaskRemindersSection` now takes `viewModel: TaskRemindersViewModel? = null` and renders nothing when there is no view model and `LocalActivity.current` is not a `GeneratedComponentManagerHolder`. This is the same pattern as `LinkedItemsSection` in `core/links/LinkedItemsPanel.kt`. Production (`TaskDetailRoute` in the Hilt `MainActivity`) still goes through `hiltViewModel()`. A test covers the case with no Hilt and no view model.

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

No material issues found. CR-1 is resolved by the added tests, and the TW-1 Hilt guard is correct and consistent with the existing pattern. No correctness, integration, security or test-quality defects were found in the final revision.
