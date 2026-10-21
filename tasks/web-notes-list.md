---
title: Notes: list and organization
status: in_progress
owner: R1963MACOS/agent-1
deps: [p0-web-shell,notes-api,p0-e2e-harness]
touches: [web/src/app/features/notes/list]
prio: 1
lease_until: 2026-10-21T03:53:41Z
---
## Goal
Notes overview (SPEC.md §8.1).

## Acceptance criteria
- [x] Masonry grid/list toggle, pinned first, tag filter, archived toggle, search within notes, create note
  - verified (2026-10-21 05:30+0200 by R1963MACOS/agent-1): Implemented in web/src/app/features/notes/list (notes-page, note-card, notes.service, notes-filter): CSS-columns masonry grid + list toggle (localStorage), pinned section first, server tag filter chips, Notes/Archived toggle, client search over all loaded pages, inline composer -> POST /notes. npx ng test --watch=false --include='src/app/features/notes/**/*.spec.ts' -> 4 files, 79 tests passed (component tests exercise each feature via DOM + HttpTestingController). Masonry layout itself not checkable in jsdom.
- [x] Vitest component/service tests cover filtering and pin ordering; `cd web && npm run check` passes
  - verified (2026-10-21 05:30+0200 by R1963MACOS/agent-1): notes-filter.spec (sortNotes/matchesQuery/filterNotes), notes-page.spec (pin moves to top/back, tag filter, search, archived), notes.service.spec; cd web && npm run check -> exit 0 (lint clean, 83 files / 1513 tests passed, build OK; only pre-existing initial-bundle budget warning)

## Notes
- ASSUMPTION (2026-10-21 05:12+0200 by R1963MACOS/agent-1): Search within notes filters the loaded notes on the client (title, text, tags; every word must match) because GET /notes has no text query; the service loads every page (limit 200) so search covers all notes of the current tag/archive filter
- ASSUMPTION (2026-10-21 05:12+0200 by R1963MACOS/agent-1): Create note uses an inline composer (title, Markdown text, tags; prefilled with the active tag filter) instead of opening the editor, which does not exist yet; cards link to /notes/<id>, which web-notes-editor must route
- ASSUMPTION (2026-10-21 05:12+0200 by R1963MACOS/agent-1): Edited notes/notes.routes.ts (to load list/notes-page) and app.spec.ts (added /notes to BUILT) outside touches; both are one-line wiring every feature does
- ASSUMPTION (2026-10-21 05:12+0200 by R1963MACOS/agent-1): Grid view is the default (stored per browser in localStorage cockpit.notes.view); the composer is hidden in the archived view
