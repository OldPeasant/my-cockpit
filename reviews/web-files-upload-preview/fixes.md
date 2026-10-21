# Fix Report

Date: 2026-10-21

Task: web-files-upload-preview - Files: upload and preview

Scope: findings CR-1..CR-6 of `reviews/web-files-upload-preview/review.md`, plus the new component style budget warning for `files-page.scss` found by the parent worker. Allowed files: web/src/app/features/files/upload, files/preview, files/browser/files-page.*, capture/capture-dialog.*. Task base 92738536. Commit eeb07812 ("review fixes in progress") already contained the fixes. This pass checked each one against the code and confirmed that its specs exist and pass.

## Fixed findings

### CR-1 - Capture dialog spec asserted the old placeholder; tests for criterion 4 were missing (P1)

Status: already fixed in eeb07812 and the test-writer commits 25d0b6a5 and fb417ca4. Verified in this pass.

Files changed (in those commits):
- `web/src/app/features/capture/capture-dialog.spec.ts`
- `web/src/app/features/files/upload/upload.service.spec.ts`, `upload-panel.spec.ts`, `sha256.spec.ts`
- `web/src/app/features/files/preview/preview-kind.spec.ts`, `pdf-viewer.spec.ts`, `preview-dialog.spec.ts`, `file-thumb.spec.ts`

Problem:
The spec still expected the "can't be saved yet" note and no `fileIds`. There were no service tests for chunk retry, resume or cancel, and no table tests for preview selection by mime type.

Fix:
`.files-note` and "can't be saved yet" no longer appear anywhere in `capture/`. The capture specs now flush the upload requests, check that Save stays disabled while files upload, check that `fileIds` are sent, and check that Remove cancels an upload. `upload.service.spec.ts` covers chunk retry, retry exhaustion, resume, and cancel during a request and during a retry pause. `preview-kind.spec.ts` covers preview selection by mime type.

Validation:
- `npm --prefix web run check` - Pass (90 files, 1663 tests)

### CR-2 - Responsive layout of the Files page was deleted (P2)

Status: already fixed in eeb07812. Verified in this pass.

Files changed:
- `web/src/app/features/files/browser/files-page.scss`

Problem:
The `@media (max-width: 720px)` block had been replaced by the new `button.open` rule.

Fix:
The media query is back, identical to the base version: one-column `.files`, `.side { order: 2 }` and `.meta .date` hidden. `git diff 92738536 HEAD` no longer shows the block as changed.

Validation:
- `git diff 92738536 -- web/src/app/features/files/browser/files-page.scss` - media query unchanged from base
- `npm --prefix web run check` - Pass

### CR-3 - PDF page changes could render two pages on the same canvas (P2)

Status: already fixed in eeb07812. Verified in this pass.

Files changed:
- `web/src/app/features/files/preview/pdf-viewer.ts`
- `web/src/app/features/files/preview/pdf-viewer.spec.ts`

Problem:
`render()` stored the render task only after `getPage` resolved, so two quick page changes could start two concurrent `page.render` calls on one canvas. pdf.js then threw an error, and the viewer showed its error state.

Fix:
`render()` takes a sequence number before it awaits anything. It returns early after `getPage` if a newer request has started, and again after awaiting the previous render's settled promise. Only the latest request sets `rendered` or `error`. `close()` bumps the sequence. The fake pdf.js in the spec now throws on a second concurrent render on the canvas, as real pdf.js does. Two tests cover the fix: "draws only the latest page when pages change while one is still loading" (pages resolve out of order) and "a stale render that fails does not show an error".

Validation:
- `npm --prefix web run check` (pdf-viewer.spec.ts) - Pass

### CR-4 - Capture was saved silently without attachments whose upload failed (P2)

Status: already fixed in eeb07812. Verified in this pass.

Files changed:
- `web/src/app/features/capture/capture-dialog.ts`, `capture-dialog.html`, `capture-dialog.scss`, `capture-dialog.spec.ts`

Problem:
Save, Enter and Shift+Enter were allowed while an attachment had failed, and the failed file was dropped without notice.

Fix:
A new `failedFiles()` computed blocks `submit()` and disables Save. A message ("Retry or remove the failed attachment to save.", `#capture-files-failed`) is shown and linked to Save with `aria-describedby`. Tests: "a failed attachment blocks saving, with the reason, until it is removed" checks Enter, Shift+Enter and `submit()`, that no capture request is sent, and that the failed job stays in the list. "a retry of a failed attachment lifts the block once it is attached" covers the retry path.

Validation:
- `npm --prefix web run check` (capture-dialog.spec.ts) - Pass

### CR-5 - Cancelling during upload creation or completion left untracked server state (P3)

Status: already fixed in eeb07812. Verified in this pass.

Files changed:
- `web/src/app/features/files/upload/upload.service.ts`
- `web/src/app/features/files/upload/upload.service.spec.ts`

Problem:
`cancel()` aborted POST /uploads and POST /uploads/{id}/complete. The server could already have created the upload or stored the file, and the client lost track of it.

Fix:
`send()` takes an `abortable` flag, and create and complete pass `false`. If the job was cancelled while the create request ran, the new upload is DELETEd as soon as the response arrives. While completing, `job.completing` stops `cancel()` from sending a DELETE. If the completion succeeds, the file counts as uploaded: the job becomes done and `uploaded` is emitted, which refreshes the listing. If it fails after a cancel, the upload is DELETEd. Four tests cover this: cancel during create, then success or failure; cancel during complete, then success or failure.

Validation:
- `npm --prefix web run check` (upload.service.spec.ts) - Pass

### CR-6 - Per-file live regions announced every progress tick (P3)

Status: already fixed in eeb07812. Verified in this pass.

