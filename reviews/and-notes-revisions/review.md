# Code Review

Date: 2026-11-09

Task: and-notes-revisions - Note editor: revisions list and restore (round 3, final code and final tests)

Scope: the whole task diff `git diff 7d3817057..c29d14ca2 -- android`, committed changes only. Round 3 adds the test-only commit c29d14ca2 (`git show HEAD -- android`) on top of the production code from rounds 1 and 2.

Requirements:
- tasks/and-notes-revisions.md acceptance criteria and the recorded assumption (overflow menu item, dialog with preview, save before restore, reuse of the "Not changed" messages)
- SPEC.md §8.1 (note history: keep the last 20 versions for undo of accidental edits)
- api/openapi.yaml `GET /api/v1/notes/{id}/revisions`, `POST /api/v1/notes/{id}/revisions/{number}/restore`
- docs/ARCHITECTURE.md and AGENTS.md conventions (feature folder, strings en + de, lint not suppressed)
- Round 1 and round 2 reports (earlier content of this file), reviews/and-notes-revisions/fixes.md and tests.md

Git range:
- Branch: main
- HEAD: c29d14ca2
- Base/start: 7d381705750ae41510671ebc6bb2f40bc5de563e (merge-base with origin/main)
- Commits reviewed: 6 (bf809d1f1 implementation, 6690f9fbf round-1 report, 8db10b448 tests, 42443e527 review fixes, 1d5a0ee92 round-2 report, c29d14ca2 final tests)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Static review only. As the parent asked, I did not run Gradle, because a check is running in parallel. No uncommitted changes existed when the review started.

## Earlier rounds: summary

- Round 1 (bf809d1f1, 8db10b448) found three issues. CR-1 (P2): text typed during a restore was silently lost. CR-2 (P3): the Restore button did nothing while another change was running. CR-3 (P3): a hidden list came back stale after a conflict.
- Round 2 (42443e527) confirmed all three were fixed:
  - CR-1: `NoteEditorUiState.restoring` was added and `editable = changeable && !restoring`. Every input path and every UI control is gated on `editable`, and a restore is refused while a photo uploads.
  - CR-2: `canRestore` is now passed to the dialog.
  - CR-3: `keepMine` and `useTheirs` now call `closeRevisions()`.
- Round 2 also re-checked criteria 1 to 3, async ordering, the strings (en + de), the architecture and the test coverage, and found no issues (0 P0, 0 P1, 0 P2, 0 P3).

## Round 3: production code unchanged

`git diff --stat 42443e527..HEAD -- android ':!android/app/src/test'` is empty. Since round 2, the only changes under `android` are the two test files. The other changes are in `reviews/`. The full task diff `7d3817057..HEAD -- android` therefore has the same production content as the code reviewed in round 2, and the round-2 conclusions still apply.

## Round 3: the new tests

I checked each new test against the current production code (NoteEditorViewModel.kt, NoteEditorScreen.kt, NotesRepository.kt):

- NoteEditorRevisionsViewModelTest.kt, "while a restore runs nothing else starts ...":
  - A `CompletableDeferred` keeps the restore pending, so `restoring` is true.
  - Each of these is refused while the restore runs: `setEditing(true)` (line 260, `editable`), `addPhoto` (`editable`), `togglePin`/`toggleArchived` (`changeFlags` guard, line 516), `delete` (line 626) and `openRevisions` (line 558).
  - The `coVerify(exactly = 0)` matchers fit the real signatures: `setFlags(base, pinned, archived)` takes 3 arguments, `uploadImage(noteId, photo)` takes 2 and `delete(note)` takes 1.
  - After the restore completes, the test checks that the restored body is shown.
  - Pin, archive and delete are also blocked by `changing` alone, so this test does not isolate `restoring` for them. Edit mode, photo and the list are the parts that depend on `restoring`, and the test covers those. This is acceptable.
- "a restore that ends in a conflict leaves no restore lock ...":
  - It covers both conflict branches of `changeFlags`. With no local edits, `Conflict(current)` shows the latest version. `Conflict(null)` becomes `NoteConflict(null)`.
  - It then checks that `keepMine` (saving as a new note through `save(null, any())`) makes the editor editable again. The stubs match the call order.
- "when saving first meets a conflict nothing is restored ...":
  - `save()` returns false on a conflict, so `restoreRevision` is never called. The typed text stays, and both flags are cleared.
  - After `keepMine` saves over `theirs`, editing works again.
  - The last two lines (a stub followed by `advanceUntilIdle()`) only drain the pending autosave and assert nothing. This is harmless and not worth a finding.
- "when saving first fails offline the restore lock is lifted ...": the save returns Offline, so `save()` returns false and the editor becomes editable again. This matches line 548, which always clears `changing` and `restoring`.
- NoteRevisionsDialogTest.kt, "while a restore runs the text fields are disabled ...":
  - With `restoring = true`, `editable` is false. As a result, TITLE and BODY are disabled (NoteEditorScreen.kt:280, 303), the toolbar (CAMERA, GALLERY, TOGGLE, MENU) is not composed (line 430), and the dialog is hidden (line 256).
  - The test then changes the `mutableStateOf` editor state and checks that the fields, the toggle and the menu come back. MENU is enabled because `changing` is false.
- Flakiness:
  - There are no real delays (`Thread.sleep` or `delay`) in either file.
  - The view model tests use the shared `StandardTestDispatcher` with `runCurrent`/`advanceUntilIdle` and virtual time.
  - Pending work is controlled with `CompletableDeferred`.
  - The Compose test changes state through snapshot state, and the compose rule waits for idle before asserting.
- The assertions check observable behaviour (UI state, which repository calls were made or not), and the mocks are only at the repository boundary.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. The production code is the same as in round 2. The new tests are correct and meaningful, and they do not depend on real time. Criterion 3 still needs the parent's recorded run of `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh`.
