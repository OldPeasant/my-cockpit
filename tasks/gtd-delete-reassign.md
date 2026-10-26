---
title: Server-side reassignment when deleting a context or area
status: in_progress
owner: dell26/agent-2
deps: [web-settings-gtd]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd,api/openapi.yaml,web/src/app/features/settings/gtd]
prio: 2
lease_until: 2026-10-26T02:48:12Z
releases: 0
extended: 
hold: 
---
## Goal
Deleting a context/area with "move to another" must also reach items the list endpoints hide
(tasks in the tickler or Trash, projects in the Trash). Today the web client reassigns only what
`listTasks`/`listProjects` return (web-settings-gtd), so hidden items just lose the context/area.

## Acceptance criteria
- [ ] `DELETE /api/v1/contexts/{id}?replaceWith=<id>` and `DELETE /api/v1/areas/{id}?moveTo=<id>` reassign all rows (incl. tickler/Trash) in one transaction; unknown target -> 400; integration tests
- [ ] `api/openapi.yaml` regenerated; Settings -> Tasks & projects uses the parameter instead of patching items client-side, and the tickler/Trash hint in the delete dialog is removed; Vitest tests updated; `cd web && npm run check` passes

## Notes
- release by felix/agent-1: Not started. Host felix has a broken JDK (JAVA_HOME=/usr/lib/jvm/java-21-openjdk missing), so server tests and .taskcheck cannot run here; released for an agent on a working host.
- release by felix/agent-1: Not started. Host felix still has a broken JDK (JAVA_HOME=/usr/lib/jvm/java-21-openjdk missing; server/gradlew -v fails), so server integration tests and .taskcheck cannot run here; released for an agent on a working host.
- release by felix/agent-1: Not started. Host felix still has a broken JDK (JAVA_HOME=/usr/lib/jvm/java-21-openjdk missing; server/gradlew -p server -v fails), so server integration tests and .taskcheck cannot run here; released for an agent on a working host.
- ASSUMPTION (2026-10-26 02:44+0100 by dell26/agent-2): replaceWith/moveTo equal to the deleted context/area itself is rejected with 400 like an unknown id (field error on the parameter); a task that already has the replacement context just keeps it once
- ASSUMPTION (2026-10-26 02:44+0100 by dell26/agent-2): The delete dialog's task/project count still comes from listTasks/listProjects (visible items only); a context used only by tickler/Trash tasks shows 'No task uses this context' and is deleted without replacement. Counting hidden items would need a new count endpoint, out of scope
