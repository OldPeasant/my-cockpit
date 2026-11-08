---
title: Android notes list: search, pin, archive, delete
status: todo
owner:
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/notes,android/app/src/test/java/ch/sonensei/cockpit/notes,android/app/src/main/res/values/strings_notes.xml,android/app/src/main/res/values-de/strings_notes.xml]
prio: 2
---
## Goal
Android notes have no search, pin, archive or archived view (web: `notes-page.html:6-50`, `note-card.ts`).

## Acceptance criteria
- [ ] Search field (server search over title and body)
- [ ] Pin/unpin and archive/unarchive (row menu and editor menu); an Archived view
- [ ] Delete a note (to Trash, with undo)
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
