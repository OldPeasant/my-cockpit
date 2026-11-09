# Code Review

Date: 2026-11-09

Task: web-notes-delete-tags - Web notes: delete and edit tags

Scope: committed task changes `git diff 87ce046e9..2e6eabf51` (web/src/app/features/notes, web/src/locale). Uncommitted files written in parallel by the test-writer (`note-tags-input.spec.ts`, `note-trash.spec.ts`, and any spec edits) are outside this scope.

Requirements:
- tasks/web-notes-delete-tags.md acceptance criteria and recorded assumptions (native datalist suggestions; tags saved via autosave PATCH with If-Match; editor delete saves first, then Trash, 8 s undo via Trash restore)
- SPEC.md §8.1 (notes), Trash (deleted items listed and restorable), §4.2 (optimistic locking via ETag/If-Match)
- docs/ARCHITECTURE.md and AGENTS.md project notes (feature folders, i18n English + German, lint, no secrets)

Git range:
- Branch: main
- HEAD: 2e6eabf51
- Base/start: 87ce046e96575ce6f3f6cb21f3552ade4001b4ca
- Commits reviewed: 3 (db103995a, bb9f3e16e, 2e6eabf51)

Summary: 0 P0, 1 P1, 0 P2, 3 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | The committed changes have no unit tests for the new delete and tag behavior**
    - File: `web/src/app/features/notes/editor/note-editor-page.spec.ts:1369`
    - Category: Tests
    - Problem: The only committed spec change adjusts an existing `expectNone` so it ignores the new `GET /notes/tags` call. Nothing committed tests `NoteEditorPage.remove()` (flush first, DELETE with the saved version's If-Match, navigate to /notes, undo restores and reopens, failure banner), `onTags()` (PATCH sends `tags` only when they changed, server-normalized tags taken over, disabled while in conflict), `NotesPage.remove()` (card removed, 404 and 412 handling, undo reloads), `NoteTrash` or `NoteTagsInput` (Enter or comma adds, case-insensitive dedupe, leading `#` stripped, Backspace removes the last chip, suggestions leave out tags the note already has).
    - Why it matters: Acceptance criterion 4 ("Unit tests cover the new behavior") is not met by the committed code. The editor changes also touch the autosave path (`changes`, `sameDraft`, `persist`), which breaks easily.
    - Suggested fix: Commit the test-writer's specs. Make sure they cover the editor's `remove()` and `onTags()` paths and `NotesPage.remove()`, not only the two new standalone classes, then re-run `npm run check`.

## P2 Medium

None.

## P3 Low

- [ ] **CR-2 | The doc comment for onToggle now sits above onTags**
    - File: `web/src/app/features/notes/editor/note-editor-page.ts:249`
    - Category: Quality
    - Problem: The new code went in between the existing `/** A checkbox in the preview: the change is saved right away. */` and `onToggle`. Two JSDoc blocks now stand back to back above `onTags`, and `onToggle` (line 283) has no comment.
    - Why it matters: The comment is misleading and tools read only the last block, so the wrong text ends up documenting the method.
    - Suggested fix: Move the checkbox comment back above `onToggle`.

- [ ] **CR-3 | Deleting from the editor can drop unsaved edits that undo cannot bring back**
    - File: `web/src/app/features/notes/editor/note-editor-page.ts:261`
    - Category: Correctness
    - Problem: `remove()` calls `flushAll()`, and that stops quietly when the save state is `error`. The DELETE then goes ahead with the last saved version, and `deleted = true` stops every later flush. The title input and the Markdown editor also stay editable while `deleting()` is true (only the tags input is disabled, see `note-editor-page.html:137`). Anything typed while the DELETE runs is never sent, and a debounced PATCH can race the DELETE. Undo restores the note as it was last saved, without those edits. Elsewhere the editor keeps unsaved edits on purpose: `open()` stays on the note when a save failed.
    - Why it matters: In a rare case the user loses typed text. The undo snackbar suggests the delete can be fully reversed, but these edits do not come back.
    - Suggested fix: Abort the delete (keep the error banner) when `flushAll()` ends in `error` with `dirty()` still true. Also disable or ignore title and body input while `deleting()` is true, or cancel the debounce timer before sending the DELETE.

- [ ] **CR-4 | Tag suggestions are not refreshed after a new tag is added in the editor**
    - File: `web/src/app/features/notes/editor/note-editor-page.ts:143`
    - Category: Correctness
    - Problem: `loadKnownTags()` runs only once, in the constructor. Unlike the editor, `NotesPage` reloads its tags after create and delete. If you open another note through a `[[` chip (same component instance, only the route changes), a tag you just added to the first note does not appear in the suggestions.
    - Why it matters: Minor UX inconsistency. The suggestions go stale within one editor session.
    - Suggested fix: Call `loadKnownTags()` again after a successful save that changed the tags, or in `open()`.

## Notes (validated, no finding)

- Optimistic locking: the editor DELETE uses the version that comes back from the last flush. A note changed elsewhere (conflict state) sends the old version, gets 412 and shows "could not be moved to the Trash". The card delete reloads the list on 409 or 412 and drops the note on 404. Tag PATCHes go through the existing `If-Match` save path. This matches SPEC.md §4.2.
- Autosave concurrency: `onTags` goes through `schedule()` and `flush()`, which waits for a save already running. `changes()` sends `tags` only when they differ, so body-only saves leave the server's tags alone. `persist` takes over server-normalized tags only when they were not changed in the meantime.
- i18n: all 8 new ids (`notes.editor.delete`, `notes.editor.deleteFailed`, `notes.editor.addTag`, `notes.editor.removeTag`, `notes.deleteAria`, `notes.trashed`, `notes.undo`, `notes.restoreFailed`) are in `messages.xlf` and have German targets in `messages.de.xlf`. `notes.noteTags` already existed. The other changes in `messages.xlf` only reorder existing bookmarks units (extract output) and change no text.
- Accessibility: the chip remove buttons and the card delete button have localized `aria-label` and `title` that name the tag or note. The SVGs are `aria-hidden`. The chip list has a label, and the tag input has a label and placeholder. MatSnackBar announces the undo message.
- Security: no secrets, no unsafe HTML. Tags are rendered through interpolation.

## Review conclusion

Material issues found; fix before completing the task. CR-1 is resolved once the test-writer's specs covering the editor and overview delete and tag paths are committed and pass. CR-2 to CR-4 are small and optional.
