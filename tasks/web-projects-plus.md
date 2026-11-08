---
title: Web projects: delete project, all tasks in project detail
status: in_progress
owner: dell26/agent-1
deps: [web-back-navigation]
touches: [web/src/app/features/gtd/projects,web/src/locale]
prio: 2
lease_until: 2026-11-08T16:12:32Z
---
## Goal
There is no UI to delete a project (`ProjectStore.remove` unused except in the review), and project detail shows
only open Next tasks while Android shows the project's waiting/someday/scheduled tasks with their list label.

## Acceptance criteria
- [ ] Delete project (menu, confirmation, server delete-reassign rules)
- [ ] Project detail shows all open tasks of the project with a list label for non-Next ones (Waiting, Someday, Scheduled)
- [ ] New UI strings are in `web/src/locale` (English + German)
- [ ] Unit tests cover the new behavior; `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 16:48+0100 by dell26/agent-1): Delete lives in a 'More project actions' menu on project detail only (not on list rows), like Android's detail menu; after deleting, the page goes to /projects with an Undo snackbar that restores the project via POST /trash restore (type PROJECT).
- ASSUMPTION (2026-11-08 16:48+0100 by dell26/agent-1): 'Server delete-reassign rules' = the existing server rule (DELETE /projects/{id}: tasks keep their project, no reassignment); the confirmation states that open tasks stay open and keep the project, as Android does. No server change.
- ASSUMPTION (2026-11-08 16:48+0100 by dell26/agent-1): Project detail already loaded all open tasks of every list (GET /tasks?projectId= has no list filter); the change adds list labels. Inbox tasks of a project also get an 'Inbox' label (Android parity), besides Waiting/Someday/Scheduled.
