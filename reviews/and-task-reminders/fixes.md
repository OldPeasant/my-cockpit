# Fix Report

Date: 2026-11-09

Task: and-task-reminders — Reminders on tasks (Android)

Scope: current task (findings TW-1 from tests.md and CR-1 from review.md)

## Fixed findings

### TW-1 — TaskRemindersSection requires Hilt, breaking TaskDetailConvertRouteTest

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskRemindersSection.kt`

Problem:
`TaskRemindersSection` defaulted its `viewModel` parameter to `hiltViewModel()`, so it always resolved a Hilt view model. Screen tests that host `TaskDetailScreen` in a plain `ComponentActivity` (`TaskDetailConvertRouteTest`, 3 tests) failed because that activity is not a Hilt component holder.

Fix:
Applied the same guard as `core/links/LinkedItemsPanel.kt` (`LinkedItemsSection`). The `viewModel` parameter is now `TaskRemindersViewModel? = null`. When no view model is passed and `LocalActivity.current !is GeneratedComponentManagerHolder`, the section renders nothing. Otherwise a private `TaskRemindersSectionContent` runs with the given view model or `hiltViewModel()`. The production caller (`TaskDetailScreen`, Hilt activity) and the test caller that passes an explicit view model (`TaskRemindersContentTest`) keep working unchanged.

Validation:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.*' --tests 'ch.sonensei.cockpit.reminders.*' --tests 'ch.sonensei.cockpit.ui.*'` — Pass (BUILD SUCCESSFUL; 526 tests in 46 suites, 0 failures, 0 errors, 0 skipped; `TaskDetailConvertRouteTest` 3/3 pass)

### CR-1 — No unit tests for the new reminder behavior

Files changed:
- None by the fixer. The test-writer added the tests in commit 2232208de.

Problem:
At the reviewed revision the task added about 540 lines of reminder code with no tests (acceptance criterion 4).

Fix:
Checked the tests added in commit 2232208de against every item CR-1 lists:
- Preset computation (`ReminderPreset.at`, `availablePresets`: 09:00 default, hour-before only with a due time, past and exactly-now presets hidden, DST): `TaskRemindersTest.kt`.
- `reminderAnchors` when the due time cannot be read: `TaskRemindersTest.kt` ("a task's anchors read its due time and ignore an unreadable one").
- Repository status mapping (rejected add is INVALID without sync, 404 on delete counts as removed, unreachable server is OFFLINE, server error or unreadable body is FAILED, sync only after success): `TaskRemindersTest.kt`.
- View model guards (busy, past time, sorted insert, remove, error and retry, reopen behavior): `TaskRemindersTest.kt`.
- `TaskRemindersContent` UI (listing, sent marker, empty and error states, preset chips, custom date/time, remove, read-only, busy): `TaskRemindersContentTest.kt`.
- `OpenItemRequest.taskId` for a task, a malformed id and other item types: `OpenItemRequestTaskTest.kt`.
- Shell opening the task detail on top of Next from a tapped notification: `ReminderUiTest.kt` ("a tapped task notification opens the task's detail on top of Next").

No clear gap found, so no tests were added.

Validation:
- Same Gradle command as above — Pass (all of these suites ran and passed)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.*' --tests 'ch.sonensei.cockpit.reminders.*' --tests 'ch.sonensei.cockpit.ui.*'` — Pass, 526 tests, 0 failures. ktlintFormat changed no files.

Full test suite:
- Not run by the fixer (the parent runs `.taskcheck`).

Lint/type-check/build:
- ktlintCheck and Kotlin compile of main and unit test sources — Pass.

`.taskcheck`:
- Not run (left to the parent worker).

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
