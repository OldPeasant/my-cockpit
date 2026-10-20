---
title: Projects, areas, contexts API and next-step rules
status: todo
owner:
deps: [gtd-tasks-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd,server/src/test/kotlin/ch/sonensei/cockpit/gtd,server/src/main/resources/db/migration/gtd]
prio: 1
---
## Goal
Projects with next step, areas and contexts (SPEC.md §5.1–5.2).

## Acceptance criteria
- [ ] CRUD for projects (status, area, dueDate, reviewDate), areas (ordered), contexts (ordered, color); ordered tasks within a project (`PUT …/order`)
- [ ] `POST /api/v1/projects/{id}/next-step {taskId}`; `stalled` flag = ACTIVE project without an open next task, exposed in list/detail; completing a project's next task returns `needsNextStep: true` in the complete response
- [ ] Search indexing, ChangeEvents, trash for projects
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*'` passes (Postgres via Testcontainers where persistence is involved) incl. stalled detection and the next-step prompt flag
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
