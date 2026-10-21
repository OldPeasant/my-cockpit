---
title: Calendar task overlay and cross-links
status: in_progress
owner: dell26/agent-1
deps: [web-cal-edit,web-gtd-data,web-links-panel,notes-api]
touches: [web/src/app/features/calendar/overlay,web/src/app/features/calendar/views]
prio: 2
lease_until: 2026-10-21T17:21:57Z
---
## Goal
Tasks in the calendar and linking events (SPEC.md §6.3).

## Acceptance criteria
- [ ] Optional overlay of due tasks and ticklers in day/week views; event detail has linked items, 'Create task from event', 'Create note for event'
- [ ] Vitest component/service tests cover overlay toggle and create-linked actions; `cd web && npm run check` passes
- [ ] Playwright `e2e/calendar-overlay/overlay.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/calendar-overlay/overlay.spec.ts`, see p0-e2e-harness): create note for event → linked both ways

## Notes
- ASSUMPTION (2026-10-21 18:51+0200 by dell26/agent-1): Added from/to (local dates, inclusive) to GET /api/v1/tasks (server gtd/api+domain, outside touches): with a range it returns open tasks whose due OR tickler date lies in it, including tasks still in a tickler. The list API hid ticklers and had no date range, so the overlay could not show ticklers otherwise
- ASSUMPTION (2026-10-21 19:05+0200 by dell26/agent-1): Overlay toggle is per browser (localStorage cockpit.calendar.taskOverlay), off by default; shown in day/week only (month/agenda say so). Due tasks with a time take a 30-min slot at the due time in the settings zone, without a time all day; ticklers show all day on their tickler date. Overlay entries are read-only (no drag); clicking one opens /tasks/{id}
