---
title: GTD data model
status: in_progress
owner: dell26/agent-3
deps: [p0-db-conventions,p0-items-links]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd,server/src/test/kotlin/ch/sonensei/cockpit/gtd,server/src/main/resources/db/migration/gtd]
prio: 1
lease_until: 2026-10-20T13:31:17Z
---
## Goal
Entities, migrations and repositories for tasks, projects, areas, contexts, checklist items (SPEC.md §5.2).

## Acceptance criteria
- [ ] Flyway migration(s) in `db/migration/gtd/` for task, project, area, context, task_context, checklist_item with the fields of SPEC.md §5.2, FKs and indexes for list/project/due queries
- [ ] Enums: list (INBOX|NEXT|WAITING|SOMEDAY|SCHEDULED), energy, creativity, project status, recurrence mode
- [ ] `ItemResolver`s for TASK and PROJECT registered (deep links `/tasks/{id}`, `/projects/{id}`)
- [ ] Repository tests on Postgres: save/load all fields, soft delete excluded; `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
