---
title: Web notes: delete and edit tags
status: in_progress
owner: dell26/agent-1
deps: [web-back-navigation]
touches: [web/src/app/features/notes,web/src/locale]
prio: 2
lease_until: 2026-11-09T01:15:33Z
---
## Goal
Web notes have no delete (API `deleteNote` unused) and tags can only be set when creating a note.

## Acceptance criteria
- [ ] Delete in the editor and on the note card (to Trash, with undo)
- [ ] Tags editable in the editor (chips with suggestions)
- [ ] New UI strings are in `web/src/locale` (English + German)
- [ ] Unit tests cover the new behavior; `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-09 01:47+0100 by dell26/agent-1): Tag suggestions use a native datalist (tags in use from GET /notes/tags) instead of mat-autocomplete: simpler, accessible, no new deps. Tag changes save right away through the editor's autosave PATCH (tags sent only when changed, with If-Match).
- ASSUMPTION (2026-11-09 01:47+0100 by dell26/agent-1): Deleting in the editor first saves pending edits, then moves the note to the Trash and goes to /notes; the Undo snackbar (8 s) restores it via the Trash restore API and reopens the note. Card delete removes it from the list, undo reloads the list.
