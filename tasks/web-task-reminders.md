---
title: Reminders on tasks (web)
status: in_progress
owner: dell26/agent-1
deps: [and-task-detail-dates, web-task-actions, and-links-panel]
prio: 2
parent: task-reminders
lease_until: 2026-11-09T00:04:57Z
---
## Goal
Part of `task-reminders` (Reminders on tasks (web + Android)), which was split.
Goal: web task detail lets the user add/remove reminders on a task via `POST/DELETE /api/v1/reminders` (itemType TASK), and lists existing ones (`GET /api/v1/reminders?itemId=...`), as SPEC.md §4.6 and the phase 3 demo step 5 expect. Stay inside `web/src/app/features/gtd/task-detail` and `web/src/locale`; reuse the calendar event dialog's reminder UI patterns (`features/calendar/edit`) and the existing `core/notifications` (no changes there needed).

## Acceptance criteria
- [ ] Web task detail: add/remove reminders (presets relative to due/scheduled date, e.g. at time / 1 h / 1 day before, or an exact date/time); existing reminders listed
- [~] A reminder fires as a browser notification and opens the task (manually verified or skipped with reason)
  - skipped (2026-11-09 00:39+0100 by dell26/agent-1): No interactive browser in this agent session to grant notification permission and wait for a server-fired reminder; firing/opening is the unchanged core/notifications (ReminderNotificationsService opens item.deepLink /tasks/{id}, covered by its unit tests); this task only creates TASK reminders via POST /api/v1/reminders
- [x] New strings in English and German (`web/src/locale`)
  - verified (2026-11-09 00:39+0100 by dell26/agent-1): cd web && npm run i18n:extract -> 12 new taskDetail.reminder* units only; added German targets to messages.de.xlf; npx ng test --watch=false --include='src/locale/**/*.spec.ts' -> 10 passed (unit per message, placeholders kept)
- [ ] Unit tests cover the new behavior; `sh scripts/check.sh` passes

## Notes
- Work done on `task-reminders` before the split is on `origin/wip/task-reminders` (if it exists): merge it first if your part builds on it.
- ASSUMPTION (2026-11-09 00:39+0100 by dell26/agent-1): Presets mirror Android (and-task-reminders): when due, 1 h before due (only with a due time), 1 day before due, on the scheduled day; a date without a time reminds at 09:00 local; only future presets are offered; exact time via datetime-local; presets use the saved task dates, not unsaved edits
- ASSUMPTION (2026-11-09 00:39+0100 by dell26/agent-1): Browser-notification firing for task reminders was not manually verified; relies on existing core/notifications handling of the reminder SSE event with the item's deepLink
