# Code Review

Date: 2026-11-09

Task: and-notes-tags - Note editor: tag chips with suggestions

Scope: this is a re-review of the final code. It covers the files changed by task commits b056900b3, 8dc50f014 and c298915fe (base b6e7dc2da), read at HEAD ae6047d03. Main has been merged since then, and those merges were not reviewed. Only committed changes were reviewed. The review was static: Gradle was not run, at the parent worker's request, because the test-writer was running it at the same time.

Requirements:
- tasks/and-notes-tags.md: acceptance criteria 1-3 and the two recorded assumptions (where the editor shows, the suggestion rules, sending tags only when they changed)
- SPEC.md section 8.1 (notes have tags)
- Server rules in server/src/main/kotlin/ch/sonensei/cockpit/notes/domain/NoteService.kt (`normalizeTags`: trim, one leading `#` removed, at most 50 characters and 50 tags, no NUL; commas are allowed)
- The web reference (note-tags-input: splits the input on commas, removes a tag only with a dedicated button)
- The previous review and the fix report: reviews/and-notes-tags/review.md (previous revision), reviews/and-notes-tags/fixes.md
- Test evidence: reviews/and-notes-tags/tests.md, fixes.md
- AGENTS.md project notes, docs/QUALITY.md

Git range:
- Branch: main
- HEAD: ae6047d03ef0cdc92f317150ab5352492b245234
- Base/start: b6e7dc2da
- Commits reviewed: 3 (b056900b3 implementation, 8dc50f014 tests and the first review, c298915fe fixes for CR-3 to CR-5)
- Uncommitted changes: android/app/src/test/.../NoteTagsEditorTest.kt is being changed by the test-writer that is running now. Those changes were not reviewed.

Summary: 0 P0, 0 P1, 1 P2, 1 P3

Previous findings, checked against the current code:
- CR-1 (no tests) is resolved. 8dc50f014 adds the following test classes. tests.md reports them passing, and fixes.md reports the full `testDebugUnitTest` passing after the fixes. tests.md reports 123 of 123 changed executable lines covered, measured at the earlier revision.
  - NoteEditorTagsViewModelTest
  - NoteTagsEditorTest
  - tag cases in NotesRepositoryTest
  - the stub in NoteEditorsTest
- CR-2 (the strict mock had no `tags()` stub) is resolved. `NoteEditorsTest.setUp` now stubs `repo.tags()`.
- CR-3 (a tap on the chip removed the tag) is resolved. The chip's `onClick` is a no-op. Removing is a separate clickable on the trailing icon, which has a content description and is enabled only when the chip is (NoteTagsEditor.kt:94-108).
- CR-4 (a refused tag was cleared silently) is fixed for Enter and for losing focus: `commit()` at NoteTagsEditor.kt:84-86. The comma path introduces a new defect, reported as CR-1 below.
- CR-5 (overlapping loads of the tags in use) is resolved. `knownTagsJob` is cancelled before each new load (NoteEditorViewModel.kt:197-206), and a test covers it.

Checked and found correct:
- Tags are reset from the server note on load, on `useTheirs` and on the flag-change conflict path. `keepMine` keeps the local tags.
- `isSaved` now includes the tags. `adoptSavedTags` takes the server's spelling and order only when the tags did not change during the save, on both the save path and the create path.
- The repository sends `tags` only when they differ from the base note, and for a new note only when it has any.
- Normalizing a tag (trim, one leading `#` removed) and the 50-character and 50-tag limits match the server.
- The strings `notes_tag_add` and `notes_tag_remove` exist in both values/ and values-de/, with matching `%1$s` placeholders.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | The comma path can save a refused tag joined with the next one, or a tag that contains a comma**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteTagsEditor.kt:114-117`
    - Category: Correctness
    - Problem: When a part typed before a comma is refused (for example a duplicate of a tag already on the note), `onValueChange` puts it back as `(refused + rest).filter { it.isNotEmpty() }.joinToString(", ")`. That breaks the input in two ways:
      1. **Typing one character at a time.** Typing `home,` gives `rest = ""`, so the comma is dropped and the input reads `home`. The test at NoteTagsEditorTest.kt:116-118 confirms this. The next characters the user types join the refused text: `home` followed by `work` becomes `homework`. Enter or losing focus then adds `homework` as a single tag.
      2. **Pasting, or text after the comma.** The input reads `home, wor` (the state the test reaches at NoteTagsEditorTest.kt:123). On each further keystroke, `rest` keeps its leading space and the join adds `", "` again, so the spaces grow: `home,  word`, then `home,   words`, and so on. Enter then calls `onAdd("home,   words")`. `normalizeTag` trims only the ends, so the value is not a duplicate and is at most 50 characters. `addTag` accepts it, and the server accepts commas, so a tag containing a comma is saved.
    - Why it matters: Following CR-4 of the previous review, ordinary typing now creates wrong tags such as `homework` or `home,   words` and autosaves them to the server. The web client cannot create tags like these, because it splits on commas.
    - Suggested fix: Keep refused parts out of the text the next tag is typed into. One option is to stop at the first refused part: keep it with its trailing comma or separator and stop splitting, then add parts again only on the next commit. Another is to keep the refused text as it was typed, without trimming it, re-joining it or retrying it on every keystroke. In addition, refuse `,` and newline in `addTag`, or split the text in `commit()` the same way as in `onValueChange`, so that a tag containing a comma can never be added. Add a Compose test that types a refused tag, a comma and a second tag one character at a time and then presses Enter, and checks the tags that were added.

## P3 Low

- [ ] **CR-2 | The tag chip still looks and is announced as clickable, but its click does nothing**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteTagsEditor.kt:93-95`
    - Category: Quality
    - Problem: The fix for CR-3 kept an `InputChip` with `onClick = {}`. An `InputChip` is always clickable, so the body still shows a ripple and gets focus. Accessibility services also announce it as an actionable control, but activating it does nothing.
    - Why it matters: Users of screen readers and keyboards meet a control with no effect, and touch users see feedback for a tap that does nothing.
    - Suggested fix: Show the tag with a non-clickable chip-styled container, for example a `Surface` with the chip shape, the label and the remove `IconButton`. Alternatively, mark the chip body as having no click action for accessibility.

## Review conclusion

Material issues found; fix before completing the task.
