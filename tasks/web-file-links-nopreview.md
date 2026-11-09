---
title: Web: linked items for files without a preview
status: in_progress
owner: dell26/agent-1
deps: [gtd-links-everywhere]
touches: [web/src/app/features/files,web/src/locale]
prio: 2
lease_until: 2026-11-09T10:30:41Z
---
## Goal
gtd-links-everywhere put the web file linked-items panel into the preview dialog, but files without a
preview type (ZIP, DOCX, ...) are downloaded instead of opened (`files-page.ts`), so their linked items
are not reachable on web. Android shows "Unsupported" plus the links toggle (SPEC.md §4.3 parity).

## Acceptance criteria
- [ ] Web: a file without a preview type can show its linked-items panel (e.g. preview dialog with a "no preview" notice plus download and the links toggle, like Android)
- [ ] New strings in English and German (`web/src/locale`)
- [ ] Unit tests cover the new behavior; `sh scripts/check.sh` passes

## Notes
From code review finding CR-1 (P2) of gtd-links-everywhere, deferred there.
- ASSUMPTION (2026-11-09 11:13+0100 by dell26/agent-1): Files without a preview type now open the preview dialog alone (no paging to other files) instead of downloading on click; the row label became 'Open NAME' and Download stays in the dialog and the row menu, matching Android's Unsupported view
