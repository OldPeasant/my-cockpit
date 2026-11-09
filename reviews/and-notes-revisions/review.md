# Code Review

Date: 2026-11-09

Task: and-notes-revisions - Note editor: revisions list and restore (round 2, final code)

Scope: `git diff 7d3817057..42443e527 -- android` (whole task diff: implementation bf809d1f1, tests 8db10b448, review fixes 42443e527), committed changes only

Requirements:
- tasks/and-notes-revisions.md acceptance criteria and the recorded assumption (overflow menu item, dialog with preview, save before restore, reuse of the "Not changed" messages)
- SPEC.md §8.1 (note history: keep the last 20 versions for undo of accidental edits)
- api/openapi.yaml `GET /api/v1/notes/{id}/revisions`, `POST /api/v1/notes/{id}/revisions/{number}/restore`
- docs/ARCHITECTURE.md and AGENTS.md conventions (feature folder, strings en + de, lint not suppressed)
- Round 1 report (previous content of this file) and reviews/and-notes-revisions/fixes.md

Git range:
- Branch: main
- HEAD: 42443e527
- Base/start: 7d381705750ae41510671ebc6bb2f40bc5de563e (merge-base with origin/main)
- Commits reviewed: 4 (bf809d1f1, 6690f9fbf, 8db10b448, 42443e527; 6690f9fbf only adds the round-1 report)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Static review only. As the parent asked, I did not run Gradle, because another helper is using the build dir in parallel. No uncommitted changes existed when the review started.

## Round 1 findings: status

- CR-1 (P2, text typed during a restore was silently lost): **fixed.** `NoteEditorUiState.restoring` now exists, and `editable` is `changeable && !restoring` (NoteEditorViewModel.kt:131-134). `changeFlags` sets `restoring` together with `changing` and clears both in every outcome (lines 517, 548). The action check uses `changeable` (line 522), so the restore itself still runs. Every input path checks `editable`: `change()` covers title, body, tags, removeTag and the checklist toggle (line 328); `setEditing(true)` (260), `addTag` (278), `addPhoto` (455) and `openRevisions` (558) check it too. In the UI, the text fields (NoteEditorScreen.kt:280, 303), the tag chips (230), the checklist toggles (330) and the toolbar (430) are all gated on `editable`. `restoreRevision` also refuses while a photo uploads (line 611), so an image cannot land while the editor is not editable and be dropped. Pin and archive pass `restoring = false`, so they still allow editing as before. Tests: NoteEditorRevisionsViewModelTest.kt:444, 484, 498, 514.
- CR-2 (P3, Restore button a silent no-op while another change runs): **fixed.** `NoteRevisionsDialog` takes `canRestore`, and the editor passes `!state.changing && !state.uploading` (NoteEditorScreen.kt:257). This matches the view model guards (`changing`, `uploading`, line 611). The Compose test is at NoteRevisionsDialogTest.kt:136.
- CR-3 (P3, a hidden list came back stale after a conflict): **fixed.** `keepMine` and `useTheirs` call `closeRevisions()` (NoteEditorViewModel.kt:399, 408), which cancels the load job and clears `revisions`. Test: NoteEditorRevisionsViewModelTest.kt:534.

## What I checked in round 2 and found correct

- Criterion 1 (list and restore):
  - The repository maps the generated `listNoteRevisions` and sorts newest first. `restoreRevision` sends If-Match from `etag(base.version)` and maps 404/409/412 through `CONFLICTS` to `SaveResult.Conflict`, the same way `save` does.
  - The view model saves unsaved text first (`save()` inside `changeFlags`), so the replaced text becomes a revision, as SPEC.md §8.1 and the web's flush-before-restore require. The restore then runs under the `saving` mutex with the version just saved.
  - On success the restored title, body and tags replace the editor text, and the status is SAVED. If the note changed elsewhere and nothing is unsaved, the latest server version is shown with the CHANGE_CONFLICT message. A note deleted elsewhere becomes a conflict to decide. Offline and failed results reuse the existing CHANGE_OFFLINE and CHANGE_FAILED messages, as the assumption states.
- Async ordering:
  - `loadRevisions` cancels the previous job, and its result is dropped once the list is closed (`it.revisions == null`).
  - Reopening replaces any load still running.
  - `retryRevisions` only runs after an error.
  - `selectRevision` ignores numbers that are not in the loaded list.
  - Every path through `changeFlags`, including a failed pre-save or a conflict, clears `changing` and `restoring`, so the editor always becomes usable again.
- The dialog covers loading, empty, offline and failed (with Retry) states, and a selectable list (radio role) with a read-only Markdown preview. It is hidden while the editor is not editable, so it never shows over a conflict.
- Criterion 2: all 8 new strings exist in both values/strings_notes.xml and values-de/strings_notes.xml with matching keys. `notes_retry` already existed in both files.
- Criterion 3 (tests):
  - 25 view model tests cover open, load, retry, select, close, ordering, restore, conflict, offline, failed, the guards and the CR-1/CR-3 regressions.
  - 12 Compose/unit dialog tests include the German translation and the editor menu entry.
  - 8 repository tests cover mapping, sorting, If-Match and the status-to-result mapping.
  - The assertions check behaviour, and the mocks are only at the repository and API boundary.
  - I did not run the commands named in criterion 3 (`ktlintCheck testDebugUnitTest lintDebug`, `scripts/check.sh`); the parent has to record that evidence.
- Architecture: all changes stay in `android/.../cockpit/notes` and its tests, which is within the task's touches. The client calls the API through the generated client, and no new dependencies were added. Nothing is logged.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. All three round-1 findings are fixed and covered by tests. Criterion 3 still needs the parent's recorded run of `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh`.
