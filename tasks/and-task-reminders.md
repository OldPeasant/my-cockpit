---
title: Reminders on tasks (Android)
status: in_progress
owner: dell26/agent-1
deps: [and-task-detail-dates, web-task-actions, and-links-panel]
prio: 2
parent: task-reminders
lease_until: 2026-11-08T23:44:44Z
---
## Goal
Part of `task-reminders` (Reminders on tasks (web + Android)), which was split.
Goal: Android task detail offers the same as the web: add/remove reminders on a task (presets relative to due/scheduled date or an exact date/time), existing reminders listed, using `POST/DELETE/GET /api/v1/reminders` (itemType TASK). Local alarms are scheduled through the existing reminder sync (`android/.../cockpit/reminders/ReminderSync.kt`, `ReminderAlarms.kt`); trigger a sync after create/delete. Stay inside `android/.../cockpit/gtd/task`, its tests and `strings_task_detail.xml` (en + de).

## Acceptance criteria
- [x] Android task detail: add/remove reminders, existing reminders listed; local alarms scheduled through the existing reminder sync
  - verified (2026-11-09 00:27+0100 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.*' --tests 'ch.sonensei.cockpit.reminders.*' --tests 'ch.sonensei.cockpit.ui.*' -> 527 tests, 0 failures (TaskRemindersContentTest: list/add presets/custom date+time/remove in task detail; TaskRemindersTest: POST/DELETE/GET /api/v1/reminders itemType TASK via MockWebServer, ReminderSync.sync() called once after each successful create/delete)
- [~] A reminder fires as a notification and opens the task (manually verified or skipped with reason)
  - skipped (2026-11-09 00:03+0100 by dell26/agent-1): No emulator/device in this environment to fire a real notification; the tap path (OpenItemRequest.taskId -> TaskRoutes.detail in CockpitShell) is covered by unit tests only
- [x] New strings in English and German (`res/values{,-de}/strings_task_detail.xml`)
  - verified (2026-11-09 00:28+0100 by dell26/agent-1): grep -c 'name="task_detail_remind' android/app/src/main/res/values{,-de}/strings_task_detail.xml -> 13 and 13 (same keys/format args); TaskDetailStringsTest + android lintDebug in scripts/check.sh pass (no missing translations)
- [x] Unit tests cover the new behavior; `sh scripts/check.sh` passes
  - verified (2026-11-09 00:28+0100 by dell26/agent-1): New TaskRemindersTest, TaskRemindersContentTest, OpenItemRequestTaskTest, ReminderUiTest task-detail test pass (task-scoped line coverage 90.5%, TaskReminders.kt 97.7%); sh scripts/check.sh at 4be818e65 -> exit 0

## Notes
- Work done on `task-reminders` before the split is on `origin/wip/task-reminders` (if it exists): merge it first if your part builds on it.
- ASSUMPTION (2026-11-09 00:03+0100 by dell26/agent-1): Android reminder presets: at due time (due date + due time, else 09:00), 1 h before due (only with a due time), 1 day before due, scheduled date 09:00, plus a custom date+time picker; past presets hidden. Web task reminders are not built yet, so these are Android's own choice.
- ASSUMPTION (2026-11-09 00:03+0100 by dell26/agent-1): After create/delete the repository calls ReminderSync.sync() directly (not the WorkManager one-off, whose KEEP policy may skip it) so local alarms update at once.
- ASSUMPTION (2026-11-09 00:03+0100 by dell26/agent-1): Touched ui/CockpitShell.kt and reminders/ReminderOpenRequests.kt (outside the task's touches) so a TASK reminder notification opens the task detail on top of Next; before it only opened Next, which would not meet criterion 2.
