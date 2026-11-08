# Code Review

Date: 2026-11-08

Task: and-notes-list - Android notes list: search, pin, archive, delete

Scope: `git diff 6fbabadfbafe865bbdba739d8dc5de77cb446065..a4af5022f91cc50a455396c7c794455d4a1b8d9d`, without `tasks/` and `reviews/`. This covers the whole task and the final revision a4af5022f91cc50a455396c7c794455d4a1b8d9d. The earlier review covered everything up to 52c0e918537a05ff9b12de6d0dec808173c949ca: the implementation, the test-writer tests, the fixes for review CR-1 to CR-5 and re-review CR-1, and commit 52c0e918, which fixes the previous re-review's P3. For that revision I read `NotesListViewModel.kt` in full, plus the 52c0e918 diff and its new test. I read the task diff of `NotesRepository.kt`, `NotesModels.kt`, `NoteEditorViewModel.kt`, `NoteEditorScreen.kt`, `NotesScreen.kt` and both `strings_notes.xml` files. This update also reviews the delta `git diff 52c0e918 a4af5022 -- android`. That delta is one added test in `NoteEditorViewModelTest.kt` and no production code.

Requirements:
- tasks/and-notes-list.md (acceptance criteria 1-5, assumptions #1-#4)
- AGENTS.md project notes (en/de strings, no secrets or note contents in logs, stay inside `touches`)
- Reviewer checklist: async ordering, and loading and error states that end in a screen the user can recover from
- reviews/and-notes-list/review.md (previous reviews), reviews/and-notes-list/fixes.md

Git range:
- Branch: main
- HEAD: a4af5022f91cc50a455396c7c794455d4a1b8d9d
- Base/start: 6fbabadfbafe865bbdba739d8dc5de77cb446065
- Commits reviewed: 9 (including the merge 699f800c of origin/wip/and-notes-list, and a4af5022)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Delta 52c0e918..a4af5022

- `NoteEditorViewModelTest.kt:873`: new test `unpin of a pinned note sends pinned false over its version, and pin then pins it again`. It opens a pinned note and checks two things:
  - The first `togglePin()` calls `setFlags(pinned, pinned = false, archived = null)`, which is the base version, and shows the returned version 4. `changing` goes back to false.
  - The second `togglePin()` goes over the new version (`unpinned`, version 4) and shows version 5.
- `repo.save` is never called, because the text did not change. This matches `NoteEditorViewModel.togglePin` (`NoteEditorViewModel.kt:358`), which flips `note.pinned`, and `changeFlags`, which runs the action on the current saved note while holding the save lock. Writing `archived = null` explicitly in the stubs matches `NotesRepository.setFlags`, where it defaults to `null` (`NotesRepository.kt:209`).
- The test adds editor coverage for unpin (criterion 2). It also checks that each flag change goes over the latest version, so an outdated version is not sent. The test asserts behavior and does not mock the code under test. It follows the file's existing `test { vm -> }` and `loaded` fixture conventions. I found no issues.

## Status of the previous re-review finding

- Previous CR-1 (P3: a finished list load cleared a search failure that had answered first): resolved. 52c0e918 removes `searchMessage = null` from the list load's completion. `searchMessage` is now written in only two places: the search result (`NotesListViewModel.kt:192`) and clearing the search text (`NotesListViewModel.kt:167`), as the field's comment says (line 106). No outdated message can remain:
  - With a search text, every `refresh()` runs `search(0)` again, and the search sets `searchMessage` itself.
  - Without a search text, `searchMessage` is already null, because clearing the text reset it.
- The test `a reload whose search fails before the list answers keeps the search's message` (`NotesListViewModelTest.kt:388`) holds `repo.list` on a `CompletableDeferred`, so the failed search answers first. It checks that `FAILED` shows while the list is loading. After the list answers, it checks that `FAILED` still shows, loading has ended and the notes are present. The test would fail if the removed line came back.

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

The production code implements criteria 1-4. It is unchanged since 52c0e918.
- Criterion 1: server search over `GET /api/v1/search` with `types=NOTE` and limit 50. The text is trimmed and capped at 200 characters (assumption #1).
  - The search starts 300 ms after typing stops.
  - Results for the previous text are removed right away.
  - A newer search or clearing the text cancels a running search.
  - `loading` ends in every path.
- Criterion 2: pin/unpin and archive/unarchive from the row menu and the editor menu. The Archived view uses a hand-written `archived=true/false` query (assumption #3). Conflicts are handled as recorded in assumptions #2 and #4. As of a4af5022, a test also covers unpin from the editor.
- Criterion 3: delete moves the note to the Trash, sending `If-Match`. Undo restores it through `POST /api/v1/trash/NOTE/{id}/restore`. Undo also works after a delete from the editor, through `onDeleted`.
- Criterion 4: all 22 new strings exist in `values/` and `values-de/`.

The changes stay inside the task's `touches`. Nothing logs secrets or note contents.

Not verified by this review:
- Criterion 5: this review ran no builds or tests, because Gradle cannot run in this sandbox. The parent worker reports these results:
  - On a4af5022: `android/gradlew -p android ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'` passes.
  - On 52c0e918: `lintDebug` and the full Android unit suite (`createDebugUnitTestCoverageReport`) pass.
- The parent worker must record `lintDebug` and `sh scripts/check.sh` with `tasks.py verify`, either as evidence or as a skip with a reason.
