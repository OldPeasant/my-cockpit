---
title: Files: browser and operations
status: in_progress
owner: R1963MACOS/agent-1
deps: [p0-web-shell,files-api,p0-e2e-harness]
touches: [web/src/app/features/files/browser]
prio: 1
lease_until: 2026-10-21T03:08:13Z
extended: R1963MACOS/agent-1
---
## Goal
Drive UI (SPEC.md §8.2).

## Acceptance criteria
- [x] Folder tree + list/grid, breadcrumbs, new folder, rename, move (dialog), delete, download, download folder as zip, disk usage
  - verified (2026-10-21 04:51+0200 by R1963MACOS/agent-1): npm run e2e -- e2e/files-browser/browser.spec.ts against deploy/docker-compose.e2e.yml -> 6 passed (create/rename/move-dialog/delete, file download content + folder zip PK header); tree, list/grid, breadcrumbs, disk usage covered by vitest in features/files/browser (73+ tests pass)
- [x] Vitest component/service tests cover operations and error states; `cd web && npm run check` passes
  - verified (2026-10-21 04:51+0200 by R1963MACOS/agent-1): cd web && npm run check -> exit 0: lint 0 warnings, 79 files / 1435 tests pass, build ok; files-browser specs cover operations, 412/404/load/usage/Show more/picker errors; coverage 99.25% stmts, 95.09% branches of changed files
- [x] Playwright `e2e/files-browser/browser.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/files-browser/browser.spec.ts`, see p0-e2e-harness): create folder, rename, move, delete
  - verified (2026-10-21 04:51+0200 by R1963MACOS/agent-1): cd web && npm run e2e -- e2e/files-browser/browser.spec.ts (docker compose e2e stack, chromium) -> 6 passed incl. 'Files browser > create folder, rename, move, delete, download' (3.6s)

## Notes
- ASSUMPTION (2026-10-21 04:12+0200 by R1963MACOS/agent-1): Delete moves a file/folder to the Trash without a confirmation dialog (restorable for 30 days, SPEC §4.2), with a toast; special folders (Attachments/*) hide rename/move/delete. Clicking a file downloads it until web-files-upload-preview adds previews. Downloads use plain links (session cookie) so large files stream to disk instead of through memory.
- ASSUMPTION (2026-10-21 04:12+0200 by R1963MACOS/agent-1): Edited features/files/files.routes.ts (outside touches folder/browser) to route /files and /files/:folderId to the browser page via one UrlMatcher, so the page and its folder tree survive folder navigation. Breadcrumbs walk up via GET /folders/{id} parentId because the API has no ancestors endpoint.
- ASSUMPTION (2026-10-21 04:28+0200 by R1963MACOS/agent-1): Edited web/src/app/app.spec.ts (outside touches): /files moved into its BUILT list since the placeholder is replaced, as done for /projects in 2d807355.
- extended by R1963MACOS/agent-1: Implementation done (9012a30d..). Latest commit fixes TW-1 (move-dialog cancel) and addresses CR-1/CR-2/CR-5 plus tests; .taskcheck passes. Left: have fixer write reviews/web-files-browser/fixes.md for those fixes (and disposition CR-3/CR-4), re-run test-writer + code-reviewer on final code, run Playwright e2e/files-browser/browser.spec.ts against the e2e stack (+ download/zip runtime evidence for CR-4), tasks.py verify 1-3, write quality.json, done.
