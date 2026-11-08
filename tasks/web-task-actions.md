---
title: Web task detail: complete, delete, undo, Markdown notes
status: in_progress
owner: dell26/agent-1
deps: [web-back-navigation]
touches: [web/src/app/features/gtd/task-detail,web/src/app/features/gtd/lists,web/src/locale]
prio: 2
lease_until: 2026-11-08T14:51:50Z
---
## Goal
Web cannot delete a task except during inbox processing and review (conflicts with SPEC.md phase 1 demo step 9)
and cannot complete/reopen it from task detail. Android has both, with undo.

## Acceptance criteria
- [x] Task detail has Complete/Reopen and Delete (to Trash); after deleting it navigates back (shared back helper if web-back-navigation is done)
  - verified (2026-11-08 15:35+0100 by dell26/agent-1): cd web && npx ng test --watch=false --include='src/app/features/gtd/task-detail/task-detail.spec.ts' (+ task-detail-page.spec.ts, task-detail.service.spec.ts) at c2b8a9e48 -> 148 task-detail tests passed incl. 'complete, reopen and delete' (complete/reopen with If-Match, delete to Trash, 'goes back through the shared back navigation after deleting'); full npm run check -> 4429 passed
- [x] An undo snackbar after completing or deleting, in task detail and in the lists (Next, Today, project detail)
  - verified (2026-11-08 15:35+0100 by dell26/agent-1): npx ng test --include ui/task-undo.spec.ts, next/next-view.spec.ts, today/today-view.spec.ts, projects/projects-pages.spec.ts, lists/list-pages.spec.ts, task-detail.spec.ts -> pass (undo snackbar after complete/delete in task detail; list undo in Next, Today, project detail, Waiting/Someday/Scheduled); part of npm run check -> 4429 passed
- [x] Checklist items can be renamed inline
  - verified (2026-11-08 15:35+0100 by dell26/agent-1): npx ng test --include task-detail/task-checklist.spec.ts -> 44 passed ('rename': Enter/blur save, Escape cancels, empty/unchanged ignored, 412/error rollback); part of npm run check -> 4429 passed
- [x] Task notes are shown rendered as Markdown when not editing (click/Edit switches to the textarea)
  - verified (2026-11-08 15:35+0100 by dell26/agent-1): npx ng test --include task-detail.spec.ts 'rendered notes' -> pass (Markdown rendered when not editing, HTML escaped, Edit/click/Enter switch to textarea, link click keeps view, renders again after save/discard); part of npm run check -> 4429 passed
- [x] New UI strings are in `web/src/locale` (English + German)
  - verified (2026-11-08 15:35+0100 by dell26/agent-1): npx ng test --include src/locale/messages-de.spec.ts -> pass ('has the task actions, undo and notes messages in English and German'); all 13 new ids in messages.xlf and messages.de.xlf (code review); npm run check (build incl. locales) -> pass
- [ ] Unit tests cover the new behavior; `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 15:01+0100 by dell26/agent-1): Undo in the lists also needed small edits in gtd/next, gtd/today, gtd/projects (outside touches) and a shared TaskUndo snackbar service in gtd/ui; the lists have no row delete, so their undo covers completing only
- ASSUMPTION (2026-11-08 15:01+0100 by dell26/agent-1): Task detail Complete saves unsaved valid changes first, then completes and stays on the page (Reopen replaces the button); Delete asks only when there are unsaved changes (Undo snackbar restores from Trash), then navigates back via the shared BackNavigation
- ASSUMPTION (2026-11-08 15:01+0100 by dell26/agent-1): Rendered task notes reuse the projects' renderMarkdown subset; empty notes show the textarea directly; Edit button, click or Enter on the rendered notes switch to the textarea (links still open); after Save/Discard the notes render again. Checklist rename via a pencil button per step (Enter/blur saves, Escape cancels), no double-click since the label toggles the checkbox
