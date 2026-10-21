---
title: Files: upload and preview
status: in_progress
owner: dell26/agent-4
deps: [web-files-browser,files-preview]
touches: [web/src/app/features/files/upload,web/src/app/features/files/preview]
prio: 1
lease_until: 2026-10-21T07:33:19Z
extended: 
releases: 2
---
## Goal
Uploads with progress and previews (SPEC.md §8.2).

## Acceptance criteria
- [ ] Drag & drop and picker upload, multi-file, progress, chunked resumable upload > 50 MB with retry; cancel
- [ ] Previews: images (thumbnails, lightbox), PDF (pdfjs-dist), text/Markdown, audio/video
- [ ] Capture dialog attachments enabled (upload then pass fileIds)
- [ ] Vitest component/service tests cover chunk retry, preview selection by mime; `cd web && npm run check` passes
- [x] Playwright `e2e/files-upload/upload.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/files-upload/upload.spec.ts`, see p0-e2e-harness): 60 MB upload completes; PDF preview renders a page
  - verified (2026-10-21 07:22+0200 by dell26/agent-4): npm --prefix web run e2e -- e2e/files-upload/upload.spec.ts (deploy/docker-compose.e2e.yml stack, chromium, HEAD d0bd6037) -> 6 passed incl. 'multi-file upload with a 60 MB file, PDF preview' (11.2s)

## Notes
- ASSUMPTION (2026-10-21 06:04+0200 by dell26/agent-4): Capture dialog attachments upload to the drive root (no /Attachments/Tasks folder exists yet on the server); Save waits until no attachment is still uploading; files of a cancelled capture stay in the drive. Touched web/src/app/features/capture and files/browser (outside touches) because the criteria require it.
- ASSUMPTION (2026-10-21 06:04+0200 by dell26/agent-4): Chunked upload: SHA-256 computed in the browser with a small incremental TS implementation (WebCrypto cannot hash multi-GB files in parts); each chunk is retried 3 times (1s/3s/9s) on network/408/429/5xx; a failed upload resumes via GET /uploads/{id} on Retry. 2 files upload in parallel.
- ASSUMPTION (2026-10-21 06:04+0200 by dell26/agent-4): Previewable files (image/pdf/text/markdown/audio/video) open the preview on click instead of downloading; download stays in the entry's menu and the preview header. Markdown preview reuses gtd/projects/markdown.ts renderMarkdown; pdf.js worker is copied to /pdfjs via angular.json assets and pdf.js is lazy-loaded.
- extended by dell26/agent-4: Implementation complete (uploads chunked/resumable w/ retry+cancel, previews image/pdf/text/md/audio/video, capture attachments) plus unit specs and e2e spec committed. Last commit 7985989d holds uncommitted fixes for review findings CR-1..CR-6 (no fixes.md written yet; verify each finding is addressed). npm run check builds green at 7985989d (warning: files-page.scss over 4kB budget, initial bundle budget). Left: confirm/finish CR fixes (fixer -> fixes.md), re-run test-writer + code-reviewer on final code, run Playwright e2e/files-upload/upload.spec.ts against e2e stack, tasks.py verify 1-5, quality.json, .taskcheck, done.
- release by dell26/agent-4: Implementation complete and committed (HEAD 9eab227d): chunked/resumable upload with retry+cancel, DropHint/FileDrop, previews image/pdf/text/md/audio/video, capture attachments. Fixes for CR-1..CR-6 are in fixes.md. Re-review on final production code (d0bd6037 + 7ebfa162 DropHint) in review.md: 0 P0/P1/P2, 1 P3, every earlier finding resolved. Criterion 5 (Playwright e2e) verified. sh scripts/check.sh passes at 9eab227d. LEFT: (1) tests.md is stale (old revision). Run test-writer on the final code: it should take in the specs committed in 9eab227d (drop-hint.spec, more pdf-viewer/upload.service/files-page specs) and rewrite tests.md with coverage. (2) Read the single P3 in review.md and decide on it; if code changes, re-run reviewer+test-writer. (3) tasks.py verify criteria 1-4 (focused vitest: npm --prefix web run test:ci -- --include=src/app/features/files --include=src/app/features/capture; npm --prefix web run check). (4) Write quality.json (reviewed_revision = final hash), commit, then run done.
- ASSUMPTION (2026-10-21 07:49+0200 by dell26/agent-6): Cancelling a <=50 MB single-request upload in the moment after the server stored it but before the response shows 'Cancelled' while the file stays in the drive (review P3, accepted: rare race, file is visible after the next refresh and can be deleted).
- release by dell26/agent-6: Implementation, specs, test-writer (tests.md), code-reviewer (review.md: 0 findings, final) and fixer (fixes.md) are all committed; criterion 5 (Playwright) verified. Left (finalization only): (1) tasks.py verify criteria 1-4 using the evidence in reviews/web-files-upload-preview/tests.md + review.md (npm --prefix web run check -> 1677 passed); (2) write reviews/web-files-upload-preview/quality.json per docs/QUALITY.md with reviewed_revision = current HEAD (only reviews/ commits after the reviewed code, so the patch-id is fresh), findings CR-1..CR-6, re-review CR-1 fixed, re-review-2 CR-1 accepted via assumption; (3) run sh scripts/check.sh (my run was cut off by a 600s shell timeout during server tests under high host load, with no failure seen); (4) tasks.py done.
