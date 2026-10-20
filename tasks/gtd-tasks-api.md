---
title: Tasks API
status: in_progress
owner: dell26/agent-6
deps: [gtd-model,p0-search-core,p0-trash-core,p0-events-sse]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd,server/src/test/kotlin/ch/sonensei/cockpit/gtd,server/src/main/resources/db/migration/gtd]
prio: 1
lease_until: 2026-10-20T14:10:16Z
---
## Goal
CRUD and workflow endpoints for tasks (SPEC.md §5.4).

## Acceptance criteria
- [ ] `GET /api/v1/tasks` with filters list, projectId, context, energy, creativity, maxMinutes, due (overdue|today|week), completed, cursor pagination
- [ ] `POST`, `GET/PATCH/DELETE /api/v1/tasks/{id}` with ETag/If-Match; `POST …/complete`, `…/uncomplete`, `…/move {list, projectId?, scheduledDate?, tickler?, waitingFor…}` with validation (e.g. SCHEDULED needs a date, WAITING sets waitingSince)
- [ ] Checklist items CRUD under `/api/v1/tasks/{id}/checklist`; contexts set by id list
- [ ] Every change indexes the task for search, publishes a ChangeEvent; delete is soft and the task shows in the trash (TrashContributor)
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*'` passes (Postgres via Testcontainers where persistence is involved) incl. API tests for filters, move validation, 412 on stale ETag
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
