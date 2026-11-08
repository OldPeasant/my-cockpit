# Code Review

Date: 2026-11-08

Task: and-notes-list - Android notes list: search, pin, archive, delete

Scope: `git diff 6fbabadfbafe865bbdba739d8dc5de77cb446065..52c0e918537a05ff9b12de6d0dec808173c949ca`, without `tasks/` and `reviews/`. This covers the whole task: the implementation, the test-writer tests, the fixes for review CR-1 to CR-5 and re-review CR-1, and the last commit 52c0e918, which fixes the previous re-review's P3. I read in full: `NotesListViewModel.kt`, the 52c0e918 diff and its new test. I read the task diff of `NotesRepository.kt`, `NotesModels.kt`, `NoteEditorViewModel.kt`, `NoteEditorScreen.kt`, `NotesScreen.kt` and both `strings_notes.xml` files.

Requirements:
- tasks/and-notes-list.md (acceptance criteria 1-5, assumptions #1-#4)
- AGENTS.md project notes (en/de strings, no secrets or note contents in logs, stay inside `touches`)
- Reviewer checklist: async ordering, loading and error states end in a screen the user can recover from
- reviews/and-notes-list/review.md (previous re-review), reviews/and-notes-list/fixes.md

Git range:
- Branch: main
- HEAD: 52c0e918537a05ff9b12de6d0dec808173c949ca
- Base/start: 6fbabadfbafe865bbdba739d8dc5de77cb446065
- Commits reviewed: 8 (including the merge 699f800c of origin/wip/and-notes-list)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of the previous re-review finding

- Previous CR-1 (P3: a finished list load cleared a search failure that had answered first): resolved. 52c0e918 removes `searchMessage = null` from the list load's completion. `searchMessage` is now written in only two places: the search result (`NotesListViewModel.kt:192`) and clearing the search text (`NotesListViewModel.kt:167`). This matches the field's comment (line 106). Nothing stale can remain. Every `refresh()` with a search text runs `search(0)` again, which sets `searchMessage` itself. Without a search text, `searchMessage` is already null, because clearing the text resets it. The new test `a reload whose search fails before the list answers keeps the search's message` (`NotesListViewModelTest.kt:388`) holds `repo.list` on a `CompletableDeferred`, so the failed search answers first. It checks that `FAILED` is shown while loading. After the list answers, it checks that `FAILED` is still shown, loading has ended and the notes are present. With the removed line, this test would fail.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.

The production code implements criteria 1-4:
- Criterion 1: server search over `GET /api/v1/search` with `types=NOTE` and limit 50, trimmed and capped at 200 characters (assumption #1). The search waits 300 ms after typing stops. The previous text's hits are dropped at once. A newer search or a cleared text cancels a running one, and `loading` ends in every path.
- Criterion 2: pin/unpin and archive/unarchive from the row menu and the editor menu. The Archived view uses a hand-written `archived=true/false` query (assumption #3). Conflicts are handled as recorded in assumptions #2 and #4.
- Criterion 3: delete moves the note to the Trash with `If-Match`, and Undo restores it through `POST /api/v1/trash/NOTE/{id}/restore`. Undo also works after a delete from the editor, through `onDeleted`.
- Criterion 4: all 22 new strings exist in `values/` and `values-de/`.

The changes stay inside the task's `touches`. No secrets or note contents are logged.

Not verified by this review:
- Criterion 5: this review ran no builds or tests, because Gradle cannot run in this sandbox. According to the parent worker, `ktlintCheck`, the notes unit tests and the full Android unit suite pass on HEAD. `lintDebug` and `sh scripts/check.sh` must be recorded by the parent worker through `tasks.py verify`, either as evidence or as a skip with a reason.
