---
title: Today view: calendar events
status: in_progress
owner: dell26/agent-1
deps: [web-next-today,web-cal-views]
touches: [web/src/app/features/gtd/today]
prio: 2
lease_until: 2026-10-21T11:41:28Z
---
## Goal
Today shows today's events (SPEC.md §5.3).

## Acceptance criteria
- [ ] Today lists today's events (time-ordered) above tasks
- [ ] Vitest component/service tests cover events section; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-21 12:54+0200 by dell26/agent-1): Today's events are built into TodayView (rendered above the task sections) instead of via the TODAY_SECTIONS slot, which renders after tasks; the section reuses the calendar feature's CalendarStore/OccurrenceStore, shows only visible calendars, and 'today' is the browser's local day (like today's tasks), not the calendar settings time zone. Cancelled occurrences are shown struck through.
