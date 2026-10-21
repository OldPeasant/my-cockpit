# Code Review

Date: 2026-10-21

Task: and-notes - Android notes

Scope: `git diff c167a9fa9ecc107793e5865b7e4953e747e0b10c..c7c89efdc053944e5f454d04fb981d1150ef7daf -- android` (committed changes only). Reviewed revision: c7c89efdc053944e5f454d04fb981d1150ef7daf. This is the third review. It checks the fixes for the second review (CR-1..CR-5, `reviews/and-notes/fixes.md`) and looks for problems those fixes introduced.

Limits of this review: Bash was not available to the reviewer, so it ran no git commands, tests or `tasks.py verify`. The files were read from the working tree. At the start of the session the tree was clean, with HEAD at c7c89efd on main. The production files under `android/app/src/main/java/ch/sonensei/cockpit/notes` were reviewed in full. A test-writer may be committing tests at the same time, so test files were read only to check the claims in the fix report.

Requirements:
- tasks/and-notes.md acceptance criteria and recorded assumptions
- SPEC.md §9.2 item 6 (Android notes)
- docs/ARCHITECTURE.md and AGENTS.md project conventions
- Second review (CR-1..CR-5) and reviews/and-notes/fixes.md
- Parent instructions: check that each earlier finding is resolved and look for issues the fixes introduced (NoteEditors per-editor ViewModelStores, app-scoped NoteSaveScope, onCleared saving, settle(), unsentNewNote, addPhoto create in the save scope)

Git range:
- Branch: main
- HEAD: c7c89efdc053944e5f454d04fb981d1150ef7daf
- Base/start: c167a9fa9ecc107793e5865b7e4953e747e0b10c
- Commits reviewed: the and-notes commits from the base to c7c89efd, including 2abd7142, fac1120a, fed32c21, 3cdd0816 and c7c89efd (the reviewer could not list the full range without git)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Status of the earlier findings

- CR-1 (the shell's top-bar back arrow lost the last text and cancelled a running save): resolved.
  - `NoteSaveScope` (`NoteEditors.kt:18-21`) is an app-wide `@Singleton` scope. `saveNow`, the autosave and `onCleared` all start `save()` there (`NoteEditorViewModel.kt:180-209`). Only the autosave delay still runs in `viewModelScope`.
  - When the Notes back stack entry is popped, `NoteEditors.onCleared` clears every editor store (`NoteEditors.kt:89-92`), and each editor saves what it still holds.
  - The new note created by `addPhoto` now runs as `saves.async { create() }.await()` (`NoteEditorViewModel.kt:300`), so the create cannot be cancelled halfway and then repeated.
  - Tests cover it: `NotesRouteInAppTest` "the shell's back arrow ..." and the `NoteEditorsTest` clearing tests.
- CR-2 (unsaved text in a new note could not be reached again): resolved.
  - `NoteEditors.editor(id)` moves a kept new-note editor to the key of the note it was saved as.
  - `unsentNewNote()` sends "New note" back to a new note that was not created yet.
  - `settle()` tries the saves again whenever the list is shown.
  - Route tests and view-model tests cover both paths.
- CR-3 (no test that the list waits for the save): resolved. `NotesRouteInAppTest` "leaving the editor lets the list reload only once the save is done ..." holds the save on a `CompletableDeferred` and checks the order `list, saved, list`.
- CR-4 (the KDoc of `insertPendingImage` did not match the code): resolved (`NoteEditorViewModel.kt:271-275`).
- CR-5 (editor view models piled up): resolved. Each editor has its own `ViewModelStore`. `settle()` clears the store of every editor that is `settled`. `NoteEditorsTest` "an editor closed with everything saved is dropped ..." covers it.

Checked for problems the fixes could have introduced, and found none:
- `settle()` dropping an editor that is on screen: `settle()` runs in the list branch's `LaunchedEffect` (`NotesScreen.kt:83-88`). Opening an editor removes that branch from composition, which cancels `settle()` while it waits in `join()`. The drop loop has no suspension point, so it cannot run alongside an editor that is open.
- An editor dropped while it uploads or loads: `pending()` counts `uploading` and `loading` as pending, so such an editor is kept until a later `settle()`.
- A note created twice: `create()` and `save()` share the `saving` mutex, and `create()` returns early if the note already exists.
- Configuration changes: `NoteEditors` is a Hilt view model of the Notes entry, `noteEditor` remembers `editors.editor(key)`, and `open()` does nothing for the same opening token.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Behavior the fixes added is not recorded as assumptions**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NotesScreen.kt:95`, `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditors.kt:81-92`, `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt:205-209`
    - Category: Quality (requirements traceability)
    - Problem: The second round of fixes made product decisions that SPEC.md does not define, and none of them is in the task's assumptions:
      - (a) While a new note has not been created yet, "New note" opens that note again instead of an empty one. Offline, the user can therefore write only one new note at a time.
      - (b) Every kept editor's save is tried again each time the list is shown.
      - (c) When the whole Notes screen is left (shell back arrow, or the entry is popped), each editor saves once. If that save fails (offline), the text is dropped without a message. This limits the recorded assumption "unsaved text stays in the editor until saved".
    - Why it matters: AGENTS.md asks for `tasks.py assume` for every decision a human might want to revisit. Item (c) narrows a recorded assumption without saying so.
    - Suggested fix: The parent should record these three behaviors with `python tasks.py assume and-notes "..."`. No code change is needed unless a human rejects them.

- [ ] **CR-2 | The list reload waits for every kept editor's save retry, one after another, with no loading indicator**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NotesScreen.kt:83-88`, `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditors.kt:81-82`
    - Category: Correctness (resilience)
    - Problem: `settle()` runs `saveNow().join()` for each kept editor in turn, and `viewModel.refresh()` runs only after all of them finish. On a slow or unreachable network, each kept editor's retry can take up to the HTTP client timeout. All that time the list shows its previous content with `loading = false`, so no progress bar shows that a reload is still coming.
    - Why it matters: When the network is bad, which is exactly when editors get kept, the list looks loaded but stays stale for one timeout per kept editor.
    - Suggested fix: Start all retries first and then wait for all of them (`map { saveNow() }.joinAll()`). Or only wait for editors that have unsaved text, and show the list's loading state while `settle()` runs. A test with two kept editors whose saves are deferred would pin the behavior down.

## Review conclusion

No material issues found. All five findings of the second review are resolved, and the fixes add no P0, P1 or P2 problems. The two P3 items are optional. The reviewer did not run the test suite or `tasks.py verify`, so the parent must still record the evidence for both acceptance criteria before `done`.
