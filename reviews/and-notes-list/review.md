# Code Review

Date: 2026-11-08

Task: and-notes-list - Android notes list: search, pin, archive, delete

Scope: `git diff 85e8706445f51b80f5260d234fd74a34dfa2ac06..ff66edb6` (commits cdf98beb implementation, 5338db19 test-writer tests and the first review, 3d414400 fixes for CR-1 to CR-5, 0ab21e7c re-review, ff66edb6 fix for the re-review's P3). This review is on the final code. It checks the latest fix (ff66edb6) in detail and looks again for new issues. Read in full: `NotesListViewModel.kt` and the ff66edb6 diff with its test. Read through the task diff: `NotesRepository.kt`, `NotesModels.kt`, `NoteEditorViewModel.kt`, and the list/message parts of `NotesScreen.kt`. Uncommitted test changes from a parallel test-writer were ignored.

Requirements:
- tasks/and-notes-list.md (acceptance criteria 1-5, assumptions #1-#4)
- AGENTS.md project notes (en/de strings, no secrets or note contents in logs, conventions)
- Reviewer checklist: async ordering, loading and error states leave a recoverable screen
- reviews/and-notes-list/review.md (previous re-review), reviews/and-notes-list/fixes.md

Git range:
- Branch: main
- HEAD: ff66edb6a2572d1b5841b470f1bcd48d6502fc28
- Base/start: 85e8706445f51b80f5260d234fd74a34dfa2ac06
- Commits reviewed: 5

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Status of the previous re-review finding

- Previous CR-1 (search message matched by value): resolved. The list and the search now keep their own messages (`listMessage`, `searchMessage`), and the screen shows `listMessage ?: searchMessage` (`NotesListViewModel.kt:103-110`). A list load sets only `listMessage` (line 125). A search sets only `searchMessage` (line 193), and clearing the search resets only `searchMessage` (line 168). A failed list load's banner and its Retry therefore stay when a search with the same error is cleared or redone. The new test `clearing or redoing a search that failed like the list load keeps the load's message and its Retry` (`NotesListViewModelTest.kt:353`) covers exactly this case, and its last step checks that Retry clears the message. The earlier tests for search messages (lines 327, 388, 440) are consistent with the new logic.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A finished list load clears a search failure that answered first in the same reload**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NotesListViewModel.kt:126`
    - Category: Correctness
    - Problem: When a list load finishes, `refresh()` still sets `searchMessage = null` (line 126). This contradicts the field's own comment ("only a search (or clearing it) changes it", line 106). `refresh()` starts the list load and `search(0)` at the same time (line 137), so either can answer first. Suppose the search fails (offline, or the search endpoint returns 5xx) and answers before the list load, which also waits for `tags()` and every list page. Then the list load's success wipes out the search's message. If the search had no hits yet (`hits == null`), `NotesScreen.kt:195,200` shows no banner, no Retry and no empty text: only a blank list under the search field. This reload happens every time the list is shown again (`NotesScreen.kt:106`), on Retry, and after a conflict or a change during a load (`reloadIfLoading`). The behavior is older than ff66edb6 (the old code replaced the whole message). The new split makes the line unnecessary, because every refresh with a search text runs the search again, and that search sets `searchMessage` itself.
    - Why it matters: In this case the user is not told that the search failed, and has no Retry. They have to edit or clear the search text to recover. It only happens when the search fails, the list load succeeds, and the search answers first, so the impact is small.
    - Suggested fix: Remove `searchMessage = null` from the list load's completion (line 126). Only `search()` and `setQuery("")` should change `searchMessage`. Add a unit test in which the reload's search fails and answers before the list (for example, hold `repo.list` on a `CompletableDeferred`), and check that the message is still the search's message after the list answers.

## Review conclusion

No material issues found.

The latest fix (ff66edb6) is correct and has a test. In the production code, criteria 1-4 are implemented as the previous reviews describe:
- server search with `types=NOTE` (assumption #1);
- pin and archive from the row menu and the editor menu, and an Archived view through a hand-written `archived=true/false` query (assumption #3);
- delete to the Trash, with Undo through `POST /api/v1/trash/NOTE/{id}/restore`, also after a delete from the editor;
- strings in `values/` and `values-de/`.

No secrets or note contents are logged. The remaining P3 is a narrow edge case in the message handling and does not block completion.

Not verified by this review:
- Criterion 5 (`./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh`): this review ran no builds or tests. The task notes say `scripts/check.sh` fails in this sandbox only because Docker is not available for the server's Testcontainers tests. The parent worker must record real evidence or a skip with a reason through `tasks.py verify`.
