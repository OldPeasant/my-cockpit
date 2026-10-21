# Fix Report

Date: 2026-10-21

Task: and-notes - Android notes

Scope: second code review, findings CR-1 to CR-5 of `reviews/and-notes/review.md` (reviewed revision af0780bf). The parent worker accepted all of them as valid and in scope. Changes stay inside `android/app/src/main/java/ch/sonensei/cockpit/notes` and its tests. The fixes are in commit 3cdd0816 (an earlier fixer run that stopped before it wrote this report) plus one follow-up commit from this run, which closes a gap left in the CR-1 fix.

## Fixed findings

### CR-1 — The shell's top-bar back arrow leaves the editor without saving and cancels a running save

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditors.kt` (new, 3cdd0816)
- `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt` (3cdd0816 and this run)
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorsTest.kt`, `NotesRouteInAppTest.kt`

Problem:
`navigateUp()` pops the Notes back stack entry. That cancels `viewModelScope`, so the pending autosave and any running save are lost.

Fix:
- (3cdd0816) Saves now run in `NoteSaveScope`, an app-scoped `@Singleton` `CoroutineScope` that the view models do not own. `NoteEditorViewModel.onCleared()` cancels only the autosave wait and starts `save()` in that scope. `save()` still does nothing during a conflict or before the note has loaded. `saveNow()` and the autosave also start their saves there, so clearing the editor never cancels a save that has started.
- (this run) The same gap remained in one more place. `addPhoto` created a new note via `create()`, which is also a save, inside `viewModelScope`. Leaving during that create cancelled it, so the request could still reach the server while the editor still thought the note was not created. The save in `onCleared` then created the note a second time. The create now runs as `saves.async { create() }.await()`. Only the photo upload that follows is still cancelled when the user leaves.

Validation:
- `NotesRouteInAppTest` "the shell's back arrow leaves the Notes screen and still saves the text typed last" (real `MainActivity`, the top bar's "Back") — Pass
- `NoteEditorsTest`: "cleared before its autosave saves the text at once", "cleared while its save runs lets that save finish", "cleared during a conflict or before its note loaded saves nothing", "leaving the Notes screen saves what every editor still holds" — Pass
- New `NoteEditorsTest` "an editor cleared while a photo creates its new note lets that create finish, so the note is created once". It failed before this run's change (a second `repo.save(null, ...)`) and passes after it — Pass

### CR-2 — Text left unsaved in a new note can never be reached again

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditors.kt`, `NotesScreen.kt`, `NoteEditorScreen.kt` (3cdd0816)

Problem:
A new note's editor sits under the key `new:<uuid>`. Opening the created note from the list used a different view model, which loaded the older server text, and nothing retried the unsaved save.

Fix:
`NoteEditors` (a Hilt view model of the Notes entry) keeps the editors itself. `editor(<id>)` also finds a kept new-note editor whose note now has that id and moves it to that key. `open()` then keeps its pending text instead of loading. `unsentNewNote()` returns a kept new note that could not be created yet, and "New note" opens it again instead of starting an empty one. `settle()` runs each time the list is shown, before `refresh()`. It retries every kept editor's save.

Validation:
- `NoteEditorsTest` "a new note's unsaved text is reached again by opening the note it was saved as", "a new note that could not be created is opened again by New note" — Pass
- `NotesRouteInAppTest` "a new note left with unsaved text after it was created shows that text when opened from the list", "a new note that could not be created is opened again by New note, with its text" — Pass

### CR-3 — No test checks that the list waits for the editor's save before reloading

Files changed:
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NotesRouteInAppTest.kt`

Problem:
Removing `saving?.join()` in `NotesRoute` would have left every test green.

Fix:
`NotesRouteInAppTest` "leaving the editor lets the list reload only once the save is done, so it shows the new text" holds `repo.save` on a `CompletableDeferred`. It asserts that the list has loaded only once while the save is pending, and that the order is `list, saved, list` after the save completes.

Validation:
- that test — Pass

### CR-4 — The comment on `insertPendingImage` does not match what the code does

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt` (3cdd0816)

Problem:
The KDoc claimed the code checks whether the text changed.

Fix:
The KDoc now says what the code does. With keep mine, the image goes at the stored cursor, clamped to the text, even if the text changed. With use theirs, it goes at the end. The behavior is unchanged.

Validation:
- Read the code against the comment; `ktlintCheck` — Pass

### CR-5 — Editor view models still pile up, one per new note and one per note opened

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditors.kt`, `NoteEditorViewModel.kt`, `NoteEditorScreen.kt`, `NotesScreen.kt` (3cdd0816)

Problem:
`hiltViewModel(key = "note:$key")` left one view model per new note and per distinct note in the Notes entry's store.

Fix:
Each editor has its own `ViewModelStore`, held by `NoteEditors`. `NoteEditorViewModel` is no longer a `@HiltViewModel`. `NoteEditors` builds it with the injected repository, images and save scope. `settle()` saves and waits, then clears the store of every editor with nothing pending (`settled`: no unsaved text, save, upload, load or conflict). Only editors that still hold something are kept. `NoteEditors.onCleared()` clears them all, which saves through CR-1.

Validation:
- `NoteEditorsTest` "an editor closed with everything saved is dropped, so editors do not pile up", "an editor left with unsaved text is kept, saved again when the list shows, then dropped" — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.NoteEditorsTest'` with the new test and without the fix -> 9 tests, 1 failed (the expected regression); with the fix -> passes as part of the run below

Full test suite:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' lintDebug` -> BUILD SUCCESSFUL; 12 notes test classes, 130 tests, 0 failures, 0 errors, 0 skipped
- The whole app unit suite and `sh scripts/check.sh` were not run by the fixer; the parent runs them before `done`

Lint/type-check/build:
- `ktlintCheck` and `lintDebug` -> Pass (in the run above)

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
