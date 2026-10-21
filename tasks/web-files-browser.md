---
title: Files: browser and operations
status: in_progress
owner: R1963MACOS/agent-1
deps: [p0-web-shell,files-api,p0-e2e-harness]
touches: [web/src/app/features/files/browser]
prio: 1
lease_until: 2026-10-21T03:05:04Z
extended: R1963MACOS/agent-1
---
## Goal
Drive UI (SPEC.md §8.2).

## Acceptance criteria
- [ ] Folder tree + list/grid, breadcrumbs, new folder, rename, move (dialog), delete, download, download folder as zip, disk usage
- [ ] Vitest component/service tests cover operations and error states; `cd web && npm run check` passes
- [ ] Playwright `e2e/files-browser/browser.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/files-browser/browser.spec.ts`, see p0-e2e-harness): create folder, rename, move, delete

## Notes
- ASSUMPTION (2026-10-21 04:12+0200 by R1963MACOS/agent-1): Delete moves a file/folder to the Trash without a confirmation dialog (restorable for 30 days, SPEC §4.2), with a toast; special folders (Attachments/*) hide rename/move/delete. Clicking a file downloads it until web-files-upload-preview adds previews. Downloads use plain links (session cookie) so large files stream to disk instead of through memory.
- ASSUMPTION (2026-10-21 04:12+0200 by R1963MACOS/agent-1): Edited features/files/files.routes.ts (outside touches folder/browser) to route /files and /files/:folderId to the browser page via one UrlMatcher, so the page and its folder tree survive folder navigation. Breadcrumbs walk up via GET /folders/{id} parentId because the API has no ancestors endpoint.
- ASSUMPTION (2026-10-21 04:28+0200 by R1963MACOS/agent-1): Edited web/src/app/app.spec.ts (outside touches): /files moved into its BUILT list since the placeholder is replaced, as done for /projects in 2d807355.
- extended by R1963MACOS/agent-1: Implementation done (9012a30d..). Latest commit fixes TW-1 (move-dialog cancel) and addresses CR-1/CR-2/CR-5 plus tests; .taskcheck passes. Left: have fixer write reviews/web-files-browser/fixes.md for those fixes (and disposition CR-3/CR-4), re-run test-writer + code-reviewer on final code, run Playwright e2e/files-browser/browser.spec.ts against the e2e stack (+ download/zip runtime evidence for CR-4), tasks.py verify 1-3, write quality.json, done.
