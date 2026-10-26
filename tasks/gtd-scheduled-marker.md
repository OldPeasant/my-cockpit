---
title: Today view: keep 'scheduled for today' visible after the scheduled release
status: in_progress
owner: felix/agent-1
deps: [web-next-today]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd,web/src/app/features/gtd/today]
prio: 2
lease_until: 2026-10-26T08:03:05Z
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
- ASSUMPTION (2026-10-26 08:11+0100 by felix/agent-1): New nullable Task.releasedOn (date the tickler job moved SCHEDULED->NEXT), cleared when the task changes list (move or tickler release), kept on complete/edit/project change; included in backup (optional field, old backups restore with null).
- ASSUMPTION (2026-10-26 08:11+0100 by felix/agent-1): Added GET /tasks?scheduled=today (released to NEXT today OR still SCHEDULED<=today) instead of loading the whole Next list client-side; Today view uses it. Small edit in web/.../gtd/data/task-store.ts (matchesTask + optimistic move) outside the task's touches, needed so live updates match the new filter.
