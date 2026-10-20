---
title: Files: upload and preview
status: todo
owner:
deps: [web-files-browser,files-preview]
touches: [web/src/app/features/files/upload,web/src/app/features/files/preview]
prio: 1
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
