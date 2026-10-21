# Code Review

Date: 2026-10-21

Task: web-files-upload-preview - Files: upload and preview

Scope: final code of the whole task, `git diff fe95f0ae..HEAD -- web`, at HEAD 242e712e. This re-review covers what changed since the previous re-review at a7e14ab2. That revision was rebased as 5aec485d, and `git diff a7e14ab2 5aec485d -- web` is empty. `git diff 5aec485d..HEAD --stat` shows that only three spec files and `reviews/` changed since then: capture-dialog.spec.ts (+49), preview-dialog.spec.ts (+15) and files-page.spec.ts (+26). No production file changed, so the production findings of the previous re-review still hold. I read the new specs in full against the production code they test: capture-dialog.html/.ts, upload.service.ts (multipart params) and the preview paging in files-page. Committed changes only. Playwright, Docker and scripts/check.sh were not run, as the parent asked.

Requirements:
- tasks/web-files-upload-preview.md (acceptance criteria 1-5 and the recorded assumptions, including the 07:49 assumption that accepts the previous P3)
- SPEC.md section 8.2 (Files) and section 4.5 (quick capture)
- AGENTS.md project notes, docs/ARCHITECTURE.md (stack, i18n, conventions)
- The earlier reviews in this file (first review CR-1..CR-6, re-review CR-1, re-review 2 CR-1) and reviews/web-files-upload-preview/fixes.md

Git range:
- Branch: main
- HEAD: 242e712e9bf4de4502e623601ce8fa0078bc2108
- Base/start: fe95f0ae6c4805b5db0f282824f5316030255509 (task base commit given by the parent)
- Commits reviewed: 12 (5bbaa8b7..242e712e). Focus on the 3 commits after 5aec485d (a82b5d0a, d9efd780, 242e712e).
- Uncommitted changes: none at review start

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Earlier findings: status

- First review CR-1 (P1, capture spec/criterion 4 tests): resolved.
- First review CR-2 (P2, responsive layout deleted): resolved.
- First review CR-3 (P2, concurrent PDF renders): resolved.
- First review CR-4 (P2, capture saved without failed attachments): resolved. Submit is blocked on `failedFiles()`.
- First review CR-5 (P3, cancel during create/complete): resolved.
- First review CR-6 (P3, live regions): resolved.
- Re-review CR-1 (P3, queued capture attachments showed no waiting state): resolved in 5aec485d (the `queued` case "Waiting" in `capture-dialog.html:97-99`).
- Re-review 2 CR-1 (P3, cancelling a single-request upload after the server stored it shows "Cancelled" while the file stays in the drive, `upload.service.ts:185`): not changed in code. The parent accepted it as a recorded assumption (task Notes, 2026-10-21 07:49 by dell26/agent-6). I agree with that disposition: the race window is small, and the file shows up after the next refresh and can be deleted. It is no longer an open finding.

## New specs checked without findings

- capture-dialog.spec.ts, "announces Waiting in the live region and keeps Save disabled while an attachment is queued": it reads the `[role="status"]` element itself, so it checks what is announced, not just the visible text. It checks `data-state="queued"`, a disabled Save, that `submit()` resolves to false, and that no `/api/v1/capture` request is sent. It then checks that the live region becomes empty while the file is uploading (this matches the template: the percentage is outside the status span), that Save waits for the third upload, and that `fileIds` keeps the file order. The assertions match the production template and the `uploading()`/`failedFiles()` guards.
- capture-dialog.spec.ts, "Remove of a queued attachment keeps it from ever being uploaded": `http.expectNone` matches on `params.get('name') === 'c.png'`. This matches `upload.service.ts:183`, which sends the name as a query param. Without the removal, the queued job would start as soon as a and b are flushed, so the negative assertion would catch a regression. It also checks that the removed file is left out of `fileIds`.
- preview-dialog.spec.ts, "shows the HTML and unsafe links of a Markdown file as inert text": a useful security regression test for the reused `renderMarkdown`. It checks that no `img`/`script`/`[onerror]` element is rendered, that the raw HTML is shown as text, that no `javascript:` href remains and that a safe https link survives.
- files-page.spec.ts, "pages the preview only through the files of a folder that also holds subfolders": checks that the subfolder is not a preview page ("2 of 2", "1 of 2") and that ArrowLeft stops at the first file. These are real behavior assertions through keyboard events.
- None of the specs weaken, skip or delete existing tests. The fixtures are fictitious (example.org, fixed UUIDs).
- Focused vitest: `npm --prefix web run test:ci -- --include=src/app/features/capture --include=src/app/features/files` gives 17 files and 292 tests passed at 242e712e (288 at the previous review, plus the 4 new tests).

## Overall task against criteria

- Criterion 1 (drag & drop and picker, multi-file, progress, chunked resumable upload over 50 MB with retry, cancel): implemented in upload.service, FileDrop/DropHint, upload-panel and files-page, and covered by upload.service.spec and upload-panel.spec. No open finding.
- Criterion 2 (previews: images with thumbnails and lightbox, PDF with pdfjs-dist, text/Markdown, audio/video): implemented in preview-kind, preview-dialog, pdf-viewer and file-thumb, with specs. No open finding.
- Criterion 3 (capture attachments: upload, then pass fileIds): implemented and covered by capture-dialog.spec. The drive-root target is a recorded assumption.
- Criterion 4 (vitest covers chunk retry and preview selection by mime; `npm run check` passes): chunk retry is in upload.service.spec and mime selection is in preview-kind.spec. I did not run `npm run check`; the parent must record it.
- Criterion 5 (Playwright): recorded at d0bd6037. The only production change after that (the capture status template in 5aec485d) is not exercised by `e2e/files-upload/upload.spec.ts`, so that evidence still applies.

Evidence note (not a finding): `python tasks.py verify web-files-upload-preview` still shows criteria 1-4 unrecorded. The parent worker has to record them before `done`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. Production code has not changed since the previous re-review (a7e14ab2, which is 5aec485d after the rebase). The four new specs are correct and useful, and the focused suite passes (292 tests). Every earlier finding is resolved, except the last P3, which the parent accepted as a recorded assumption. Before `done`, the parent worker still has to record evidence for criteria 1-4 and run `npm run check` / `.taskcheck`.
