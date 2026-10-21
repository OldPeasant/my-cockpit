---
title: Files: upload and preview
status: in_progress
owner: dell26/agent-4
deps: [web-files-browser,files-preview]
touches: [web/src/app/features/files/upload,web/src/app/features/files/preview]
prio: 1
lease_until: 2026-10-21T04:33:27Z
---
## Goal
Uploads with progress and previews (SPEC.md §8.2).

## Acceptance criteria
- [ ] Drag & drop and picker upload, multi-file, progress, chunked resumable upload > 50 MB with retry; cancel
- [ ] Previews: images (thumbnails, lightbox), PDF (pdfjs-dist), text/Markdown, audio/video
- [ ] Capture dialog attachments enabled (upload then pass fileIds)
- [ ] Vitest component/service tests cover chunk retry, preview selection by mime; `cd web && npm run check` passes
- [ ] Playwright `e2e/files-upload/upload.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/files-upload/upload.spec.ts`, see p0-e2e-harness): 60 MB upload completes; PDF preview renders a page

## Notes
- ASSUMPTION (2026-10-21 06:04+0200 by dell26/agent-4): Capture dialog attachments upload to the drive root (no /Attachments/Tasks folder exists yet on the server); Save waits until no attachment is still uploading; files of a cancelled capture stay in the drive. Touched web/src/app/features/capture and files/browser (outside touches) because the criteria require it.
- ASSUMPTION (2026-10-21 06:04+0200 by dell26/agent-4): Chunked upload: SHA-256 computed in the browser with a small incremental TS implementation (WebCrypto cannot hash multi-GB files in parts); each chunk is retried 3 times (1s/3s/9s) on network/408/429/5xx; a failed upload resumes via GET /uploads/{id} on Retry. 2 files upload in parallel.
- ASSUMPTION (2026-10-21 06:04+0200 by dell26/agent-4): Previewable files (image/pdf/text/markdown/audio/video) open the preview on click instead of downloading; download stays in the entry's menu and the preview header. Markdown preview reuses gtd/projects/markdown.ts renderMarkdown; pdf.js worker is copied to /pdfjs via angular.json assets and pdf.js is lazy-loaded.
