# Code Review

Date: 2026-11-08

Task: and-notes-list - Android notes list: search, pin, archive, delete

Scope: `git diff 9693de16690ae78b85572ce04086689ab656d81d..8be33ea2` (commits 0a43ccdd implementation, 01417be4 test-writer tests and the first review, 8be33ea2 fixes). This is a re-review of the final code. It checks that the earlier findings CR-1 to CR-5 are resolved and looks for new issues. Production code was read in full: `NotesListViewModel.kt`, `NotesRepository.kt`, `NoteEditorViewModel.kt`, `NotesScreen.kt`, `NoteEditorScreen.kt`, `strings_notes.xml` (en, de). Tests were read selectively: `NotesListViewModelTest.kt` in full, and the other test files through the diff summary and `fixes.md`. A test-writer may be editing tests in parallel.

Requirements:
- tasks/and-notes-list.md (acceptance criteria 1-5, assumptions #1-#4)
- SPEC.md §8.1 (as cited in the first review), docs/PARITY.md row for and-notes-list
- AGENTS.md project notes (en/de strings, no secrets or note contents in logs, conventions)
- reviews/and-notes-list/review.md (first review), reviews/and-notes-list/fixes.md

Git range:
- Branch: main
- HEAD: 8be33ea2
- Base/start: 9693de16690ae78b85572ce04086689ab656d81d
- Commits reviewed: 3

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Status of the earlier findings

- CR-1 (loading never ends when the search is cleared): resolved. `listLoading` and `searchRunning` are tracked separately, and `cancelSearch()` resets the loading flag (`NotesListViewModel.kt:169-174`). `loading` follows the other flag when either request answers (lines 124, 191). Covered by the tests `clearing the search while it waits for the server ends the loading` and `a reload with a search text keeps loading until both ...`.
- CR-2 (an older search's hits shown under a new text): resolved. `setQuery` sets `hits = null` when the trimmed text changes (line 157). The message of a failed search is tracked in `searchMessage`. Tests were added. See CR-1 below for a remaining edge case in how that message is matched.
- CR-3 (a stale load brings back a changed note): resolved for the case reported. `replace`/`remove` restart a running load (`reloadIfLoading`, lines 268-284). Covered by the test `a load that started before an archive does not bring the archived note back`.
- CR-4 (Keep mine after a flag conflict overwrites the other device's edit): resolved. `changeFlags` takes the server's version and shows `CHANGE_CONFLICT` when nothing typed here is unsaved. It opens the conflict banner only when there are unsaved edits or the note was deleted (`NoteEditorViewModel.kt:380-391`). Assumption #4 records this.
- CR-5 (German "Unpin" label): resolved. It is now "Lösen" (`values-de/strings_notes.xml:50`).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The search message is matched by value, so clearing or redoing a search can remove a failed list load's banner**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NotesListViewModel.kt:161`
    - Category: Correctness
    - Problem: `searchMessage` holds only the enum value (`OFFLINE`/`FAILED`), and the code removes the message with `it.message.takeUnless { m -> m == searchMessage }` (lines 161 and 192). It cannot tell a list-load message from a search message that has the same value. Example while offline: the list load fails (`message = OFFLINE`, `searchMessage = null`). The user then types a search, which also fails (`searchMessage = OFFLINE`). Clearing the search (line 161), or a later successful search (line 192), removes the `OFFLINE` banner, even though the list load still failed. If the first load failed (`loaded == false`), the screen ends up with no empty text, no banner and no Retry button (`NotesScreen.kt:195, 200`). The user has to switch view or filter, or leave the screen, to reload.
    - Why it matters: A load failure that is still current loses its Retry banner. This touches the "every path leaves a recoverable screen" rule, but only in a narrow case: both requests fail with the same kind of error.
    - Suggested fix: Keep the list's message and the search's message as separate values, for example private `listMessage` and `searchMessage`, and expose `message = listMessage ?: searchMessage`. A list load sets only `listMessage`. A search, or clearing it, sets only `searchMessage`. Add a unit test: the list load fails with `OFFLINE`, the search fails with `OFFLINE`, the search is cleared, and the message is still `OFFLINE`.

## Review conclusion

No material issues found.

All five earlier findings are resolved in the current code, and each fix has a unit test (CR-1 to CR-3 in `NotesListViewModelTest.kt`; CR-4 per `fixes.md`). In the production code:
- Criteria 1-4 are implemented. The search uses the server's search with `types=NOTE` (assumption #1). Pin, archive and delete are in both the row menu and the editor menu, and there is an Archived view. Delete moves the note to the Trash, and the list snackbar's Undo restores it, also after a delete from the editor. All new string keys exist in both `values/` and `values-de/`.
- No secrets, note contents or bodies are logged. `NotesHttpFailure` carries only the HTTP status.

The remaining P3 is a minor edge case in the message handling and does not block completion.

Not verified by this review:
- Criterion 5 (Gradle checks and `scripts/check.sh`): this review ran no build or test commands.
- The recorded `tasks.py verify` evidence: Bash was not available to this reviewer, so it was not inspected. The parent worker must make sure every criterion has evidence from a real run, including `.taskcheck`. `fixes.md` reports that `scripts/check.sh` failed in the fixer's sandbox because Docker was not available.
