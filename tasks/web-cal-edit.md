---
title: Calendar event editing
status: in_progress
owner: dell26/agent-3
deps: [web-cal-views]
touches: [web/src/app/features/calendar/edit,web/src/app/features/calendar/views]
prio: 1
lease_until: 2026-10-21T11:51:21Z
---
## Goal
Create and edit events (SPEC.md §6.3).

## Acceptance criteria
- [ ] Drag to create/move/resize; event dialog (title, time/all-day, tz, recurrence, location, description, reminders, attendees read-only for external)
- [ ] Recurring edits ask THIS / FOLLOWING / ALL; 'changed remotely' conflicts shown with reload
- [ ] Vitest component/service tests cover scope prompt and conflict handling; `cd web && npm run check` passes
- [ ] Playwright `e2e/calendar-edit/edit.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/calendar-edit/edit.spec.ts`, see p0-e2e-harness): move one occurrence of a weekly event

## Notes
