---
title: Server-side reassignment when deleting a context or area
status: todo
owner:
deps: [web-settings-gtd]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd,api/openapi.yaml,web/src/app/features/settings/gtd]
prio: 2
---
## Goal
Deleting a context/area with "move to another" must also reach items the list endpoints hide
(tasks in the tickler or Trash, projects in the Trash). Today the web client reassigns only what
`listTasks`/`listProjects` return (web-settings-gtd), so hidden items just lose the context/area.

## Acceptance criteria
- [ ] `DELETE /api/v1/contexts/{id}?replaceWith=<id>` and `DELETE /api/v1/areas/{id}?moveTo=<id>` reassign all rows (incl. tickler/Trash) in one transaction; unknown target -> 400; integration tests
- [ ] `api/openapi.yaml` regenerated; Settings -> Tasks & projects uses the parameter instead of patching items client-side, and the tickler/Trash hint in the delete dialog is removed; Vitest tests updated; `cd web && npm run check` passes

## Notes
