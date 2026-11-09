# Fix Report

Date: 2026-11-09

Task: web-notes-delete-tags - Web notes: delete and edit tags

Scope: current task; findings CR-1 to CR-4 of reviews/web-notes-delete-tags/review.md, approved by the parent worker.

## Fixed findings

### CR-1 - The committed changes have no unit tests for the new delete and tag behavior

Files changed:
- none (resolved by the test-writer specs committed in 058b62e69)

Problem:
At review time the committed changes had no unit tests for remove(), onTags(), NotesPage.remove(), NoteTrash or NoteTagsInput.

Fix:
No code change. The test-writer's specs, now committed, cover the editor delete and tag paths ("tags" and "delete" blocks in note-editor-page.spec.ts), the overview delete, NoteTrash and NoteTagsInput. Checked that they pass. This fix round also adds four tests (see CR-3 and CR-4).

Validation:
- `npx ng test --watch=false --include='src/app/features/notes/**/*.spec.ts' --include='src/locale/**/*.spec.ts'` - Pass (12 files, 268 tests)

### CR-2 - The doc comment for onToggle now sits above onTags

Files changed:
- `web/src/app/features/notes/editor/note-editor-page.ts`

Problem:
The checkbox comment sat above onTags, right before onTags' own comment. onToggle had no comment.

Fix:
Moved the checkbox comment back above onToggle.

Validation:
- `npm run lint` - Pass

### CR-3 - Deleting from the editor can drop unsaved edits that undo cannot bring back

Files changed:
- `web/src/app/features/notes/editor/note-editor-page.ts`
- `web/src/app/features/notes/editor/note-editor-page.html`
- `web/src/app/features/notes/editor/note-editor-page.spec.ts`
- `web/src/locale/messages.xlf`, `web/src/locale/messages.de.xlf`

Problem:
remove() went ahead with the DELETE even when flushAll() ended with edits still unsaved (save error or conflict). The title stayed editable while the delete ran, so text typed during the DELETE was lost.

Fix:
After flushAll(), remove() now stops when dirty() is true or the save state is 'error' or 'conflict'. In that case it sends no DELETE and shows the new localized banner notes.editor.deleteUnsaved ("Save or discard your edits before deleting the note."). The title input is now disabled while deleting() is true. The Markdown editor stays editable, as the parent worker approved. The German target was added: "Speichere oder verwirf deine Änderungen, bevor du die Notiz löschst." It was inserted only for this unit. The extract output in messages.xlf changed only by the new unit and line numbers.
New tests: "does not delete when the edits could not be saved" (a PATCH answered 503 leads to no DELETE, the banner, no navigation and no undo; Delete is enabled again and the edit is kept) and "disables the title while the delete runs".

Validation:
- `npx ng test --watch=false --include='src/app/features/notes/editor/note-editor-page.spec.ts'` - Pass (96 tests)
- `npx ng test --watch=false --include='src/app/features/notes/**/*.spec.ts' --include='src/locale/**/*.spec.ts'` - Pass (268 tests, including the German locale spec)

### CR-4 - Tag suggestions are not refreshed after a new tag is added in the editor

Files changed:
- `web/src/app/features/notes/editor/note-editor-page.ts`
- `web/src/app/features/notes/editor/note-editor-page.spec.ts`

Problem:
The known tags were loaded only once, in the constructor. They went stale when another note was opened in the same component instance.

Fix:
open() now calls loadKnownTags() each time another note is opened. The first route emission covers the initial load, so the constructor call was removed and the first open still sends exactly one GET /notes/tags. After a successful save that sent tags, the saved tags not yet known (compared case-insensitively) are added to knownTags. The existing tests that count GET /notes/tags requests needed no changes. New test: "suggests a tag added here on the next note opened". It checks that the added tag is suggested right away and that the tags are loaded again for the other note.

Validation:
- `npx ng test --watch=false --include='src/app/features/notes/**/*.spec.ts' --include='src/locale/**/*.spec.ts'` - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `npx ng test --watch=false --include='src/app/features/notes/editor/note-editor-page.spec.ts'` - Pass (96 tests)
- `npx ng test --watch=false --include='src/app/features/notes/**/*.spec.ts' --include='src/locale/**/*.spec.ts'` - Pass (12 files, 268 tests)

Full test suite:
- Not run by the fixer (the parent worker runs `.taskcheck`)

Lint/type-check/build:
- `npx prettier --write src/app/features/notes` - done
- `npm run lint` - Pass (all files pass linting)
- The ng test build compiled the changed sources without errors

`.taskcheck`:
- Not run (it includes Gradle, which the parent worker asked the fixer not to run)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
