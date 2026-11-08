---
title: Web task detail: complete, delete, undo, Markdown notes
status: in_progress
owner: dell26/agent-1
deps: [web-back-navigation]
touches: [web/src/app/features/gtd/task-detail,web/src/app/features/gtd/lists,web/src/locale]
prio: 2
lease_until: 2026-11-08T14:46:48Z
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
- ASSUMPTION (2026-11-08 15:01+0100 by dell26/agent-1): Undo in the lists also needed small edits in gtd/next, gtd/today, gtd/projects (outside touches) and a shared TaskUndo snackbar service in gtd/ui; the lists have no row delete, so their undo covers completing only
- ASSUMPTION (2026-11-08 15:01+0100 by dell26/agent-1): Task detail Complete saves unsaved valid changes first, then completes and stays on the page (Reopen replaces the button); Delete asks only when there are unsaved changes (Undo snackbar restores from Trash), then navigates back via the shared BackNavigation
- ASSUMPTION (2026-11-08 15:01+0100 by dell26/agent-1): Rendered task notes reuse the projects' renderMarkdown subset; empty notes show the textarea directly; Edit button, click or Enter on the rendered notes switch to the textarea (links still open); after Save/Discard the notes render again. Checklist rename via a pencil button per step (Enter/blur saves, Escape cancels), no double-click since the label toggles the checkbox
