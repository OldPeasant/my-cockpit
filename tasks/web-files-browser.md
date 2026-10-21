---
title: Files: browser and operations
status: in_progress
owner: R1963MACOS/agent-1
deps: [p0-web-shell,files-api,p0-e2e-harness]
touches: [web/src/app/features/files/browser]
prio: 1
lease_until: 2026-10-21T02:08:30Z
---
## Goal
Drive UI (SPEC.md §8.2).

## Acceptance criteria
- [ ] Folder tree + list/grid, breadcrumbs, new folder, rename, move (dialog), delete, download, download folder as zip, disk usage
- [ ] Vitest component/service tests cover operations and error states; `cd web && npm run check` passes
- [ ] Playwright `e2e/files-browser/browser.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/files-browser/browser.spec.ts`, see p0-e2e-harness): create folder, rename, move, delete

## Notes
