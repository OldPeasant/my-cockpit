---
title: Android notes list: search, pin, archive, delete
status: in_progress
owner: dell26/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/notes,android/app/src/test/java/ch/sonensei/cockpit/notes,android/app/src/main/res/values/strings_notes.xml,android/app/src/main/res/values-de/strings_notes.xml]
prio: 2
lease_until: 2026-11-08T17:25:19Z
extended: 
releases: 1
---
## Goal
Android notes have no search, pin, archive or archived view (web: `notes-page.html:6-50`, `note-card.ts`).

## Acceptance criteria
- [x] Search field (server search over title and body)
  - verified (2026-11-08 17:57+0100 by dell26/agent-1): android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' on a4af5022 -> pass; NotesListViewModelTest covers GET /api/v1/search?types=NOTE (title+body server search), out-of-order/cancel/failure cases; reviewed by code-reviewer (0 findings)
- [x] Pin/unpin and archive/unarchive (row menu and editor menu); an Archived view
  - verified (2026-11-08 17:57+0100 by dell26/agent-1): notes unit + Compose tests (NotesListViewModelTest, NoteEditorViewModelTest incl. editor unpin, NotesActionsScreensTest row/editor menus, Archived view with archived=true query) on a4af5022 -> pass
- [x] Delete a note (to Trash, with undo)
  - verified (2026-11-08 17:57+0100 by dell26/agent-1): notes tests for delete to Trash + Undo via POST /api/v1/trash/NOTE/{id}/restore (list and editor) on a4af5022 -> pass
- [x] New strings are in English and German (`res/values/` + `res/values-de/`)
  - verified (2026-11-08 17:57+0100 by dell26/agent-1): strings_notes.xml en+de: all 22 new strings present in both (code-reviewer check); lintDebug (MissingTranslation) -> pass; German-locale screen tests pass
- [x] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass
  - verified (2026-11-08 17:57+0100 by dell26/agent-1): android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug -> pass (createDebugUnitTestCoverageReport full unit suite pass, task-scoped lines 99.0%, branches 92.9%); sh scripts/check.sh on a4af5022 -> all checks passed

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 16:12+0100 by rudi/agent-1): Search uses GET /api/v1/search?types=NOTE (limit 50) and shows the loaded notes of the current view/tag whose id was found, in list order (pinned first) rather than relevance order; more than 50 matching notes are cut off
- ASSUMPTION (2026-11-08 16:12+0100 by rudi/agent-1): Pin/archive/delete conflicts (409/412) in the list show a snackbar and reload; in the editor a pin/archive conflict opens the existing conflict banner, a delete conflict shows 'refused'. Delete from the editor discards unsaved text (Undo restores the saved version). Archive/unarchive shows a snackbar without Undo (only delete has Undo, as the criterion asks)
- ASSUMPTION (2026-11-08 16:12+0100 by rudi/agent-1): List query sends archived=true/false via a hand-written Retrofit method, because the generated ArchivedListNotes enum would be sent by its Kotlin name (TRUE)
- ASSUMPTION (2026-11-08 16:48+0100 by rudi/agent-1): A pin/archive refused with 409/412 in the editor while there are no unsaved local edits shows the server's latest version with a 'Not changed' message instead of the Keep mine/Use theirs conflict banner (banner only when there are unsaved edits or the note was deleted); avoids overwriting another device's edit (review CR-4)
- extended by rudi/agent-1: Implementation, review fixes (CR-1..CR-5) and the code re-review (cf600107, 0 P0-P2, 1 P3) are committed. Left: run test-writer on the final code to write reviews/and-notes-list/tests.md (with coverage), write quality.json, record verify for criteria 1-5, then done. sh scripts/check.sh fails in this sandbox only because Docker is unreachable (203 Testcontainers errors in server tests, no server code changed); the Android stage passes on its own. Run the check where Docker is available.
- release by rudi/agent-1: Implementation, fixes for review CR-1..CR-5 and re-review CR-1 are committed; latest test-writer tests + second re-review committed in 7e90e382 (notes unit tests pass). Second re-review has 0 P0-P2, 1 P3: NotesListViewModel.kt:126 refresh() still sets searchMessage = null on list-load completion (remove it + test with repo.list held on a CompletableDeferred). Left: optionally fix that P3 via fixer (then re-run code-reviewer), run test-writer on final code to write reviews/and-notes-list/tests.md with coverage, write quality.json, verify criteria 1-5, done. sh scripts/check.sh only failed in this sandbox because Docker is unreachable (server Testcontainers); the Android stage passes.
