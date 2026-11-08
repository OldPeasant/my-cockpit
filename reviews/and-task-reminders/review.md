# Code Review

Date: 2026-11-09

Task: and-task-reminders - Reminders on tasks (Android)

Scope: `git diff 362ff9a53..2acf601a7 -- android` (task commits: reminders section, ktlint format, notification opens task detail). The branch has since been rebased (equivalent commits b04aec6fd, 902ed728e, 2573a6faf); the code reviewed is the same.

Requirements:
- tasks/and-task-reminders.md (acceptance criteria and recorded assumptions)
- SPEC.md (reminders, §4.2 API conventions)
- docs/ARCHITECTURE.md, AGENTS.md project notes

Git range:
- Branch: main
- HEAD: 2acf601a765284925585d4e3846c55366da77748 (reviewed head)
- Base/start: 362ff9a5303b6e31a9dc200c705e7e8b4b0d437c
- Commits reviewed: 3

Summary: 0 P0, 1 P1, 0 P2, 0 P3

Static review only. Gradle was not run, as instructed, because another helper was running it at the same time.

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | No unit tests for the new reminder behavior in the reviewed range**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskReminders.kt:1` (and `TaskRemindersSection.kt`, `ReminderOpenRequests.kt:36`, `CockpitShell.kt:104`)
    - Category: Tests
    - Problem: The diff adds about 540 lines of production code and no test file. There is no test under `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/` matching "remind", and nothing tests `OpenItemRequest.taskId` or the shell navigating to the task detail.
    - Why it matters: Acceptance criterion 4 ("Unit tests cover the new behavior") is not met at this revision. Untested logic includes: preset computation (`ReminderPreset.at`, `availablePresets`: 09:00 default, hour-before only with a due time, past presets hidden); repository status mapping (400/422 to INVALID, 404 on delete counts as success, IOException to OFFLINE, sync triggered only on success); view model guards (busy, past time, sorted insert, remove, error and retry); `reminderAnchors` when the due time cannot be parsed; and `OpenItemRequest.taskId` for a malformed id or a non-TASK type.
    - Suggested fix: Add JVM unit tests for the pure functions, `TaskReminderRepository` (with a fake `RemindersApi` and `ReminderSync`), and `TaskRemindersViewModel` (with a fake `TaskReminderSource`). Add a Robolectric or Compose test for `TaskRemindersContent` that covers listing, the empty and error states, the preset chips and remove. Add a unit test for `OpenItemRequest.taskId`. The test-writer helper should cover this before the task is completed.

## P2 Medium

None.

## P3 Low

None.

Notes (checked, no finding):
- The changes to `ui/CockpitShell.kt` and `reminders/ReminderOpenRequests.kt` fall outside the task's stated folders. They are needed for criterion 2 and are recorded as an assumption in the task file.
- Errors in `ReminderSync.sync()` are handled inside it (it returns a result and only rethrows cancellation), so calling it from `add` and `remove` cannot crash the view model's coroutine.
- English and German string sets match: 13 keys in each, with the same format arguments.
- The date picker's conversion from UTC midnight to a local date, and `LocalDate` in `rememberSaveable` (it is Serializable), are correct.
- The UI blocks editing while a load or write is running (`canEdit`), so a stale list response cannot overwrite a reminder that was just added.

## Review conclusion

Material issues found; fix before completing the task. The only issue is the missing unit tests (CR-1). No correctness, integration or security defects were found in the production code.
