# Code Review

Date: 2026-11-09

Task: task-reminders - Reminders on tasks (web + Android)

Scope: integration review of the combined result on current HEAD for the parent task. The parent has no code of its own. Reviewed the subtasks' changes together: web-task-reminders (commits 5b4986f21..acde3e1fc, `web/src/app/features/gtd/task-detail`, `web/src/locale`) and and-task-reminders (commits 783bab0a0..b377610c3, `android/.../cockpit/gtd/task`, `ui/CockpitShell.kt`, `reminders/ReminderOpenRequests.kt`, `strings_task_detail.xml` en and de, plus tests). Checked with `git diff 5b4986f21~1 HEAD -- {web paths}` and `git diff 783bab0a0~1 HEAD -- android`. Static review only. As instructed, no Gradle or npm was run.

Requirements:
- tasks/task-reminders.md acceptance criteria
- SPEC.md section 4.6 (Reminders and notifications)
- AGENTS.md project notes
- The subtask reviews in reviews/web-task-reminders/review.md and reviews/and-task-reminders/review.md (findings dispositioned there are not repeated)

Git range:
- Branch: main
- HEAD: 35ffab87b1ad866044dde56dec1d45f161f228ea
- Base/start: 783bab0a0~1 (Android part) and 5b4986f21~1 (web part)
- Commits reviewed: the subtask commit ranges above. Commits after them touch only `tasks/` and `reviews/` in these paths.
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Integration checks (no findings)

- Preset parity: both clients have the same four presets in the same order: at due, 1 hour before due, 1 day before due, on the scheduled day. Web: `task-reminders.ts:18-75`. Android: `TaskReminders.kt:136-165`. Both use 09:00 as the default time for a date without a time (`DEFAULT_REMINDER_TIME`). Both offer "1 hour before" only when the task has a due time. Both compute "1 day before" by calendar day, so the time of day stays the same across a DST change (web builds a new local Date, Android uses `ZonedDateTime.minusDays`). Both compute "1 hour before" as an absolute hour. Both offer only presets that are strictly in the future, and both refuse an exact time at or before now.
- Exact date and time: web uses a `datetime-local` input, read as local time (`localDateTime`). Android uses a date-then-time picker flow. Both send an ISO-8601 instant: web with `toISOString()`, Android with `atOffset(UTC)`.
- Listing and removal: both list the server's reminders for itemType TASK, sorted by time, and mark fired ones ("Sent" / "(sent)"). Both count a 404 on delete as removed.
- Android local alarms: `TaskReminderRepository.add` and `remove` call the existing `ReminderSync.sync()` after a successful call (`TaskReminders.kt:88,94`), so the existing reminder store and alarm scheduling pick up the change. Nothing new schedules alarms on its own.
- Notification opens the task. Web: the server resolves TASK items to `deepLink = "/tasks/{id}"` (`server/.../gtd/infra/GtdItemResolvers.kt:25`). The unchanged `core/notifications` navigates to that link, and the `tasks/:id` route shows the task detail. Android: `OpenItemRequest.taskId` parses a TASK item id, and `CockpitShell` opens Next and then `TaskRoutes.detail(id)` on top (`CockpitShell.kt:104-110`).
- Strings: web has 13 `taskDetail.reminder*` units in both `messages.xlf` and `messages.de.xlf`. Android `strings_task_detail.xml` adds the same 13 keys in `values` and `values-de`, with the same format arguments.
- SPEC.md section 4.6: reminders are created as generic `reminder` rows (itemType TASK, channel NOTIFICATION) through `/api/v1/reminders`. Web relies on the server SSE plus browser notifications, and Android schedules local alarms from synced reminders. This matches the spec.
- Small differences that are not findings: the label wording differs ("When due" on web, "At due time" on Android). Android hides editing when the detail screen is read-only. Web has no read-only state in the task detail.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None new. The web P3 about a duplicate row from a slow add after leaving and returning to the same task (reviews/web-task-reminders/review.md, CR-1) is still present in `task-reminders.ts:182-183`. It was dispositioned in that subtask, and it stays cosmetic: the server data is correct and a reload fixes the view.

## Review conclusion

No material issues found.

The parent worker still has to record evidence for the criteria with `tasks.py verify`. Criterion 3 (a real notification fires and opens the task) is covered only by unit tests on both platforms (`reminder-notifications.service.spec.ts`, `ReminderUiTest.kt`, `OpenItemRequestTaskTest.kt`). Unless it is manually verified, it has to be marked skipped with a reason. Criterion 5 (`sh scripts/check.sh` passes) was not run by this reviewer.
