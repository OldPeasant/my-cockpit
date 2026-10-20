---
title: Projects, areas, contexts API and next-step rules
status: in_progress
owner: dell26/agent-5
deps: [gtd-tasks-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd,server/src/test/kotlin/ch/sonensei/cockpit/gtd,server/src/main/resources/db/migration/gtd]
prio: 1
lease_until: 2026-10-20T17:02:00Z
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
- ASSUMPTION (2026-10-20 18:25+0200 by dell26/agent-5): Stalled = ACTIVE project whose nextTaskId is not an open task of the project (unset, done, in Trash, or moved to another project); projects in other statuses are never stalled. Open tasks without a designated next step do not count.
- ASSUMPTION (2026-10-20 18:25+0200 by dell26/agent-5): needsNextStep in the complete response is true iff the completed task is the nextTaskId of its ACTIVE (not trashed) project; nextTaskId keeps pointing to the done task (project shows as stalled) so uncomplete restores it.
- ASSUMPTION (2026-10-20 18:25+0200 by dell26/agent-5): POST /tasks/{id}/complete now returns CompletedTaskResponse = all TaskResponse fields (flattened via @JsonUnwrapped) + needsNextStep, so clients reading task fields keep working.
- ASSUMPTION (2026-10-20 18:25+0200 by dell26/agent-5): POST /projects/{id}/next-step {taskId|null}: taskId must be an open, non-deleted task of this project (else 400 taskId); null clears; If-Match optional like the task workflow actions.
- ASSUMPTION (2026-10-20 18:25+0200 by dell26/agent-5): PUT /projects/{id}/order {taskIds}: listed tasks get positions 0..n-1 in that order, the project's other tasks (open and done) follow in previous order; changes task versions, not the project; returns all project tasks.
- ASSUMPTION (2026-10-20 18:25+0200 by dell26/agent-5): Areas and contexts: unpaginated {items} lists (small user-defined sets); full reorder via PUT /areas/order and /contexts/order {ids} (every id once); positions also settable via PATCH. Change events use type strings AREA/CONTEXT (not ItemTypes).
- ASSUMPTION (2026-10-20 18:25+0200 by dell26/agent-5): Deleting an area or context is a hard delete (no Trash; not user content per SPEC 4.2): projects lose areaId / tasks lose the context in code so versions bump and UPDATED events fire. Context names trimmed, unique case-insensitively (409); color must be #rgb or #rrggbb.
- ASSUMPTION (2026-10-20 18:25+0200 by dell26/agent-5): Project list ordered by title then id; no client-chosen id / idempotent create for projects (only tasks need offline capture).
