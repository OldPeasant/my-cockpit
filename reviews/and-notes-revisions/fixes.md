# Fix Report

Date: 2026-11-09

Task: and-notes-revisions - Note editor: revisions list and restore

Scope: current task. Findings CR-1, CR-2 and CR-3 from reviews/and-notes-revisions/review.md, and TW-1 from reviews/and-notes-revisions/tests.md (the same problem as CR-1).

## Fixed findings

### CR-1 / TW-1 — Text typed while a restore is in flight is silently lost

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorRevisionsViewModelTest.kt`

Problem:
While `restoreRevision` ran (the save before it, then the restore request), the editor stayed editable. Its `onSaved` then replaced title, body and tags with the restored note and set the status to SAVED. So text typed in the meantime was dropped without notice.

Fix:
- Added a `restoring` flag to `NoteEditorUiState`. `editable` is now `changeable && !restoring`. The new `changeable` property is the old `editable` condition (loaded, not closed, no conflict).
- `changeFlags` takes `restoring` (default false), sets it together with `changing`, and clears both at the end, also after a failure or conflict. Its check before the action now uses `changeable`, so the restore itself still runs. Only `restoreRevision` passes `restoring = true`, so pin and archive work as before and do not block editing.
- While a restore runs, `change()` (title, body, tags, checklist toggles), `setEditing(true)`, `addTag` and `addPhoto` refuse input, and the text fields and the toolbar are disabled or hidden, the same way as during a conflict. Nothing can be typed, so nothing can be lost.
- `restoreRevision` is also refused while a photo uploads. Otherwise the uploaded image would land while `editable` is false and be dropped, or would be overwritten by the restored body.
- Updated the KDoc of `restoreRevision`, `changing` and `editable`.
- Tests: `while a restore runs the text cannot be edited, so nothing typed is replaced by the restored version` suspends the restore, then tries setBody, setTitle, addTag and toggleChecklist. All of them are refused and the status stays SAVED. After the restore completes, the restored text shows and the editor is editable again. The PR also adds `a restore that fails makes the text editable again`, `a pin does not block editing, unlike a restore` and `restore is ignored while a photo uploads, as the restored version would drop its image`.

Validation:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'` — Pass (322 tests, 0 failures)

### CR-2 — Restore button is a silent no-op while another change runs

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteRevisionsDialog.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteRevisionsDialogTest.kt`

Problem:
During a pin, archive or delete (`changing`), `restoreRevision` returned early, but the dialog's Restore button stayed enabled.

Fix:
`NoteRevisionsDialog` takes a `canRestore` parameter (default true). Restore is enabled only when `canRestore` is true and a revision is selected. The editor passes `!state.changing && !state.uploading`, which matches the view model's guards, including the new upload guard from CR-1. Compose test `Restore is disabled while a pin, archive or delete runs, or a photo uploads` checks the button as disabled, disabled, then enabled.

Validation:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'` — Pass

### CR-3 — Hidden revisions list reappears after a conflict is resolved

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorRevisionsViewModelTest.kt`

Problem:
A conflict hid the dialog but kept `revisions`. Once the conflict was resolved, the dialog came back with a list that might be stale.

Fix:
`keepMine` and `useTheirs` now call `closeRevisions()`, which cancels any load still running and sets `revisions = null`. The dialog stays hidden during the conflict, as before, and is gone once the conflict is resolved. The user can open it again for a fresh list. Test `a list hidden by a conflict is closed when the conflict is resolved, so it does not come back stale` covers both useTheirs and keepMine.

Validation:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'` — BUILD SUCCESSFUL. The JUnit XML results show 322 tests, 0 failures, 0 errors.

Full test suite:
- Not run. The full Android unit suite and `.taskcheck` are left to the parent's final validation.

Lint/type-check/build:
- ktlintCheck — Pass (ktlintFormat made no further changes). Kotlin compile of main and test sources — Pass. `lintDebug` was not run.

`.taskcheck`:
- Not run (left to the parent worker).

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
