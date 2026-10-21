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

## CR-1 / CR-2 fix (third fix cycle)

Date: 2026-10-21

Scope: findings CR-1 (P2) and CR-2 (P3) from `reviews/web-notes-editor/review.md` (review of HEAD `48747768`, base `7ce21cc0`); changes limited to `web/src/app/features/notes/editor` as instructed by the parent worker.

### CR-1 — Switching to another note after a save keeps failing drops the edits without any notice

Files changed:
- `web/src/app/features/notes/editor/note-editor-page.ts`
- `web/src/app/features/notes/editor/note-editor-page.spec.ts`

Problem:
`open()` always went on to the new note after `leave()`. If the retry on leaving failed too, `show()` replaced the unsaved text of the first note and cleared the error banner, so the edits were lost silently.

Fix:
After `leave()`, if the page is in the `error` save state and still has unsaved edits, `open()` does not load the other note. It keeps the draft and the error banner with its retry, and puts the address back with `router.navigate(['/notes', id], { replaceUrl: true })`. The resulting route change calls `open()` for the note already shown, which returns early. The conflict case is unchanged: leaving in a conflict still drops the edits, as the recorded assumption says. Leaving the page (destroy) is unchanged. Test added: `stays on the note with its edits when the retry before opening another note fails too`. The other note is not requested, the text and the error banner with its retry stay, and the address goes back to the first note. A later successful retry then saves the first note with `If-Match "1"`.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'` — Pass (97 tests). The new test fails on the code before the fix.

### CR-2 — `open()` goes on loading the next note after the page was destroyed while leaving waited

Files changed:
- `web/src/app/features/notes/editor/note-editor-page.ts`
- `web/src/app/features/notes/editor/note-editor-page.spec.ts`

Problem:
After `await this.leave()`, `open()` checked only that its id was still the last one requested. It did not check whether the page had been destroyed, and with an id instead of a sequence, A → B → A → B let both pending `open(B)` calls continue.

Fix:
`requested` is replaced by a counter, `openSeq`. Every `open()` call increments it, so after `leave()` only the latest call continues. `open()` also returns when `destroyed` is set. A → B → A behaves as before: `open(A)` while A is shown still increments the counter and returns, which cancels the pending switch. Test added: `does not open the other note when the page is left while leaving the first one waits`. The route goes A → B while leaving waits for an upload, then to another page. The image is still saved into A once, and no GET for B and no links request is sent.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'` — Pass. The new test fails on the code before the fix.

### Not fixed (third cycle)

None.

### Validation summary (third cycle)

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'` — Pass (5 files, 97 tests, including TW-2 and the existing switch/leave tests)
- `cd web && npx eslint src/app/features/notes/editor --max-warnings=0` — Pass
- `cd web && npx prettier --check src/app/features/notes/editor/` — Pass

Full test suite:
- `cd web && npm run check` (api:check, lint, e2e:typecheck, test:ci, build) — Pass (88 files, 1610 tests; build completes)

Lint/type-check/build:
- Included in `cd web && npm run check` — Pass

`.taskcheck`:
- Not run (the parent worker runs `sh scripts/check.sh`; the server and Android parts are unchanged)

Remaining unresolved findings:
0

### Final status (third cycle)

All validated findings fixed and validation passed.

## CR-1 fix (fourth fix cycle)

Date: 2026-10-21

Scope: findings CR-1 (P3) and CR-2 (P3) from `reviews/web-notes-editor/review.md` (review of HEAD `966e827e`, base `993bc56e`); only CR-1 fixed, as instructed by the parent worker. Changes limited to `web/src/app/features/notes/editor`.

### CR-1 — Text typed during the last save before an in-app note switch is dropped

Files changed:
- `web/src/app/features/notes/editor/note-editor-page.ts`
- `web/src/app/features/notes/editor/note-editor-page.spec.ts`

Problem:
Confirmed in the code. When the user typed while the final `flush()` of `leave()` waited for the server, `persist()` saw the new edits and only called `schedule()` (state `pending`). `open()` then went on to the other note, `show()` cleared the timer and replaced the text, and the edits were never sent.

Fix:
`leave()` now calls a new `flushAll()` (both before and after waiting for uploads). It runs `flush()` again while `dirty()` holds and the state is neither `conflict` nor `error`. The loop is bounded: each extra round sends the current draft, and a round with nothing left to send ends it. `flush()` clears the debounce timer, and the last round only ends in `saved`, `error` or `conflict`, which never schedule an autosave. So no autosave timer survives to fire for the next note. Because the loop is in `leave()`, it covers both the note switch and page destroy. The existing behaviours are unchanged: the `error` state still stops the loop, so `open()` stays on the note with its draft and puts the address back, and a conflict still discards the edits. Tests added:
- `saves edits typed during the save on leaving before opening another note`: a second PATCH (`If-Match "2"`, the new text) goes out before the other note's GET, and no PATCH follows after the debounce. This test fails on the code before the fix.
- `saves edits typed during the save on leaving when the page is left meanwhile`: the destroy case. The edits are saved with `If-Match "2"`, and the other note is never requested. This test already passed before the fix, through the `destroyed` branch in `persist()`, so it is kept as a regression guard.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'` — Pass (5 files, 103 tests)

### Not fixed (fourth cycle)

### CR-2 — Putting the address back after a failed leave leaves a duplicate history entry

Status:
Requires decision (accepted)

Reason:
Not fixed by instruction of the parent worker: the duplicate history entry is accepted as a cost of the chosen design and recorded by the parent as an assumption (`tasks.py assume`).

### Validation summary (fourth cycle)

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'` — Pass (5 files, 103 tests)
- `cd web && npx eslint src/app/features/notes/editor --max-warnings=0` — Pass
- `cd web && npx prettier --check src/app/features/notes/editor/` — Pass

Full test suite:
- Included in `.taskcheck` below (lint and unit tests of all parts) — Pass

Lint/type-check/build:
- Included in `.taskcheck` — Pass

`.taskcheck`:
- `sh scripts/check.sh` — Pass (exit 0)

Remaining unresolved findings:
1 (CR-2, accepted as an assumption)

### Final status (fourth cycle)

All validated findings fixed and validation passed. CR-2 was left as an accepted assumption, as instructed.
