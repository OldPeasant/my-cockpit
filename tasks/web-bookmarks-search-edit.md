---
title: Web bookmarks: search, edit, undo, to inbox
status: in_progress
owner: dell26/agent-1
deps: []
touches: [web/src/app/features/bookmarks,web/src/locale]
prio: 2
lease_until: 2026-11-09T00:30:10Z
---
## Goal
Android bookmarks have search and editing; web has neither (SPEC.md §8.4 lists search). SPEC.md §4.5 wants
bookmark → Inbox task.

## Acceptance criteria
- [ ] Search field (title, URL, notes, tags)
- [ ] Edit title, tags and notes
- [ ] Undo after delete; "To Inbox task" action
- [ ] New UI strings are in `web/src/locale` (English + German)
- [ ] Unit tests cover the new behavior; `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
