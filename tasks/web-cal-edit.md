---
title: Calendar event editing
status: in_progress
owner: dell26/agent-3
deps: [web-cal-views]
touches: [web/src/app/features/calendar/edit,web/src/app/features/calendar/views]
prio: 1
lease_until: 2026-10-21T12:46:27Z
---
## Goal
Create and edit events (SPEC.md §6.3).

## Acceptance criteria
- [ ] Drag to create/move/resize; event dialog (title, time/all-day, tz, recurrence, location, description, reminders, attendees read-only for external)
- [ ] Recurring edits ask THIS / FOLLOWING / ALL; 'changed remotely' conflicts shown with reload
- [ ] Vitest component/service tests cover scope prompt and conflict handling; `cd web && npm run check` passes
- [ ] Playwright `e2e/calendar-edit/edit.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/calendar-edit/edit.spec.ts`, see p0-e2e-harness): move one occurrence of a weekly event

## Notes
- ASSUMPTION (2026-10-21 13:47+0200 by dell26/agent-3): Conflicts: both 412 (stale ETag) and 409 (changed concurrently / changed at the provider, SPEC.md §6.2) are shown as 'changed elsewhere' with a Reload action (dialog banner; snackbar after a drag); the dialog's Reload discards the unsaved edits
- ASSUMPTION (2026-10-21 13:47+0200 by dell26/agent-3): Event dialog repeat choices are presets (none/daily/weekly/monthly/yearly = FREQ=X); an existing rule the presets can't express is kept as 'Custom (rule)'. Changing the repeat rule or calendar of a series offers only FOLLOWING/ALL (the API rejects them for THIS)
- ASSUMPTION (2026-10-21 13:47+0200 by dell26/agent-3): Attendees are editable (add email / remove) for LOCAL calendars, read-only with their status for synced calendars; a synced event's calendar can't be changed. Delete (with scope prompt) is included in the dialog although not in the criteria, since the API supports it
