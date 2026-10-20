---
title: Inbox/task detail: to calendar
status: todo
owner:
deps: [web-gtd-convert-note,gtd-convert-event,web-cal-views]
touches: [web/src/app/features/gtd/inbox,web/src/app/features/gtd/task-detail]
prio: 2
---
## Goal
Enable 'To calendar' in processing and task detail.

## Acceptance criteria
- [ ] Dialog picks calendar and time, converts
- [ ] Vitest component/service tests cover convert flow; `cd web && npm run check` passes

## Notes
