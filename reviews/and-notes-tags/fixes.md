# Fix Report

Date: 2026-11-09

Task: and-notes-tags - Note editor: tag chips with suggestions

Scope: current task; findings CR-1 to CR-5 from reviews/and-notes-tags/review.md (base b6e7dc2da, task commits b056900b3 and 8dc50f014)

## Fixed findings

### CR-1 — The new tag behavior has no tests (criterion 3)

Files changed:
- none by the fixer (the test-writer added the tests in 8dc50f014)

Problem:
The implementation commit added no tests for adding and removing tags, the repository `tags` field, saving and adopting tags, or the Compose tag editor.

Fix:
Verified only. 8dc50f014 adds `NoteEditorTagsViewModelTest` (add, normalize, duplicate, limits, remove, save with text, server spelling adopted, tag edited during a save, new note, failed tag load, reload per opening), `NoteTagsEditorTest` (chips, comma, Enter, focus loss, suggestions, read view, `splitTagInput`, `matchingTags`) and repository tests in `NotesRepositoryTest` (tags patched only when changed, empty list, order/case change, new note with or without tags). All of them pass.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'` — Pass (NoteEditorTagsViewModelTest 20, NoteTagsEditorTest 14, NotesRepositoryTest 35 tests, 0 failures)

### CR-2 — NoteEditorsTest strict mock has no stub for tags()

Files changed:
- none by the fixer (stub added by the test-writer in 8dc50f014)

Problem:
`NoteEditors.open()` loads the tags in use through `repo.tags()`, which the strict mock in `NoteEditorsTest` did not stub.

Fix:
Verified only. `NoteEditorsTest.setUp` stubs `coEvery { repo.tags() } returns NotesResult.Ok(emptyList())` (line 52).

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'` — Pass (NoteEditorsTest 13 tests, 0 failures)

### CR-3 — Tapping anywhere on a tag chip removes the tag

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteTagsEditor.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteTagsEditorTest.kt`

Problem:
The `InputChip` `onClick` called `onRemove(tag)`, so any tap on a chip (also in the rendered read view) removed the tag and autosaved.

Fix:
The chip body has no action (`onClick = {}`). The trailing close icon is its own clickable (role Button, enabled only when the chip is) with the `notes_tag_remove` content description and calls `onRemove`. The remove description moved from the chip to the icon only. Tests: a new test taps the label of a chip and checks nothing is removed (a long tag is used, because the icon's 48 dp minimum touch target covers a whole short chip when text measures narrow under Robolectric); the disabled test also taps the remove icon; the editor-screen test removes through the icon.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.NoteTagsEditorTest'` — Pass

### CR-4 — A tag that is not added is dropped silently and the typed text is cleared

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteTagsEditor.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteTagsEditorTest.kt`

Problem:
`commit()` and the comma split in `onValueChange` ignored the result of `onAdd` and always cleared the input.

Fix:
`commit()` (Enter and focus loss) clears the input only when the text is blank or `onAdd` returns true. The comma path keeps the refused non-blank parts (trimmed) in front of the text still being typed, joined with ", "; when nothing is refused the behavior is unchanged. Suggestion taps still clear the filter text (not typed tag input, outside the finding). Tests: comma with a refused tag keeps it (also mixed with an accepted one), Enter with a refused tag keeps it.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.NoteTagsEditorTest'` — Pass

### CR-5 — An older known-tag load can overwrite a newer one

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorTagsViewModelTest.kt`

Problem:
`loadKnownTags()` started a new coroutine per opening without cancelling the previous one.

Fix:
The load is kept in `knownTagsJob`, which is cancelled before a new load starts (same pattern as `linkSearchJob`). New test: the first load is held, a second opening loads "fresh", the first then completes with "stale", and the known tags stay "fresh".

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.NoteEditorTagsViewModelTest'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat` then `./gradlew ktlintCheck` — Pass
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'` — Pass (18 classes, 0 failures)

