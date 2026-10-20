---
title: GTD data model
status: in_progress
owner: dell26/agent-3
deps: [p0-db-conventions,p0-items-links]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd,server/src/test/kotlin/ch/sonensei/cockpit/gtd,server/src/main/resources/db/migration/gtd]
prio: 1
lease_until: 2026-10-20T13:51:14Z
---
## Goal
Entities, migrations and repositories for tasks, projects, areas, contexts, checklist items (SPEC.md §5.2).

## Acceptance criteria
- [x] Flyway migration(s) in `db/migration/gtd/` for task, project, area, context, task_context, checklist_item with the fields of SPEC.md §5.2, FKs and indexes for list/project/due queries
  - verified (2026-10-20 15:27+0200 by dell26/agent-3): gtd/V202610201311__gtd_model.sql creates task, project, area, context, task_context, checklist_item with SPEC §5.2 fields, FKs and indexes; ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*' (Testcontainers postgres:16) -> GtdRepositoryTests 22 passed incl. FK/cascade/check and index-existence tests
- [x] Enums: list (INBOX|NEXT|WAITING|SOMEDAY|SCHEDULED), energy, creativity, project status, recurrence mode
  - verified (2026-10-20 15:27+0200 by dell26/agent-3): gtd/domain/GtdEnums.kt TaskList, Energy, Creativity, ProjectStatus, RecurrenceMode + DB check constraints; enum round-trip and invalid-value tests in GtdRepositoryTests -> passed
- [x] `ItemResolver`s for TASK and PROJECT registered (deep links `/tasks/{id}`, `/projects/{id}`)
  - verified (2026-10-20 15:27+0200 by dell26/agent-3): TaskItemResolver/ProjectItemResolver beans; GtdItemResolverTests (registry finds both, deep links /tasks/{id} and /projects/{id}, soft-deleted not resolved) against Testcontainers Postgres -> 8 passed
- [x] Repository tests on Postgres: save/load all fields, soft delete excluded; `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*'` passes (Postgres via Testcontainers where persistence is involved)
  - verified (2026-10-20 15:27+0200 by dell26/agent-3): cd server && ./gradlew cleanTest test --tests 'ch.sonensei.cockpit.gtd.*' with Testcontainers postgres:16-alpine -> BUILD SUCCESSFUL, 30 tests, 0 failures (save/load all fields, soft delete excluded)

## Notes
- ASSUMPTION (2026-10-20 15:16+0200 by dell26/agent-3): waiting_for_contact_id has no FK: contacts live in another module (and are not built yet), like link item ids
- ASSUMPTION (2026-10-20 15:16+0200 by dell26/agent-3): Projects are soft-deletable (deleted_at) like tasks; areas, contexts and checklist items are hard-deleted (FKs set null / cascade). Context names are unique case-insensitively
- ASSUMPTION (2026-10-20 15:16+0200 by dell26/agent-3): Task references project/contexts by id (UUID columns + task_context element collection), not JPA associations; position is an integer
