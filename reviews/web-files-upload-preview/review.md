# Code Review

Date: 2026-10-21

Task: web-files-upload-preview - Files: upload and preview

Scope: full task diff `git diff c725676c..HEAD -- web` on the final code (commits 099dd516, b059eb59, d9a455aa, a1f6c6b5, 323e47a2, 93a3687e, d0bd6037). Production files: files/upload/* (upload.service, sha256, file-drop, drop-hint, upload-panel, format-bytes), files/preview/* (preview-dialog, pdf-viewer, preview-kind, preview.service, file-thumb), files/browser/files-page.*, capture/capture-dialog.*, web/angular.json, e2e/files-upload/upload.spec.ts, e2e/files-browser/browser.spec.ts. Committed specs were checked for coverage of the fixes. Uncommitted spec files of the concurrently running test-writer were not reviewed. Playwright and Docker were not run, as the parent agent asked.

Requirements:
- tasks/web-files-upload-preview.md (acceptance criteria 1-5, the three recorded assumptions)
- SPEC.md section 8.2 (Files, web drive)
- AGENTS.md project notes, docs/ARCHITECTURE.md (stack, i18n, conventions)
- Previous review (CR-1..CR-6 on revision 1ec0da45) and reviews/web-files-upload-preview/fixes.md

Git range:
- Branch: main
- HEAD: d0bd60373e35a61f30068519fc2b5eb3768a22e0
- Base/start: c725676c23944cf2a30a4e345bd629c8f7d1ad0b
- Commits reviewed: 7
- Uncommitted changes: none at review start

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Earlier findings: status

- CR-1 (P1, capture spec asserted the old placeholder, no tests for criterion 4): resolved. `capture-dialog.spec.ts` no longer refers to `.files-note` or "can't be saved yet". `upload.service.spec.ts` covers chunk retry, retry exhaustion, resume (with received chunks skipped and the 404 restart), and cancel during a request, between chunks, during the retry pause, during create and during complete. `preview-kind.spec.ts` covers selection by mime type. pdf-viewer, preview-dialog, upload-panel, file-thumb and sha256 also have specs.
- CR-2 (P2, responsive layout deleted): resolved. The diff of `files-page.scss` no longer touches the `@media (max-width: 720px)` block.
- CR-3 (P2, concurrent PDF renders on one canvas): resolved. `pdf-viewer.ts:151-157` takes a sequence number before any await, gives up after `getPage` and again after awaiting the previous render's settled promise. Between the last check and `page.render` there is no await, so two renders can no longer overlap on the canvas. Only the latest request sets `rendered` or `error`, and `close()` bumps the sequence, so a render of a replaced document stops too. I walked through the A/B/C interleavings (a newer request arriving while an older one waits on `getPage` or on `this.rendering`): each time only the newest one draws. Specs: "draws only the latest page when pages change while one is still loading" and "a stale render that fails does not show an error". The fake pdf.js in the spec rejects a second concurrent render, as pdf.js does.
- CR-4 (P2, capture saved without failed attachments): resolved. `failedFiles()` blocks `submit()` (`capture-dialog.ts:145`) for Enter, Shift+Enter and the button. Save is disabled and described by `#capture-files-failed`. Retry and Remove are offered for each file.
- CR-5 (P3, cancel during create/complete): resolved. Create and complete are sent with `abortable = false` (`upload.service.ts:224`, `:266`). A cancel during create DELETEs the new upload once its id arrives (`:270-274`). A cancel during complete sends no DELETE (`completing`). A completion that then succeeds becomes `done` and emits `uploaded`, so the listing refreshes. A completion that then fails is discarded (`:228`). `run()` still ignores errors of a cancelled job (`:171`). Four specs cover these paths.
- CR-6 (P3, live regions announced progress): resolved. In `upload-panel.html` and `capture-dialog.html`, the `role="status"` element holds only the state text, and the byte or percentage text sits outside it.

## New code checked without findings

- DropHint (`files/upload/drop-hint.ts`): it is purely visual (`aria-hidden`, `pointer-events: none`), absolutely positioned inside `.main`, which is now `position: relative`. It is shown only while `FileDrop.dragging()` is true. The `.drop-hint` host class keeps the files-page spec selectors working.
- `files-page.scss` `.open { all: unset; ... }`: the existing `&:focus-visible` outline comes after the reset in the same rule, so keyboard focus is still visible for both the link and the new preview button. The grid variant still overrides the layout.
- FileDrop: it ignores drags without `Files` and handles dragleave into child elements. `preventDefault` is called only when files were dropped.
- Upload service on retry and cancel: a failed chunked job keeps `uploadId` and resumes it. A cancel of a failed job DELETEs it. `dismiss()` of a done job is a no-op cancel. `clearFinished()` keeps failed and running jobs.
- Files page: uploads go into the folder shown, the listing, tree and usage refresh once per burst of uploads (`debounceTime`), the picker input is reset so the same file can be picked again, and previewable files open the preview with the folder's files to page through (recorded assumption).
- Preview dialog: text loading ignores responses for a file that is no longer shown. Markdown goes through `renderMarkdown` and Angular's `innerHTML` sanitizer. The image error state resets on file change. Arrow keys inside audio and video controls are left alone.
- e2e spec: it uploads through the picker (60 MB chunked file, PDF, Markdown), compares the stored SHA-256 with the bytes sent, checks that the PDF canvas has `data-rendered="1"` and non-uniform pixels, and pages to the Markdown file. The files-browser e2e now downloads through the entry menu, which matches the new click-to-preview behavior.

Evidence note (not a finding): `python tasks.py verify web-files-upload-preview` shows criteria 1-5 as not yet recorded at review time. The parent worker still has to record evidence, including the Playwright run for criterion 5 against the e2e stack.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Capture attachments queue behind drive uploads and show no waiting state**
    - File: `web/src/app/features/capture/capture-dialog.html:96`
    - Category: Correctness
    - Problem: Capture attachments go through the shared root `UploadService`, which runs at most `PARALLEL_UPLOADS` (2) jobs across the whole app (`upload.service.ts:30`, `:153`). If two large drive uploads are running (for example multi-GB files from the Files page), a file pasted into the quick capture stays `queued` until one of them finishes. `uploading()` (`capture-dialog.ts:58`) counts queued jobs, so Save stays disabled the whole time. The capture file list has no `queued` case in its status `@switch` (lines 96-107) and shows "0 %" (line 109), so the user cannot tell that the attachment is waiting for other uploads rather than uploading.
    - Why it matters: Quick capture is meant to be fast (SPEC.md §4.5). Behind long drive uploads it can block Save for minutes with no visible reason.
    - Suggested fix: Show the queued state in the capture list (for example "Waiting", as the upload panel does), and/or let capture attachments skip ahead of the drive queue (start them right away, or put them at the front of the queue).

## Review conclusion

No material issues found. All six earlier findings (CR-1..CR-6) are resolved in the current code, and the one new finding is low priority. Acceptance criterion evidence (`tasks.py verify`, including the Playwright run for criterion 5) is still to be recorded by the parent worker.
