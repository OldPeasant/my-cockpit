---
title: Tasks API
status: in_progress
owner: dell26/agent-6
deps: [gtd-model,p0-search-core,p0-trash-core,p0-events-sse]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd,server/src/test/kotlin/ch/sonensei/cockpit/gtd,server/src/main/resources/db/migration/gtd]
prio: 1
lease_until: 2026-10-20T15:30:49Z
extended: dell26/agent-6
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
- ASSUMPTION (2026-10-20 16:06+0200 by dell26/agent-6): GET /tasks: 'context' filter takes a context id (UUID), not a name; due windows are relative to today in the user's time zone: overdue = before today, today = exactly today, week = today..today+6; maxMinutes only matches tasks with an estimate; completed defaults to false (open tasks), completed=true gives the Logbook ordered by completedAt desc; tickler-hidden tasks are NOT excluded here (left to gtd-tickler)
- ASSUMPTION (2026-10-20 16:06+0200 by dell26/agent-6): move: absent projectId keeps the project (use PATCH projectId:null to remove); scheduledDate only allowed with SCHEDULED and cleared on leaving it; waiting-for fields only allowed with WAITING and cleared (with waitingSince) on leaving it; ticklerDate must be in the future and an omitted ticklerDate clears it; a task changing list/project goes to the end of the new list/project
- ASSUMPTION (2026-10-20 16:06+0200 by dell26/agent-6): PATCH /tasks/{id} rejects 'list' and other unknown fields (list changes go through /move, completion through /complete); complete/uncomplete/move check If-Match only when sent; checklist items have their own ETag (If-Match required for item PATCH/DELETE), checklist changes publish TASK UPDATED; TaskResponse does not embed the checklist
- ASSUMPTION (2026-10-20 16:06+0200 by dell26/agent-6): POST /tasks with a client id is idempotent: existing active task returned with 200 unchanged, an id of a task in the Trash gives 409; recurrenceRule is stored as given (validation is gtd-recurrence)
- extended by dell26/agent-6: Implementation complete incl. fixes for code-review CR-1..CR-4 (commit bb6d7d0: project check only on change, list-field invariants on create/PATCH, maxMinutes integer + nullable PATCH fields in regenerated openapi/web client, new TaskWebTests); scripts/check.sh passes. Left: run test-writer (tests.md missing) and code-reviewer again on final code, record fixer-not-required/fixes in quality.json (fixes done by parent, no fixes.md), run gtd tests + OpenApiContractTest, tasks.py verify 1-6, write quality.json, done.