Files changed:
- `web/src/app/features/files/upload/upload-panel.html`, `upload-panel.spec.ts`
- `web/src/app/features/capture/capture-dialog.html`, `capture-dialog.spec.ts`

Problem:
The `role="status"` element also contained the byte counter or percentage, so screen readers announced every progress update.

Fix:
The `role="status"` element now holds only the state text (Waiting, Uploaded, Failed, Cancelled, Attached). The bytes and percentage sit outside it, and the progress bar still exposes the value. Both specs check that the live region is empty while uploading and holds only the state text afterwards.

Validation:
- `npm --prefix web run check` (upload-panel.spec.ts, capture-dialog.spec.ts) - Pass

### BUDGET - files-page.scss exceeded the 4 kB component style budget (warning)

Status: fixed in this pass.

Files changed:
- `web/src/app/features/files/upload/drop-hint.ts` (new)
- `web/src/app/features/files/upload/drop-hint.scss` (new)
- `web/src/app/features/files/browser/files-page.ts`, `files-page.html`, `files-page.scss`

Problem:
At base 92738536, `files-page.scss` compiled to about 3.98 kB, just under the 4 kB `anyComponentStyle` budget. This task's drop target and preview button styles pushed it to 4.40 kB, a new build warning. The budget has never been raised since the scaffold (1d26c31b), so this pass did not raise it.

Fix:
- The drop-target styles (dashed outline and hint text) moved into a new `DropHint` component (`app-drop-hint`) in `files/upload` with its own stylesheet. It is an overlay with `aria-hidden` and the host class `drop-hint`, so the existing files-page spec still finds `.drop-hint` with the same text. The files page keeps only `.main { position: relative; min-height: 12rem }`.
- The `button.open` resets were folded into `.open` as `all: unset` followed by the existing layout rules plus `cursor: pointer`. This covers both the link and the preview button.
- Removed `min-width: 0` on `.main`. It had no effect because the grid track is `minmax(0, 1fr)`.
- Grouped the identical list resets of `.crumbs ol` and `.entries`.

Compiled size is now under 4 kB, and the warning no longer appears.

Validation:
- `npm --prefix web run build` - no `files-page.scss` budget warning
- `npm --prefix web exec -- prettier --check` on the changed files - Pass
- `npm --prefix web run check` - Pass (files-page.spec.ts drop tests included)

## Not fixed

### Initial bundle budget warning (650.91 kB against 500 kB)

Status:
Out of scope

Reason:
The warning existed before this task: earlier reports (web-settings-gtd, gtd-capture-api) record it at 647 kB. It is only a warning and does not fail `npm run check`. It concerns the app-wide initial bundle, not a finding of this review.

## Validation summary

Focused tests:
- `npm --prefix web run check` after verifying eeb07812 (before changes) - Pass. Lint clean, 90 test files and 1663 tests passed, build completed with the files-page.scss budget warning and the existing initial bundle warning.

Full test suite:
- `npm --prefix web run check` on the final code - Pass. API client up to date, `ng lint --max-warnings=0` clean, e2e typecheck passed, 90 test files and 1663 tests passed, build completed. The only warning left is the existing initial bundle budget warning.

Lint/type-check/build:
- Included in `npm run check` above - Pass

`.taskcheck`:
- `sh scripts/check.sh` on the final code - Pass ("== all checks passed", exit 0). It ran longer than 10 minutes because a second `check.sh` was running at the same time; a run on its own should be shorter.

Remaining unresolved findings:
0

## Round 2 - re-review finding CR-1 (P3)

Date: 2026-10-21

Scope: the single finding CR-1 in the re-review section "P3 Low" of `reviews/web-files-upload-preview/review.md`. Task base 9d1c49cc. Fix commit eba1547c.

### Fixed findings

#### CR-1 (re-review) - Queued capture attachments showed "0 %" with no waiting state (P3)

Files changed:
- `web/src/app/features/capture/capture-dialog.html`
- `web/src/app/features/capture/capture-dialog.spec.ts`

Problem:
The shared `UploadService` runs `PARALLEL_UPLOADS` (2) uploads at a time. A capture attachment queued behind them had state `queued`, but the status `@switch` had no case for it and the percentage was shown for every unfinished job. The row read "0 %" as if the upload had started.

Fix:
Added a `queued` case to the status `@switch` with the label "Waiting" (`i18n="@@capture.fileQueued"`), matching the upload panel's `@@files.upload.queued`. The percentage is now shown only while the job is `uploading`. The label sits in the existing `role="status"` region, so the change from Waiting to uploading to Attached is announced as a state change. Queue priority was not changed. The new test "shows Waiting, not a percentage, for an attachment queued behind running uploads" pastes three files and checks that the first two show "0 %", the third shows "Waiting" with no request sent, and that the third switches to "0 %" when a slot frees and then to "Attached".

`web/src/locale/messages.xlf` was not changed. It does not hold any of the existing `capture.file*` ids (for example `capture.fileAttached`), so it is not kept up to date with these strings. Regenerating it here would add unrelated ids.

Validation:
- `cd web && npm run check` - Pass (API client up to date, lint clean, e2e typecheck passed, 91 test files and 1673 tests passed, build completed. The only warning is the existing initial bundle budget warning, out of scope as above.)

### Validation summary (round 2)

Focused tests:
- `cd web && npm run check` (includes capture-dialog.spec.ts) - Pass

Full test suite:
- `cd web && npm run check` - Pass, 1673 tests

Lint/type-check/build:
- Included in `npm run check` - Pass

`.taskcheck`:
- `sh scripts/check.sh` on commit eba1547c - Pass ("== all checks passed", exit 0; it took more than 10 minutes)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
