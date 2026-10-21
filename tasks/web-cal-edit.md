---
title: Calendar event editing
status: in_progress
owner: dell26/agent-3
deps: [web-cal-views]
touches: [web/src/app/features/calendar/edit,web/src/app/features/calendar/views]
prio: 1
lease_until: 2026-10-21T13:34:23Z
extended: dell26/agent-3
---
## Goal
Create and edit events (SPEC.md §6.3).

## Acceptance criteria
- [x] Drag to create/move/resize; event dialog (title, time/all-day, tz, recurrence, location, description, reminders, attendees read-only for external)
  - verified (2026-10-21 15:16+0200 by dell26/agent-3): cd web && npx ng test --watch=false --include 'src/app/features/calendar/**/*.spec.ts' -> 11 files, 213 tests passed (calendar-view: drag/select on writable calendars, resize saved, drop reverted on cancel/conflict/error; event-dialog/event-form: title, time/all-day, tz, recurrence presets+custom rule, location, description, reminders, attendees read-only for synced calendars); Playwright e2e/calendar-edit/edit.spec.ts drags an occurrence in the real UI (criterion 4)
- [x] Recurring edits ask THIS / FOLLOWING / ALL; 'changed remotely' conflicts shown with reload
  - verified (2026-10-21 15:16+0200 by dell26/agent-3): same Vitest run -> pass: scope-dialog offers THIS/FOLLOWING/ALL (only FOLLOWING/ALL for rule/calendar change), event-dialog 'asks which occurrences a change is for and saves with that scope', 'shows a 409/412 conflict with Reload, which loads the current version', calendar-view drag conflict snackbar with Reload; e2e edit.spec.ts picks THIS in the scope prompt against the e2e stack
- [x] Vitest component/service tests cover scope prompt and conflict handling; `cd web && npm run check` passes
  - verified (2026-10-21 15:16+0200 by dell26/agent-3): npm --prefix web run check (lint + Vitest) at 8c8b2d1c -> exit 0, 114 test files / 2275 tests passed, incl. scope-dialog, event-dialog, event-editor, event-edit.service conflict specs
- [x] Playwright `e2e/calendar-edit/edit.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/calendar-edit/edit.spec.ts`, see p0-e2e-harness): move one occurrence of a weekly event
  - verified (2026-10-21 14:45+0200 by dell26/agent-3): npm --prefix web run e2e -- e2e/calendar-edit/edit.spec.ts (deploy/docker-compose.e2e.yml stack via podman-compose, chromium) at 26d4b7d2 -> 'moves one occurrence of a weekly event' passed (22 passed incl. phase demo project)

## Notes
- ASSUMPTION (2026-10-21 13:47+0200 by dell26/agent-3): Conflicts: both 412 (stale ETag) and 409 (changed concurrently / changed at the provider, SPEC.md §6.2) are shown as 'changed elsewhere' with a Reload action (dialog banner; snackbar after a drag); the dialog's Reload discards the unsaved edits
- ASSUMPTION (2026-10-21 13:47+0200 by dell26/agent-3): Event dialog repeat choices are presets (none/daily/weekly/monthly/yearly = FREQ=X); an existing rule the presets can't express is kept as 'Custom (rule)'. Changing the repeat rule or calendar of a series offers only FOLLOWING/ALL (the API rejects them for THIS)
- ASSUMPTION (2026-10-21 13:47+0200 by dell26/agent-3): Attendees are editable (add email / remove) for LOCAL calendars, read-only with their status for synced calendars; a synced event's calendar can't be changed. Delete (with scope prompt) is included in the dialog although not in the criteria, since the API supports it
- extended by dell26/agent-3: Implementation, e2e spec, Vitest specs and round-1 fixes are committed; npm run check passes. Left: (1) fix review round-2 CR-1 (P2, reviews/web-cal-edit/review.md): when EventEditService.reread returns undefined (series changed elsewhere / override moved >1 day), don't offer a futile 'Try again' - close the dialog, refresh the view, tell the user; add a spec. (2) re-run test-writer (tests.md is still missing; its extra specs are committed) and code-reviewer on final code. (3) run Playwright npm run e2e -- e2e/calendar-edit/edit.spec.ts on final code. (4) tasks.py verify 1-4, quality.json, .taskcheck, done.
