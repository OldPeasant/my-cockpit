---
title: Web: linked items for files without a preview
status: done
owner: dell26/agent-1
deps: [gtd-links-everywhere]
touches: [web/src/app/features/files,web/src/locale]
prio: 2
lease_until: 
---
## Goal
gtd-links-everywhere put the web file linked-items panel into the preview dialog, but files without a
preview type (ZIP, DOCX, ...) are downloaded instead of opened (`files-page.ts`), so their linked items
are not reachable on web. Android shows "Unsupported" plus the links toggle (SPEC.md §4.3 parity).

## Acceptance criteria
- [x] Web: a file without a preview type can show its linked-items panel (e.g. preview dialog with a "no preview" notice plus download and the links toggle, like Android)
  - verified (2026-11-09 11:20+0100 by dell26/agent-1): npx ng test --include='src/app/features/files/**/*.spec.ts' (web/) -> passed: clicking 'Open Taxes.zip'/'Open Cards.zip' row opens PreviewDialog alone showing 'There is no preview for this type of file.', Download link /api/v1/files/ID/content and Linked items toggle that loads GET /api/v1/links?type=FILE&id=ID
- [x] New strings in English and German (`web/src/locale`)
  - verified (2026-11-09 11:14+0100 by dell26/agent-1): Added files.openFile, files.preview.unsupported, files.preview.downloadFile to web/src/locale/messages.xlf (npm run i18n:extract output matched, only line numbers differed) and messages.de.xlf; npm run check (incl. messages-de.spec) -> 4811 passed, lint 0 warnings
- [x] Unit tests cover the new behavior; `sh scripts/check.sh` passes
  - verified (2026-11-09 11:20+0100 by dell26/agent-1): sh scripts/check.sh at 3ad50d319 -> all checks passed (web 196 files / 4815 tests, server, android, tfp); new tests in files-page.spec.ts (zip row opens dialog alone with notice/download/links), preview-dialog.spec.ts (zip, docx, paging regression), messages-de.spec.ts (EN/DE strings)

## Notes
From code review finding CR-1 (P2) of gtd-links-everywhere, deferred there.
- ASSUMPTION (2026-11-09 11:13+0100 by dell26/agent-1): Files without a preview type now open the preview dialog alone (no paging to other files) instead of downloading on click; the row label became 'Open NAME' and Download stays in the dialog and the row menu, matching Android's Unsupported view
- done by dell26/agent-1: `sh scripts/check.sh` passed in 73s on 90b782d13; criteria verified 3/3
