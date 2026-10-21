# Fix Report

Date: 2026-10-21

Task: web-notes-editor - Notes: Markdown editor with images

Scope: findings CR-1 (= TW-1), CR-2 and CR-3 from `reviews/web-notes-editor/review.md` and `reviews/web-notes-editor/tests.md`; base `868c962c9f0210a8a92ca00123d4b123afd0711e`, reviewed HEAD `6eaaa082`; changes limited to `web/src/app/features/notes/editor` as instructed by the parent worker.

## Fixed findings

### CR-1 / TW-1 — Leaving the editor during an image upload saved the "Uploading" placeholder

Files changed:
- `web/src/app/features/notes/editor/markdown-editor.ts`
- `web/src/app/features/notes/editor/note-editor-page.ts`
- `web/src/app/features/notes/editor/markdown-editor.spec.ts`
- `web/src/app/features/notes/editor/note-editor-page.spec.ts`

Problem:
The placeholder `![Uploading name…](uploading:N)` was part of the body, so autosave and the flush on leaving saved it. When the upload ended after the editor was destroyed, `replaceMarker` wrote into the destroyed `EditorView` (the field was never cleared), no change was reported and the note kept the placeholder for good.

Fix:
- Placeholders are never saved: the page builds its draft from `withoutUploadPlaceholders(body)` (new exported helper in `markdown-editor.ts`), so neither an intermediate autosave nor the flush on leaving contains one.
- The editor now passes the placeholder to `uploadImage(file, placeholder)`. The page tracks pending uploads by placeholder. When leaving the note (`leave()`, used on page destroy and before opening another note) it saves pending edits, waits for the pending uploads, puts each result where its placeholder still stands (failed uploads remove it) and saves again. Before opening another note the editor is still alive and applies the result itself; after the page is destroyed the page applies it to its last body.
- The editor clears `this.view` on destroy and no longer reports a failed upload after it is gone (avoids writing into a destroyed view and emitting on a destroyed output).
- `beforeunload` also asks while an image is still uploading.
- The committed TW-1 test is kept unchanged and passes. The existing test `uploads pasted images to the note` now passes the placeholder argument (call signature only, behaviour unchanged).
- Added tests: no placeholder in an intermediate autosave and the image saved after the upload; upload finished and saved before opening another note; leaving during an upload that then fails saves no placeholder; `withoutUploadPlaceholders`; the editor passes the placeholder and ignores uploads that end after it is destroyed.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'` — Pass (5 files, 88 tests)

### CR-2 — Conflict actions ignored a note switch made while they ran

Files changed:
- `web/src/app/features/notes/editor/note-editor-page.ts`
- `web/src/app/features/notes/editor/note-editor-page.spec.ts`

Problem:
`useTheirs()` and `keepMine()` applied their GET result even when another note had been opened meanwhile; `keepMine` could save the other note's text over the first note.

Fix:
Both capture `loadSeq` before the GET and drop the result (and any error message) when a load happened meanwhile, the same pattern as `load()`. Tests added for "Save my version" (no PATCH, other note shown), "Discard my edits" (other note shown, edits go to the other note with its version) and a failed conflict action (no error banner on the other note).

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'` — Pass

### CR-3 — Leaving after a failed save discarded the edits

Files changed:
- `web/src/app/features/notes/editor/note-editor-page.ts`
- `web/src/app/features/notes/editor/note-editor-page.spec.ts`

Problem:
The destroy hook flushed only while a debounce timer was pending, so after a failed (non-conflict) save leaving the page dropped the edits.

Fix:
The destroy hook now always calls `leave()`, which flushes unconditionally; `flush()` saves the pending draft also in the `error` state and does nothing without edits. The conflict case stays as it is (the parent records it as an assumption). Test added: `retries a failed save when leaving the page`.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'` — Pass (5 files, 88 tests, including TW-1)
- `cd web && npx prettier --check src/app/features/notes/editor/` — Pass

Full test suite:
- `cd web && npm run check` (api:check, lint, e2e:typecheck, test:ci, build) — Pass (88 files, 1601 tests; build completes with the pre-existing initial bundle budget warning)

Lint/type-check/build:
- Included in `cd web && npm run check` — Pass

`.taskcheck`:
- Not run (the parent worker runs `sh scripts/check.sh`; the server and Android parts are unchanged)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.

## TW-2 fix (second fix cycle)

Date: 2026-10-21

Scope: finding TW-2 from `reviews/web-notes-editor/tests.md` (re-test on `0001772c`); changes limited to `web/src/app/features/notes/editor` as instructed by the parent worker.

### TW-2 — Opening a note ignored a route change back to the previous note while leaving it was still running

Files changed:
- `web/src/app/features/notes/editor/note-editor-page.ts`

Problem:
`open(id)` set `this.id` only after `await this.leave()`. When the route went from note A to B (leaving A waits for an upload) and back to A before that ended, `open(A)` returned early because `id()` was still A, and the earlier `open(B)` then loaded B while the address showed `/notes/A`.

Fix:
The page records the id the route asks for last (`requested`) at the start of `open()`, before awaiting `leave()`. After `leave()`, an `open()` continues only if its id is still the requested one, so the latest route wins. Going back to the note still shown cancels the pending switch: the note is not reloaded, and the edits and the finished upload stay in the editor and are saved by `leave()`. The committed TW-2 test is unchanged and passes. No test was changed.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'` — Pass (5 files, 93 tests, including TW-2)
- `cd web && npm run check` (api:check, lint, e2e:typecheck, test:ci, build) — Pass (build shows only the existing initial bundle budget warning)

### Validation summary (TW-2 cycle)

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'` — Pass (93 tests)

Full test suite:
- `cd web && npm run check` — Pass

Lint/type-check/build:
- Included in `cd web && npm run check` — Pass

`.taskcheck`:
- Not run (the parent worker runs `sh scripts/check.sh`; the server and Android parts are unchanged)

Remaining unresolved findings:
0

### Final status (TW-2 cycle)

All validated findings fixed and validation passed.
