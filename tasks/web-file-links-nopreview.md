---
title: Web: linked items for files without a preview
status: todo
owner:
deps: [gtd-links-everywhere]
touches: [web/src/app/features/files,web/src/locale]
prio: 2
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
