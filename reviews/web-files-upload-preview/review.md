# Code Review

Date: 2026-10-21

Task: web-files-upload-preview - Files: upload and preview

Scope: production changes in `git diff 4e4ca24d..HEAD -- web` (commits e7fdd62d, 1ec0da45): files/upload/*, files/preview/*, files/browser/files-page.*, capture/capture-dialog.*, web/angular.json, e2e/files-upload/upload.spec.ts, e2e/files-browser/browser.spec.ts. Unit specs under src were left to the concurrently running test-writer, except where the committed state breaks the task's check.

Requirements:
- tasks/web-files-upload-preview.md (acceptance criteria 1-5 and the three recorded assumptions)
- SPEC.md section 8.2 (Files, web drive)
- api/openapi.yaml: /api/v1/files, /api/v1/uploads (create, get, abort, chunks, complete), /files/{id}/preview, /files/{id}/thumbnail, capture fileIds
- docs/ARCHITECTURE.md, AGENTS.md project notes, docs/QUALITY.md, deploy/Caddyfile (CSP)

Git range:
- Branch: main
- HEAD: 1ec0da4528a807698ebf0618fdcfb70764fe7655
- Base/start: 4e4ca24d1ceb82fe11364f361c7c077bfd1c2036
- Commits reviewed: 2 (e7fdd62d, 1ec0da45); uncommitted at review time: only the untracked test-writer file web/src/app/features/files/upload/sha256.spec.ts (not reviewed)

Summary: 0 P0, 1 P1, 3 P2, 2 P3

Verified as correct (no finding): chunk loop hashes every chunk in order including ones the server already has, so a resumed upload sends the right SHA-256; the incremental SHA-256 padding and 64-bit length encoding are correct; per-chunk retry only on status 0/408/429/5xx with the injected delays, progress reset to the chunk start before a retry; resume via GET /uploads/{id} with fallback to a new upload on 404; cancel stops the request or the retry pause and is re-checked after every await (check() after arrayBuffer, at the start of every send); retry/cancel/dismiss state guards; the pump never exceeds two running uploads; errors are mapped to ApiError by the problem interceptor and toasts are suppressed via SILENT_ERRORS. Markdown preview goes through renderMarkdown (escapes all text, http/https/mailto links only) and Angular's innerHTML sanitizer, so no XSS path was found. Preview text loading guards against stale responses by file id; PdfViewer guards document loading against src changes. previewKind matches the server's PreviewService type sets. CSP in deploy/Caddyfile allows the same-origin pdf.js worker (worker-src 'self').

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Committed capture dialog spec still asserts the old placeholder behavior; criterion 4 is not met at HEAD**
    - File: `web/src/app/features/capture/capture-dialog.spec.ts:559`
    - Category: Tests
    - Problem: The task replaced the "Attachments can't be saved yet" note and the never-send-fileIds behavior, but the committed spec still expects `.files-note` to contain "can't be saved yet" (line 559) and that the capture body has no `fileIds` (line 568). The `.files-note` element no longer exists, and dropped/pasted files now trigger real POST /api/v1/files requests that the spec neither expects nor flushes. In addition, the commits contain no Vitest tests for upload.service.ts (chunk retry, resume, cancel), preview-kind.ts (selection by mime), PdfViewer, PreviewDialog, UploadPanel or the new capture attachment flow, which criterion 4 names explicitly.
    - Why it matters: `cd web && npm run check` (and therefore `.taskcheck`) fails on the committed revision, and criterion 4 ("tests cover chunk retry, preview selection by mime; npm run check passes") is not satisfied by the reviewed code.
    - Suggested fix: Update the capture "attachments placeholder" tests to the new behavior (upload requests flushed, Save disabled while uploading, `fileIds` sent for done uploads, remove cancels a running upload). Add service tests for chunk retry exhaustion and success after retry, resume skipping received chunks, cancel during a request and during the retry pause, and table tests for previewKind. Re-run `npm run check` and re-review if production code changes.

## P2 Medium

- [ ] **CR-2 | Responsive layout of the Files page was deleted**
    - File: `web/src/app/features/files/browser/files-page.scss:284`
    - Category: Correctness
    - Problem: The diff replaced the existing `@media (max-width: 720px)` block (single-column `.files` grid, `.side` moved below via `order: 2`, `.meta .date` hidden) with the new `button.open` rule instead of adding the rule next to it. No other media query remains in the file.
    - Why it matters: On narrow screens the page keeps the two-column grid with a 12rem minimum sidebar and the date column, squeezing the file list; this is an unrelated regression of the web-files-browser layout.
    - Suggested fix: Restore the removed media query block and keep the new `button.open` rule in addition to it.

- [ ] **CR-3 | PDF page changes can render two pages onto the same canvas and flip the viewer into its error state**
    - File: `web/src/app/features/files/preview/pdf-viewer.ts:143`
    - Category: Correctness
    - Problem: `render()` cancels `this.renderTask` before `await doc.getPage(number)`, but the task of a render whose `getPage` is still pending is not yet stored. When the page changes again before that `getPage` resolves (quick double click on "Next page", or a slow range fetch on a large streamed PDF), both calls proceed to `page.render` on the same canvas. pdf.js rejects a second concurrent render on one canvas with a plain Error, which the catch block treats as a failure (`error.set(true)`), replacing the viewer with "The PDF could not be shown". Depending on resolution order, the earlier page can also be drawn last while `page()` shows the later number.
    - Why it matters: Normal paging through a PDF can break the preview until it is closed and reopened.
    - Suggested fix: Use a render sequence number (or the requested page) captured before the await and return early after `getPage` when a newer render was requested; cancel and await the previous task before starting a new one, and only set `error` for the latest request.

- [ ] **CR-4 | Capture is saved silently without attachments whose upload failed**
    - File: `web/src/app/features/capture/capture-dialog.ts:141`
    - Category: Correctness
    - Problem: `uploading()` only counts unfinished jobs, so Save/Enter is allowed while an attachment is in state `failed`. `fileIds` (line 145) takes only jobs with a result, so the failed file is left out, and afterwards the dialog closes or (Shift+Enter, line 160) drops the failed job from the list. The user gets "Captured to Inbox" with no hint that an attachment was not attached.
    - Why it matters: Criterion 3 is about attaching captured files; with the keyboard-first capture flow (Enter saves) a failed attachment is lost without notice, and the Retry button disappears with the dialog.
    - Suggested fix: Treat failed attachments like running ones for Save (disable Save and say why until the user retries or removes them), or keep the failed jobs in the dialog and show a clear message that they were not attached.

## P3 Low

- [ ] **CR-5 | Cancelling during upload creation or completion can leave server state the client no longer tracks**
    - File: `web/src/app/features/files/upload/upload.service.ts:116`
    - Category: Correctness
    - Problem: `cancel()` unsubscribes from whatever request is running. During POST /uploads (line 236) the server may already have created the upload, but `job.uploadId` is only set after the response, so `discard()` sends no DELETE and the chunks area stays until the 7-day expiry. During POST /uploads/{id}/complete (line 208) the server may already have stored the file; the client then shows "Cancelled", sends a DELETE that can no longer abort it, never emits `uploaded` (the listing is not refreshed), and in the capture dialog the file ends up unlinked in the drive root.
    - Why it matters: A cancelled write may already be committed by the server; the UI then disagrees with the drive.
    - Suggested fix: Do not abort create and complete requests on cancel; let them finish and then discard (DELETE the new upload, or for a completed upload either keep it and report it as uploaded, or delete the created file), or at least refresh the listing after a cancelled completion.

- [ ] **CR-6 | Per-file live regions announce every progress tick**
    - File: `web/src/app/features/files/upload/upload-panel.html:27`
    - Category: Quality
    - Problem: Each job's status text has `role="status"` and, while uploading, contains the byte counter (line 33), which changes on every upload progress event; the capture dialog does the same with the percentage (`web/src/app/features/capture/capture-dialog.html:93`).
    - Why it matters: Screen readers queue an announcement for each change of a polite live region, so a large upload floods the user with byte counts.
    - Suggested fix: Keep the changing byte/percent text outside the live region (the progressbar already exposes the value) and announce only state changes (waiting, uploaded, failed, cancelled).

## Review conclusion

Material issues found; fix before completing the task.
