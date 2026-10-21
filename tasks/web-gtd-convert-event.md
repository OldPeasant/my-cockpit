---
title: Inbox/task detail: to calendar
status: in_progress
owner: dell26/agent-1
deps: [web-gtd-convert-note,gtd-convert-event,web-cal-views]
touches: [web/src/app/features/gtd/inbox,web/src/app/features/gtd/task-detail]
prio: 2
lease_until: 2026-10-21T18:42:37Z
---
## Goal
Enable 'To calendar' in processing and task detail.

## Acceptance criteria
- [ ] Dialog picks calendar and time, converts
- [ ] Vitest component/service tests cover convert flow; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-21 20:25+0200 by dell26/agent-1): To calendar dialog (gtd/task-detail/to-calendar-dialog.ts) asks only calendar (writable, default first) and times (timed in the settings' zone, or all-day); no zone/repeat/reminders - the task becomes a plain event, the calendar dialog edits the rest. Defaults: next full hour, 1 hour.
