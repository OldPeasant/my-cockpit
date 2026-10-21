---
title: Inbox/task detail: to calendar
status: done
owner: dell26/agent-1
deps: [web-gtd-convert-note,gtd-convert-event,web-cal-views]
touches: [web/src/app/features/gtd/inbox,web/src/app/features/gtd/task-detail]
prio: 2
lease_until: 
---
## Goal
Enable 'To calendar' in processing and task detail.

## Acceptance criteria
- [x] Dialog picks calendar and time, converts
  - verified (2026-10-21 20:51+0200 by dell26/agent-1): cd web && npx ng test --include='src/app/features/gtd/**/*.spec.ts' -> pass (735 tests); to-calendar-dialog.spec.ts covers calendar select, timed/all-day times, convert request; task-detail/inbox specs cover dialog open -> convertToEvent -> navigation
- [x] Vitest component/service tests cover convert flow; `cd web && npm run check` passes
  - verified (2026-10-21 20:51+0200 by dell26/agent-1): cd web && npm run check (via sh scripts/check.sh at b7a697cbb) -> lint ok, 123 test files passed, build ok; convert flow covered in to-calendar-dialog.spec.ts, task-detail.spec.ts, task-detail.service.spec.ts, task-store.spec.ts, inbox specs

## Notes
- ASSUMPTION (2026-10-21 20:25+0200 by dell26/agent-1): To calendar dialog (gtd/task-detail/to-calendar-dialog.ts) asks only calendar (writable, default first) and times (timed in the settings' zone, or all-day); no zone/repeat/reminders - the task becomes a plain event, the calendar dialog edits the rest. Defaults: next full hour, 1 hour.
- ASSUMPTION (2026-10-21 20:25+0200 by dell26/agent-1): After converting, task detail opens /calendar?date=<event start day>; the inbox shows 'Moved to the calendar as the event ...' with an 'Open calendar' link like 'To reference' does. Inline inbox attribute changes are not saved (an event has no GTD attributes).
- ASSUMPTION (2026-10-21 20:25+0200 by dell26/agent-1): Touched gtd/data/task-store.ts (convertToEvent, outside touches) like web-gtd-convert-note did for convertToNote; reused calendar/edit/event-times and CalendarStore instead of duplicating them. No frontend-design pass: the dialog reuses the existing event dialog's Material look.
- done by dell26/agent-1: `sh scripts/check.sh` passed in 82s on 3c5484f31; criteria verified 2/2
