# Code Review

Date: 2026-11-09

Task: web-notes-delete-tags - Web notes: delete and edit tags

Scope: second review of the final revision. The full task diff is `git diff 87ce046e9..556c110c3`. The focus is the fix diff `git diff 058b62e69..556c110c3 -- web` and whether the first review's findings CR-1 to CR-4 are resolved. Committed changes only. The working tree was clean.

Requirements:
- tasks/web-notes-delete-tags.md acceptance criteria and recorded assumptions
- SPEC.md §8.1 (notes), Trash, §4.2 (optimistic locking via ETag/If-Match)
- docs/ARCHITECTURE.md and AGENTS.md project notes (feature folders, i18n English and German, lint)
- First review (earlier version of this file) and reviews/web-notes-delete-tags/fixes.md

Git range:
- Branch: main
- HEAD: 556c110c3
- Base/start: 87ce046e9
- Commits reviewed: 5 (db103995a, bb9f3e16e, 2e6eabf51, 058b62e69, 556c110c3)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Status of the first review's findings

- CR-1 (no unit tests for the new behavior): resolved. Commit 058b62e69 adds specs for the editor's tag and delete paths, the overview delete, NoteTrash and NoteTagsInput. Commit 556c110c3 adds three more tests: "does not delete when the edits could not be saved", "disables the title while the delete runs" and "suggests a tag added here on the next note opened". I re-ran `npx ng test --watch=false --include='src/app/features/notes/editor/note-editor-page.spec.ts'` and all 96 tests passed.
- CR-2 (misplaced doc comment): resolved. The checkbox comment now sits above `onToggle`, and `onTags` has only its own comment (`note-editor-page.ts:250`, `:290`).
- CR-3 (delete could drop unsaved edits): resolved. After `flushAll()`, `remove()` stops when `dirty()` is true or the save state is `error` or `conflict` (`note-editor-page.ts:266-272`). No DELETE is sent, and `finally` resets `deleting`. The title input is disabled while the delete runs (`note-editor-page.html:129`). The parent worker approved leaving the Markdown editor editable. Text typed into it during the DELETE is still not saved after a successful delete. That is a narrow window, the decision was deliberate, and I am not raising it again. Both new ids have English sources, and the German target is in `messages.de.xlf`. The other changes in `messages.xlf` only update line numbers.
- CR-4 (stale tag suggestions): resolved. `open()` now calls `loadKnownTags()` for every note it opens (`note-editor-page.ts:182`), and the constructor call was removed, so the first open still sends exactly one GET. After a save that sent tags, `persist` merges the saved tags into the suggestions without case duplicates (`addKnownTags`, `note-editor-page.ts:459-464`).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-5 | The delete guard replaces the save error message, so the user loses the reason the save failed**
    - File: `web/src/app/features/notes/editor/note-editor-page.ts:268`
    - Category: Correctness
    - Problem: When the save state is `error`, `persist` has already put the server's reason into `saveError` (line 435, for example a validation detail or "Service Unavailable"). The new guard in `remove()` overwrites that message with "Save or discard your edits before deleting the note." The banner keeps its "Try again" button, but it no longer says why the save failed. If the failure is permanent, such as a 400 validation error, the user cannot tell what to fix.
    - Why it matters: Minor UX regression, and only after a failed save followed by a delete attempt. No data is lost.
    - Suggested fix: Set the deleteUnsaved message only when `saveError()` is empty (the conflict or dirty-only cases), or show it together with the existing message instead of replacing it.

## Notes (validated, no finding)

- `loadKnownTags()` has no request sequence. If notes are switched quickly, an older tags response can overwrite a newer one. Both responses hold the same global tag list and affect only the datalist suggestions, so the impact is negligible.
- If the user clicks Delete while the note is in conflict, the deleteUnsaved banner appears next to the conflict banner. The conflict actions still work, and `show()` or a successful save clears the extra message.
- Optimistic locking, autosave concurrency, i18n, accessibility and security, as noted in the first review: the fix diff does not change these findings.

## Review conclusion

No material issues found. CR-1 to CR-4 are resolved. CR-5 is optional and low priority.
