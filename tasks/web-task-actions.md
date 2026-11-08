---
title: Web task detail: complete, delete, undo, Markdown notes
status: todo
owner:
deps: [web-back-navigation]
touches: [web/src/app/features/gtd/task-detail,web/src/app/features/gtd/lists,web/src/locale]
prio: 2
---
## Goal
Web cannot delete a task except during inbox processing and review (conflicts with SPEC.md phase 1 demo step 9)
and cannot complete/reopen it from task detail. Android has both, with undo.

## Acceptance criteria
- [ ] Task detail has Complete/Reopen and Delete (to Trash); after deleting it navigates back (shared back helper if web-back-navigation is done)
- [ ] An undo snackbar after completing or deleting, in task detail and in the lists (Next, Today, project detail)
- [ ] Checklist items can be renamed inline
- [ ] Task notes are shown rendered as Markdown when not editing (click/Edit switches to the textarea)
- [ ] New UI strings are in `web/src/locale` (English + German)
- [ ] Unit tests cover the new behavior; `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
