---
title: Web projects: delete project, all tasks in project detail
status: done
owner: dell26/agent-1
deps: [web-back-navigation]
touches: [web/src/app/features/gtd/projects,web/src/locale]
prio: 2
lease_until: 
---
## Goal
There is no UI to delete a project (`ProjectStore.remove` unused except in the review), and project detail shows
only open Next tasks while Android shows the project's waiting/someday/scheduled tasks with their list label.

## Acceptance criteria
- [x] Delete project (menu, confirmation, server delete-reassign rules)
  - verified (2026-11-08 17:02+0100 by dell26/agent-1): project-detail-delete.spec.ts (npx ng test --include src/app/features/gtd/projects -> 122 passed): menu 'More project actions' > 'Delete project' opens DeleteProjectDialog (title, Trash text, open-task count 0/1/n), confirm sends DELETE /projects/p1 with current If-Match, navigates to /projects, Undo snackbar restores via restoreTrashItem PROJECT; cancel/dismiss/refused delete keep the project; server rule (tasks keep their project) stated in the dialog
- [x] Project detail shows all open tasks of the project with a list label for non-Next ones (Waiting, Someday, Scheduled)
  - verified (2026-11-08 17:02+0100 by dell26/agent-1): project-detail-delete.spec.ts 'list labels' tests -> pass: tasks in WAITING/SOMEDAY/SCHEDULED/INBOX of the project show 'Waiting'/'Someday'/'Scheduled'/'Inbox', NEXT tasks no label, next-step marker kept on a non-Next next step (GET /tasks?projectId= returns all open lists)
- [x] New UI strings are in `web/src/locale` (English + German)
  - verified (2026-11-08 17:02+0100 by dell26/agent-1): 12 new trans-units (gtd.project.delete*, gtd.project.actions, gtd.project.list.*, gtd.project.deleted) added to web/src/locale/messages.xlf and messages.de.xlf with German targets; messages-de.spec.ts in npm run check -> pass; ng build (incl. de locale) -> ok
- [x] Unit tests cover the new behavior; `cd web && npm run check` and `sh scripts/check.sh` pass
  - verified (2026-11-08 17:02+0100 by dell26/agent-1): cd web && npx ng test --include src/app/features/gtd/projects -> 122 passed; sh scripts/check.sh (runs web npm run check: 191 files / 4450 tests, lint, build; server; android) on b7195f3d8 -> all checks passed

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 16:48+0100 by dell26/agent-1): Delete lives in a 'More project actions' menu on project detail only (not on list rows), like Android's detail menu; after deleting, the page goes to /projects with an Undo snackbar that restores the project via POST /trash restore (type PROJECT).
- ASSUMPTION (2026-11-08 16:48+0100 by dell26/agent-1): 'Server delete-reassign rules' = the existing server rule (DELETE /projects/{id}: tasks keep their project, no reassignment); the confirmation states that open tasks stay open and keep the project, as Android does. No server change.
- ASSUMPTION (2026-11-08 16:48+0100 by dell26/agent-1): Project detail already loaded all open tasks of every list (GET /tasks?projectId= has no list filter); the change adds list labels. Inbox tasks of a project also get an 'Inbox' label (Android parity), besides Waiting/Someday/Scheduled.
- done by dell26/agent-1: `sh scripts/check.sh` passed in 133s on 6b987fbcd; criteria verified 4/4
