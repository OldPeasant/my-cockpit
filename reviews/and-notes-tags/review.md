# Code Review

Date: 2026-11-09

Task: and-notes-tags - Note editor: tag chips with suggestions

Scope: `git diff 28a7c555da91462a187b1774f4ebf824777d5445..00c7e5be0` (committed changes only; reviewed statically, Gradle was not run at the parent worker's request because another helper was running it at the same time)

Requirements:
- tasks/and-notes-tags.md acceptance criteria (1: tag chips with suggestions from existing tags, saved with the note; 2: strings en + de; 3: unit/Compose tests cover the new behavior, ktlintCheck testDebugUnitTest lintDebug and scripts/check.sh pass)
- SPEC.md section 8.1 (note has tags; filter by tag)
- Web reference: web/src/app/features/notes/editor/note-tags-input.ts and note-editor-page.html
- Server rules: server/src/main/kotlin/ch/sonensei/cockpit/notes/domain/NoteService.kt (normalizeTags, apply), notes/api/NoteDtos.kt (tags returned in case-insensitive order)
- AGENTS.md project notes, docs/QUALITY.md

Git range:
- Branch: main
- HEAD: 00c7e5be0
- Base/start: 28a7c555da91462a187b1774f4ebf824777d5445
- Commits reviewed: 1 (00c7e5be0 "and-notes-tags: tag chips with suggestions in the Android note editor")
- Uncommitted changes: none at review time

Summary: 0 P0, 2 P1, 0 P2, 3 P3

What I checked and found correct:
- Normalizing tags (trim, remove one leading `#`, trim) matches the server's `normalizeTags`. The limits (50 characters, 50 tags) and the case-insensitive duplicate check also match.
- Tags are sent only when they differ from the base note. A new note sends them when there are any. The server's create and patch DTOs both accept `tags`.
- `adoptSavedTags` reconciles the server's spelling and its case-insensitive sort order once a save completes. If the user changed tags during the save, the next autosave converges, because the server keeps the spelling of tags the note already has.
- `isSaved` includes tags. Load, `useTheirs` and the flag-change conflict path all reset `tags` from the server note. `keepMine` keeps the local tags and sends them over `current`.
- Suggestions exclude tags that are already on the note, ignoring case. They are filtered by the typed text and capped at 8. Splitting on comma or newline matches the web component.
- `notes_tag_add` and `notes_tag_remove` exist in both values/strings_notes.xml and values-de/strings_notes.xml, with matching `%1$s` placeholders.

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | The new tag behavior has no tests (criterion 3)**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt:243`, `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteTagsEditor.kt:48`, `android/app/src/main/java/ch/sonensei/cockpit/notes/NotesRepository.kt:175`
    - Category: Tests
    - Problem: The commit adds no test file. A search of `android/app/src/test` finds no reference to `addTag`, `removeTag`, `NoteTagSource`, `NoteTagsEditor`, `splitTagInput` or `matchingTags`. Nothing checks that the repository sends `tags` only when they changed, or that `adoptSavedTags` and `isSaved` behave correctly with tags.
    - Why it matters: Criterion 3 says "Unit/Compose tests cover the new behavior". As committed, it is not met. The save and adopt logic is easy to break: order-sensitive comparison, adopting the server's spelling, edits made during a save.
    - Suggested fix: Add view-model tests for:
      - add: normalized, duplicate in another case refused, known spelling adopted, length and count limits;
      - remove;
      - the draft carrying tags into `save`;
      - adopting the server's spelling and order after a save, including a tag edited during the save;
      - known tags sorted by count, and a failed tag load.

      Add repository tests for the `tags` field: absent when unchanged, present on change and on a new note with tags. Add a Compose test for chips, suggestion tap, comma and Enter commit, and remove. Add plain tests for `splitTagInput` and `matchingTags`.

- [ ] **CR-2 | NoteEditorsTest probably fails now: the strict repository mock has no stub for `tags()`**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditors.kt:80`, `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorsTest.kt:44`
    - Category: Tests
    - Problem: `NoteEditors` now builds every editor with `NoteTagSource { notes.tags() }`, and `open()` calls `loadKnownTags()`. That call runs `tagSource.tags()` in `viewModelScope`. `NoteEditorsTest` uses a strict `mockk<NotesRepository>()`. Its `setUp` stubs only `repo.get(id)`, and nothing in the file stubs `repo.tags()`. Every test that goes through `editors()` and then `open(...)` (lines 156, 178, 200, 225, 245, 285, 314, 341) should therefore throw MockKException inside a Main-dispatcher coroutine. `runTest` reports that as an uncaught exception and fails the test. `NotesRouteInAppTest` stubs `repo.tags()` at line 77 and is not affected. Tests that build `NoteEditorViewModel(repo, images)` directly use the default no-op tag source and are not affected either.
    - Why it matters: Criterion 3 requires `testDebugUnitTest` to pass. I inferred this statically and did not run it, so confirm it with the parallel Gradle run.
    - Suggested fix: Stub `coEvery { repo.tags() } returns NotesResult.Ok(emptyList())` in `NoteEditorsTest.setUp`. Do the same in any other test that builds editors through `NoteEditors` with a strict mock.

## P2 Medium

None.

## P3 Low

- [ ] **CR-3 | Tapping anywhere on a tag chip removes the tag, also in read mode**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteTagsEditor.kt:89`
    - Category: Quality
    - Problem: The `onClick` of `InputChip` is `onRemove(tag)`, so a tap anywhere on the chip removes the tag and the change autosaves. The trailing close icon only decorates and has no click handler of its own. The chips also appear in the rendered read view (`NoteEditorScreen.kt:304`), where they are enabled whenever the note is editable. The web reference removes a tag only through its dedicated remove button (`note-tags-input.ts:19-32`).
    - Why it matters: A user who taps a tag in the read view, for example expecting to filter by it, silently removes it from the note on the server.
    - Suggested fix: Put the removal on the trailing icon only, as its own clickable (`IconButton`) with the remove content description. Give the chip body either no action or a neutral one.

- [ ] **CR-4 | A tag over a limit is dropped silently and the typed text is cleared; the result of `onAdd` is never used**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteTagsEditor.kt:84`, `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt:246`
    - Category: Quality
    - Problem: `addTag` returns `false` for a tag longer than 50 characters, for the 51st tag, and for a duplicate. `commit()` (and the comma path in `onValueChange`) ignores that return value and always clears the input. A long tag therefore disappears with no chip and no message. The KDoc of `NoteTagsEditor` and `NoteEditorCallbacks.addTag` describe a "returns whether it was added" contract that no caller uses.
    - Why it matters: The user loses typed input without feedback, and the unused contract is misleading.
    - Suggested fix: Keep the text when `onAdd` returns `false` (or show a short error), or drop the Boolean from the API if it is not needed.

- [ ] **CR-5 | Known-tag loads have no request identity, so an older load can overwrite a newer one**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt:193`
    - Category: Correctness
    - Problem: `loadKnownTags()` starts a new coroutine on each `open` with a new opening and never cancels the previous one. A slower, older response that finishes last overwrites `knownTags` with stale data.
    - Why it matters: The impact is low (outdated suggestions only), but the project explicitly reviews out-of-order async results.
    - Suggested fix: Keep the `Job` and cancel it before starting a new load, as `searchLinks` does with `linkSearchJob`.

## Review conclusion

Material issues found; fix before completing the task.
