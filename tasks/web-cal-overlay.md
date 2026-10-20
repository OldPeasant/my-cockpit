---
title: Calendar task overlay and cross-links
status: todo
owner:
deps: [web-cal-edit,web-gtd-data,web-links-panel,notes-api]
touches: [web/src/app/features/calendar/overlay,web/src/app/features/calendar/views]
prio: 2
---
## Goal
Tasks in the calendar and linking events (SPEC.md §6.3).

## Acceptance criteria
- [ ] Optional overlay of due tasks and ticklers in day/week views; event detail has linked items, 'Create task from event', 'Create note for event'
- [ ] Vitest component/service tests cover overlay toggle and create-linked actions; `cd web && npm run check` passes
- [ ] Playwright `e2e/calendar-overlay/overlay.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/calendar-overlay/overlay.spec.ts`, see p0-e2e-harness): create note for event → linked both ways

## Notes
