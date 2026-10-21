---
title: Notes: list and organization
status: in_progress
owner: R1963MACOS/agent-1
deps: [p0-web-shell,notes-api,p0-e2e-harness]
touches: [web/src/app/features/notes/list]
prio: 1
lease_until: 2026-10-21T03:33:31Z
---
## Goal
Notes overview (SPEC.md §8.1).

## Acceptance criteria
- [ ] Masonry grid/list toggle, pinned first, tag filter, archived toggle, search within notes, create note
- [ ] Vitest component/service tests cover filtering and pin ordering; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-21 05:12+0200 by R1963MACOS/agent-1): Search within notes filters the loaded notes on the client (title, text, tags; every word must match) because GET /notes has no text query; the service loads every page (limit 200) so search covers all notes of the current tag/archive filter
- ASSUMPTION (2026-10-21 05:12+0200 by R1963MACOS/agent-1): Create note uses an inline composer (title, Markdown text, tags; prefilled with the active tag filter) instead of opening the editor, which does not exist yet; cards link to /notes/<id>, which web-notes-editor must route
- ASSUMPTION (2026-10-21 05:12+0200 by R1963MACOS/agent-1): Edited notes/notes.routes.ts (to load list/notes-page) and app.spec.ts (added /notes to BUILT) outside touches; both are one-line wiring every feature does
- ASSUMPTION (2026-10-21 05:12+0200 by R1963MACOS/agent-1): Grid view is the default (stored per browser in localStorage cockpit.notes.view); the composer is hidden in the archived view
