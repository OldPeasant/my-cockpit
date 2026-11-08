---
title: Reminders on tasks (Android)
status: in_progress
owner: dell26/agent-1
deps: [and-task-detail-dates, web-task-actions, and-links-panel]
prio: 2
parent: task-reminders
lease_until: 2026-11-08T23:19:31Z
---
## Goal
Part of `task-reminders` (Reminders on tasks (web + Android)), which was split.
Goal: Android task detail offers the same as the web: add/remove reminders on a task (presets relative to due/scheduled date or an exact date/time), existing reminders listed, using `POST/DELETE/GET /api/v1/reminders` (itemType TASK). Local alarms are scheduled through the existing reminder sync (`android/.../cockpit/reminders/ReminderSync.kt`, `ReminderAlarms.kt`); trigger a sync after create/delete. Stay inside `android/.../cockpit/gtd/task`, its tests and `strings_task_detail.xml` (en + de).

## Acceptance criteria
- [ ] Android task detail: add/remove reminders, existing reminders listed; local alarms scheduled through the existing reminder sync
- [ ] A reminder fires as a notification and opens the task (manually verified or skipped with reason)
- [ ] New strings in English and German (`res/values{,-de}/strings_task_detail.xml`)
- [ ] Unit tests cover the new behavior; `sh scripts/check.sh` passes

## Notes
- Work done on `task-reminders` before the split is on `origin/wip/task-reminders` (if it exists): merge it first if your part builds on it.
