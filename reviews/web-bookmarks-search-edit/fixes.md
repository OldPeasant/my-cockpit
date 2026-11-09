# Fix Report

Date: 2026-11-09

Task: web-bookmarks-search-edit - Web bookmarks: search, edit, undo, to inbox

Scope: findings CR-2 and CR-3 of reviews/web-bookmarks-search-edit/review.md, as assigned by the parent worker (web/src/app/features/bookmarks, web/src/locale). CR-1 (tests) and CR-4 (uuidv7 import) were not assigned to this fixer.

## Fixed findings

### CR-2 — Editor save bypasses optimistic locking and can silently overwrite newer values

Files changed:
- `web/src/app/features/bookmarks/bookmarks-page.ts`
- `web/src/app/features/bookmarks/bookmarks-page.spec.ts`
- `web/src/locale/messages.xlf`
- `web/src/locale/messages.de.xlf`

Problem:
Confirmed in the code: `Draft` had no version, and `saveEdit(b)` sent the row's current bookmark to `service.update`, whose version is advanced by `refreshOne` when a change event arrives. A PATCH with stale draft values then went out with the newer `If-Match` and overwrote e.g. the fetched title and description without a conflict.

Fix:
- `Draft` stores `version`, set in `startEdit`. `saveEdit` sends `{ ...bookmark, version: draft.version }`, so the server answers 412/409 when the row changed meanwhile, and the existing conflict path runs (row error plus `reloadOne`).
- `run`/`reloadOne` take an optional callback that receives the reloaded bookmark after a conflict. `saveEdit` uses it to move the draft to the current version (the editor stays open with the user's values) and replaces the row error with a clear message: "It was changed elsewhere in the meantime. Save again to replace it with your values." The row shows the current values, so a second Save overwrites them knowingly.
- `change()` (mark read / Read later) moves the draft version along when it succeeded from the draft's version. These fields are not part of the editor, so the user's values still apply. Without this, marking an open bookmark read would cause a needless conflict on Save.
- New message id `bookmarks.edit.conflict` added by hand to messages.xlf and, with a German target (du form, as in the rest of the file), to messages.de.xlf.
- Tests: the existing conflict test now also checks the new message. New test "saves with the version the editor opened with, so a newer row is a conflict": the fetched-title change event arrives while editing, the PATCH carries If-Match "1" (the old version), a 412 keeps the editor and the fetched row values, and the retry sends If-Match "2" and closes the editor. New test "keeps the editor current after marking the bookmark read while editing".

Validation:
- `npx ng test --watch=false --include='src/app/features/bookmarks/**/*.spec.ts'` (in web/) — Pass (4 files, 121 tests)

### CR-3 — Notes textarea has no client-side length limit

Files changed:
- `web/src/app/features/bookmarks/bookmarks-page.html`
- `web/src/app/features/bookmarks/bookmarks-page.spec.ts`

Problem:
Confirmed: the notes textarea had no `maxlength`, while the server limits `description` to 2000 characters.

Fix:
Added `maxlength="2000"` to the textarea. New test "limits the notes to the 2000 characters the server accepts" checks the attribute (and the title's 500).

Validation:
- `npx ng test --watch=false --include='src/app/features/bookmarks/**/*.spec.ts'` (in web/) — Pass

## Not fixed

### CR-1 — New behavior has no unit tests in the reviewed commits

Status:
Out of scope

Reason:
The parent worker did not assign it to this fixer. The test-writer handles it.

### CR-4 — Bookmarks imports a helper from the capture feature folder

Status:
Out of scope

Reason:
The parent worker did not assign it. The review itself suggests a follow-up, because the task's touches don't include moving the helper.

## Validation summary

Focused tests:
- `npx ng test --watch=false --include='src/app/features/bookmarks/**/*.spec.ts'` — Pass, 121 tests

Full test suite:
- Not run (the parent worker runs `.taskcheck`)

Lint/type-check/build:
- `npm run lint` (in web/) — Pass, all files pass linting
- `npx prettier --check src/app/features/bookmarks` (in web/) — Pass
- `npx ng build` (in web/) — Pass, no translation errors. The only warning is the existing initial bundle budget warning. The change sits in a lazy chunk.

`.taskcheck`:
- Not run (it runs Gradle, which the parent worker told this fixer not to run)

Remaining unresolved findings:
0 of the assigned findings (CR-1 and CR-4 were not assigned, see above)

## Final status

All validated findings fixed and validation passed.

# Round 2

Date: 2026-11-09

Task: web-bookmarks-search-edit - Web bookmarks: search, edit, undo, to inbox

Scope: findings CR-1 and CR-2 of the second review (reviews/web-bookmarks-search-edit/review.md, HEAD 66b577797), plus the optional conflict-message refinement the parent worker asked for. Files: web/src/app/features/bookmarks, web/src/locale.

## Fixed findings

### CR-1 — "Show more" is hidden when no loaded bookmark matches the search

Files changed:
- `web/src/app/features/bookmarks/bookmarks-page.html`
- `web/src/app/features/bookmarks/bookmarks-page.spec.ts`

Problem:
Confirmed: the "Show more" button sat only in the branch for a non-empty `shown()` list, so with a search that matched no loaded bookmark (and also in an empty filtered view) older pages could not be loaded while `nextCursor()` was set.

Fix:
Moved the `@if (nextCursor())` button out of the non-empty branch, after the empty/list block inside the `shown()` branch. It is now rendered whenever `nextCursor()` is set. The empty messages are unchanged: an empty view still shows its empty message (now with "Show more" below it when more pages exist). The load-error and loading branches still show no button.
New page tests: "keeps "Show more" when nothing loaded matches, so older pages can be searched" (search with no match, the button is there, loading the next page shows the matching bookmark) and "keeps the empty message of an empty view and offers "Show more" with it".

Validation:
- `npx ng test --watch=false --include='src/app/features/bookmarks/**/*.spec.ts'` (in web/) — Pass

### CR-2 — A failed "To Inbox task" is reported as "could not be changed"

Files changed:
- `web/src/app/features/bookmarks/bookmarks-page.ts`
- `web/src/app/features/bookmarks/bookmarks-page.html`
- `web/src/app/features/bookmarks/bookmarks-page.spec.ts`
- `web/src/locale/messages.xlf`
- `web/src/locale/messages.de.xlf`

Problem:
Confirmed: `toInbox` ran through `run()`, whose failure path always produced the `bookmarks.changeFailed` row error.

Fix:
`rowError` got an optional `task` flag. `run()` takes an optional fourth parameter `task = false` that is stored in the row error; `toInbox` passes `true`. A new `isTaskError(b)` selects the template message: new id `bookmarks.taskFailed`, "No Inbox task could be created for “{title}”: {error}". Added by hand to messages.xlf and to messages.de.xlf with the target "Für „{title}“ konnte keine Aufgabe im Eingang erstellt werden: {error}" (Inbox = Eingang, task = Aufgabe, as elsewhere in the file). The source text was checked against `ng extract-i18n` output written to `.tmp/` (identical source and placeholders).
The existing test "retries with the same task id after a failure, and a new one after success" now expects the new message (the behavior changed as the finding requires; the assertion is not weaker).

Validation:
- `npx ng test --watch=false --include='src/app/features/bookmarks/**/*.spec.ts'` (in web/) — Pass
- `npx ng build` (in web/) — Pass, no translation errors

### Optional — Conflict message "Save again..." only while the editor is open

Files changed:
- `web/src/app/features/bookmarks/bookmarks-page.ts`
- `web/src/app/features/bookmarks/bookmarks-page.spec.ts`

Problem:
After a save conflict, `bookmarks.edit.conflict` ("Save again to replace it with your values.") replaced the row error even if the user had closed the editor meanwhile, so there was nothing to save again.

Fix:
The `saveEdit` reload callback sets the conflict message only when `draft()?.id` is still that bookmark; otherwise the generic conflict error stays. New test "does not ask to save again after a conflict when the editor was closed meanwhile".

Validation:
- `npx ng test --watch=false --include='src/app/features/bookmarks/**/*.spec.ts'` (in web/) — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `npx ng test --watch=false --include='src/app/features/bookmarks/**/*.spec.ts'` (in web/) — Pass, 4 files, 128 tests

Full test suite:
- Not run (the parent worker runs `.taskcheck`)

Lint/type-check/build:
- `npm run lint` (in web/) — Pass, all files pass linting
- `npx prettier --check src/app/features/bookmarks` (in web/) — Pass
- `npx ng build` (in web/) — Pass; only the existing initial bundle budget warning

`.taskcheck`:
- Not run (it runs Gradle, which the parent worker told this fixer not to run)

Remaining unresolved findings:
0

## Final status (Round 2)

All validated findings fixed and validation passed.
