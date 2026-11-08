---
title: Web projects: delete project, all tasks in project detail
status: todo
owner:
deps: [web-back-navigation]
touches: [web/src/app/features/gtd/projects,web/src/locale]
prio: 2
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
