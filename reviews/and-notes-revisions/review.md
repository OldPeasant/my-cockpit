# Code Review

Date: 2026-11-09

Task: and-notes-revisions - Note editor: revisions list and restore

Scope: task commit 758b571d0 on top of base c6a0de9a1 (`git diff c6a0de9a1e..758b571d0`), committed changes only

Requirements:
- tasks/and-notes-revisions.md acceptance criteria
- SPEC.md §8.1 (note history: keep the last 20 versions for undo of accidental edits)
- api/openapi.yaml `GET /api/v1/notes/{id}/revisions`, `POST /api/v1/notes/{id}/revisions/{number}/restore`
- docs/ARCHITECTURE.md and AGENTS.md conventions
- Web reference: web/src/app/features/notes/editor/note-revisions-dialog.ts, `openRevisions` in note-editor-page.ts

Git range:
- Branch: main
- HEAD: 758b571d0
- Base/start: c6a0de9a1e3722a9893e4d7684216c4a3453cbc7
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 1 P2, 2 P3

Static review only. I did not run Gradle, as the parent asked, because another helper uses the build dir.

What I checked and found correct:
- The repository calls the generated `listNoteRevisions` / `restoreNoteRevision` with the If-Match ETag. It maps 404/409/412 through `CONFLICTS` to `SaveResult.Conflict`, the same way `save` does, and sorts newest first, matching the contract and the web dialog.
- Restore saves the unsaved text first (`changeFlags` -> `save()`), so the current text becomes a revision and the restore can be undone. This matches the web's `flush()` before `restore`.
- Loading the list cancels the previous job. A result is dropped when the list was closed or the editor was reopened (`it.revisions == null`), so an older answer never shows. Retry only runs after an error.
- The dialog covers loading, empty, offline and failed (with Retry) states, plus a selectable list with a read-only Markdown preview. `MarkdownView` has no lazy layout, so putting it inside `verticalScroll` is safe.
- All 8 new strings exist in both `values/strings_notes.xml` and `values-de/strings_notes.xml`. `notes_retry` already existed in both.
- The Android editor has no links panel, so it has nothing to reload after a restore (the web's `reloadLinks`).

Note on the tests criterion: the reviewed commit has no tests for the new behavior (no `revision` matches under `android/app/src/test/.../notes/`). The parent says a test-writer is adding them in parallel. So this is not a finding against this scope, but criterion 3 is open until those tests exist and pass.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Text typed while a restore is in flight is silently lost**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt:599`
    - Category: Correctness
    - Problem: `restoreRevision` passes an `onSaved` that overwrites `title`, `body` and `tags` with the restored note and sets `status = SaveStatus.SAVED`. While the request runs, the editor stays editable (`editable` ignores `changing`; `change()` and the title/body text fields stay enabled). So any edit made during the request is replaced when the answer arrives. The autosave that edit scheduled waits on the `saving` mutex. When it runs, `isSaved()` is already true, so it saves nothing. There is no conflict or message: the edit just disappears. The default `onSaved` for pin/archive only replaces `note`, so this risk is new with this task.
    - Why it matters: the user loses input with no notice, on slow connections or when the pre-restore save takes time. It goes against the editor's rule that typed text is never dropped, and against SPEC.md §8.1's aim of protecting against accidental loss.
    - Suggested fix: inside `onSaved`, replace the text only when the state still matches what was there when the restore was sent (e.g. `s.isSaved()` against the pre-restore note, or the title/body/tags captured before the request). Otherwise set `note = restored` and keep the typed text as unsaved. That makes the next save a conflict or overwrite, the same way a concurrent change is handled. A simpler alternative: block editing while a restore runs (e.g. a `restoring` flag folded into `editable`). Add a ViewModel test that edits during a suspended restore.

## P3 Low

- [ ] **CR-2 | Restore button is a silent no-op while another change runs**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt:597`
    - Category: Quality
    - Problem: if a pin, archive or delete is still running (`changing == true`), `restoreRevision` returns early and leaves the dialog open. The Restore button stays enabled (`NoteRevisionsDialog.kt:97` only checks `selectedRevision != null`), so tapping it does nothing.
    - Why it matters: the button looks broken, with no feedback.
    - Suggested fix: pass `changing` to the dialog and disable Restore while it is true, or close the dialog and run the restore after the running change.

- [ ] **CR-3 | Hidden revisions list reappears after a conflict is resolved**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorScreen.kt:256`
    - Category: Correctness
    - Problem: the dialog is only hidden while `!state.editable` (e.g. an autosave conflict comes up while the list is open). `state.revisions` is kept. When the user resolves the conflict (`keepMine` / `useTheirs`), the dialog pops up again with the list loaded before the conflict. That list may no longer include the content just replaced.
    - Why it matters: an unexpected dialog appears with possibly stale data. Restoring from it still goes through the ETag check, so nothing is corrupted.
    - Suggested fix: close the revisions list (`revisions = null`, cancel `revisionsJob`) when a conflict is set, or reload it when the editor becomes editable again.

## Review conclusion

Material issues found; fix before completing the task. CR-1 is a possible silent loss of typed text. CR-2 and CR-3 are minor. The tests for criterion 3 still have to be added by the parallel test-writer.