Full test suite:
- `cd android && ./gradlew testDebugUnitTest` — Pass

Lint/type-check/build:
- `cd android && ./gradlew lintDebug` — Pass

`.taskcheck`:
- Not run (left to the parent worker; only the Android part changed and its lint, unit tests and Android lint pass)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.

# Round 2

Date: 2026-11-09

Task: and-notes-tags - Note editor: tag chips with suggestions

Scope: current task; the new findings CR-1 (P2) and CR-2 (P3) of the re-review in reviews/and-notes-tags/review.md (HEAD ae6047d03), recorded here as R2-CR-1 and R2-CR-2. The Round 1 entries above stay valid.

## Fixed findings

### R2-CR-1 — The comma path can save a refused tag joined with the next one, or a tag that contains a comma

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteTagsEditor.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteTagsEditorTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorTagsViewModelTest.kt`

Problem:
Confirmed in the code: `onValueChange` rebuilt the input as the trimmed refused parts plus the rest joined with ", ". Typing `home,` (home refused) left `home` without its comma, so the next tag merged into it (`homework`). Text after a comma got another ", " on every keystroke, and Enter or losing focus passed the whole text, comma included, to `onAdd`, which accepted it.

Fix:
- The editor now handles keystrokes, Enter and focus loss with one function: it splits the text on commas and line breaks, offers each finished part (all parts on Enter or focus loss) to `onAdd`, and keeps only the refused parts in the input, as typed, each followed by its comma, then the part still being typed (new `keptTagInput`). The text is therefore stable across keystrokes (no growing spaces), a refused tag stays separated from the next one, and `onAdd` never receives a comma.
- As a second guard, `NoteEditorViewModel.addTag` refuses a tag containing a comma or a line break (the web client cannot create one either, as it splits on commas).
- Tests: Compose tests type `home,work` one key at a time with `home` refused and press Enter (only `work` is added, `home` stays in the input), type `home, wor` then more keys and leave the input (the input stays `home, words`, only `words` is added, no part with a comma is offered); a unit test for `keptTagInput`; a view-model test that `addTag` refuses `home, work`, `home,   words` and a tag with a line break. The existing test of a refused tag before a comma now expects the comma to stay (`home,`, then `home,wor`).

Validation:
- `cd android && ./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.NoteTagsEditorTest' --tests 'ch.sonensei.cockpit.notes.NoteEditorTagsViewModelTest'` — Pass (NoteTagsEditorTest 20, NoteEditorTagsViewModelTest 21 tests, 0 failures)

### R2-CR-2 — The tag chip still looks and is announced as clickable, but its click does nothing

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteTagsEditor.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteTagsEditorTest.kt`

Problem:
Confirmed: the tag was an `InputChip` with `onClick = {}`, so the chip body was clickable, focusable and announced as a control with no effect.

Fix:
The tag is now a private `TagChip`: a non-clickable `Surface` with the chip shape and outline (disabled colors when the note cannot be edited) holding the `#tag` label and a separate `IconButton` (test tag `note-tag-remove`, enabled with the editor) whose close icon has the content description "Remove tag ..." (`notes_tag_remove`). A new Compose test checks that the chip node has no click action and that the remove button has one, has the button role and the content description; the existing tests for removing with the button, for a tap on the chip doing nothing and for the disabled state still pass.

Validation:
- same focused command as R2-CR-1 — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.NoteTagsEditorTest' --tests 'ch.sonensei.cockpit.notes.NoteEditorTagsViewModelTest'` — Pass

Full test suite:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` — BUILD SUCCESSFUL (5294 unit tests, 0 failures, 0 errors, 0 skipped)

Lint/type-check/build:
- `ktlintCheck` and `lintDebug` in the command above — Pass

`.taskcheck`:
- Not run (left to the parent worker; only the Android part changed and its lint, unit tests and Android lint pass)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
