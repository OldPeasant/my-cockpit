---
title: Today view: keep 'scheduled for today' visible after the scheduled release
status: todo
owner: 
deps: [web-next-today]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd,web/src/app/features/gtd/today]
prio: 2
lease_until: 
releases: 0
extended: 
hold: 
---
## Goal
TaskService.releaseDue moves SCHEDULED tasks to NEXT and clears scheduledDate, so the Today view (SPEC.md §5.3 'scheduled for today') can't tell which Next actions were scheduled for today.

## Acceptance criteria
- [ ] Server keeps a marker (e.g. `scheduledDate` kept or a new `releasedOn` field) on released tasks, new Flyway migration if needed, `api/openapi.yaml` regenerated; server tests cover it
- [ ] Today view 'Scheduled for today' shows tasks released today; Vitest covers it; `cd web && npm run check` passes

## Notes
- release by felix/agent-1: Not started. Host felix: JAVA_HOME=/usr/lib/jvm/java-21-openjdk missing, server build/check cannot run; released so a healthy host can take it.
- release by felix/agent-1: Not started. Host felix still has JAVA_HOME=/usr/lib/jvm/java-21-openjdk missing (checked at 545116db); server build/.taskcheck cannot run here. Released for a healthy host.
- hold by felix/agent-1: Host felix: JAVA_HOME=/usr/lib/jvm/java-21-openjdk does not exist (only java-25 installed), so server build and .taskcheck cannot run; a human must fix the JDK on felix (or run this task on another host), then unhold. No work started.
- unhold by Peter Gürtler: JDK issue on felix is fixed
