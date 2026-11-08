---
title: Android notes list: search, pin, archive, delete
status: in_progress
owner: rudi/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/notes,android/app/src/test/java/ch/sonensei/cockpit/notes,android/app/src/main/res/values/strings_notes.xml,android/app/src/main/res/values-de/strings_notes.xml]
prio: 2
lease_until: 2026-11-08T16:13:16Z
extended: rudi/agent-1
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
- ASSUMPTION (2026-11-08 16:12+0100 by rudi/agent-1): Search uses GET /api/v1/search?types=NOTE (limit 50) and shows the loaded notes of the current view/tag whose id was found, in list order (pinned first) rather than relevance order; more than 50 matching notes are cut off
- ASSUMPTION (2026-11-08 16:12+0100 by rudi/agent-1): Pin/archive/delete conflicts (409/412) in the list show a snackbar and reload; in the editor a pin/archive conflict opens the existing conflict banner, a delete conflict shows 'refused'. Delete from the editor discards unsaved text (Undo restores the saved version). Archive/unarchive shows a snackbar without Undo (only delete has Undo, as the criterion asks)
- ASSUMPTION (2026-11-08 16:12+0100 by rudi/agent-1): List query sends archived=true/false via a hand-written Retrofit method, because the generated ArchivedListNotes enum would be sent by its Kotlin name (TRUE)
- ASSUMPTION (2026-11-08 16:48+0100 by rudi/agent-1): A pin/archive refused with 409/412 in the editor while there are no unsaved local edits shows the server's latest version with a 'Not changed' message instead of the Keep mine/Use theirs conflict banner (banner only when there are unsaved edits or the note was deleted); avoids overwriting another device's edit (review CR-4)
- extended by rudi/agent-1: Implementation, review fixes (CR-1..CR-5) and the code re-review (cf600107, 0 P0-P2, 1 P3) are committed. Left: run test-writer on the final code to write reviews/and-notes-list/tests.md (with coverage), write quality.json, record verify for criteria 1-5, then done. sh scripts/check.sh fails in this sandbox only because Docker is unreachable (203 Testcontainers errors in server tests, no server code changed); the Android stage passes on its own. Run the check where Docker is available.
