---
title: Web notes: delete and edit tags
status: todo
owner:
deps: [web-back-navigation]
touches: [web/src/app/features/notes,web/src/locale]
prio: 2
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
