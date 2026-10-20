# Code Review

Date: 2026-10-20

Task: gtd-projects-api - Projects, areas, contexts API and next-step rules (review round 2, on the final code after fixes e2deb13)

Scope: `git diff c8f7715e781047aa1fd88c14ddd4ada82d9f75dc..6ef305614d518af3bdd4b473f097880a92efef92` (committed changes only; working tree clean). The range contains the task commits 96e52e9, 295d887, 9e00030, e2deb13 (review fixes), 00a09db (regenerated openapi.yaml and web client), merged via ad53d18 and 46acd7b, plus 6ef3056 (edge-case tests, test report round 2). Generated files (`api/openapi.yaml`, `web/src/app/api/generated/`) were checked for the changed schemas only.

Requirements:
- tasks/gtd-projects-api.md (acceptance criteria 1-5 and the recorded assumptions in Notes)
- SPEC.md §4.2 (UUIDv7, ETag/If-Match, soft delete/Trash, cursor pagination, Problem Details), §4.3/§4.4 (item resolver, search), §5.1, §5.2 (stalled and next-step rules), §5.4, §9.1 (SSE change events refresh stores), §10.2 and §14 (backup coverage)
- docs/ARCHITECTURE.md conventions (module packages, services/events), AGENTS.md project notes
- Round 1: reviews/gtd-projects-api/review.md (previous version), reviews/gtd-projects-api/fixes.md; reviews/gtd-projects-api/tests.md (round 2)

Git range:
- Branch: main
- HEAD: 6ef305614d518af3bdd4b473f097880a92efef92
- Base/start: c8f7715e781047aa1fd88c14ddd4ada82d9f75dc
- Commits reviewed: 8 (96e52e9, 295d887, 9e00030, e2deb13, 00a09db, ad53d18, 46acd7b, 6ef3056)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Commands run

- `git log --graph --oneline`, `git diff --stat c8f7715..6ef3056`, `git diff` of the changed existing files (TaskController, TaskDtos, TaskService, GtdRepositories); full read of ProjectController, ProjectDtos, PatchBodies, ProblemResponses, AreaContextControllers, ProjectService, ProjectCommands, AreaService, ContextService, ProjectTrashContributor, and the relevant parts of TaskService, TaskTrashContributor, TrashService and the gtd migration (FKs).
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest'` -> BUILD SUCCESSFUL; JUnit XML: 156 gtd tests (AreaContextWebTests 10, ProjectWebTests 18, ProjectEdgeCaseTests 37, TaskWebTests 21, TaskEdgeCaseTests 24, GtdRepositoryTests 22, GtdItemResolverTests 8, TaskPositionAndCursorTests 8, ChecklistWebTests 3, ChecklistEdgeCaseTests 5) + OpenApiContractTest 1, 0 failures, 0 errors, 0 skipped; `git status` clean afterwards (openapi.yaml unchanged without the flag).
- `python tasks.py verify gtd-projects-api` -> all 5 criteria still unrecorded (expected at this stage; the parent records evidence before `done`).

## Round 1 findings: verification

- Round-1 CR-1 (purge detaches tasks without version bump/event): resolved. `ProjectTrashContributor.purge` (lines 45-57) loads every task of the project, including trashed ones, and sets `projectId = null` in code. Live tasks move to the end of their list and get `TASK UPDATED`, and versions bump once (the position query runs before the entity changes). Covered by `ProjectWebTests` and 3 new `ProjectEdgeCaseTests`.
- Round-1 CR-2 (area PATCH ignored null/unknown fields): resolved. `AreaController.update` (AreaContextControllers.kt:87-96) now uses the shared `parsePatch` with both fields not nullable. Tests cover null, unknown and wrongly typed values, and a non-object body.
- Round-1 CR-3 (next-step body without `taskId` cleared the next step): resolved. `ProjectController.setNextStep` (lines 162-177) rejects unknown keys and a missing `taskId` key with 400, and keeps explicit `null` as "clear". The schema marks `taskId` required and nullable, and the generated TS model matches (`taskId: (string | null)`).

## What I checked

- AC1: project CRUD (status with completedAt bookkeeping, areaId validated against existing areas, dueDate, reviewDate), strict PATCH for projects/areas/contexts, area and context ordering (full permutation via `PUT /order`, positions via PATCH, default position at the end), context name trim and case-insensitive uniqueness (409, also on the unique-index race), color validation, `PUT /projects/{id}/order` semantics as in the assumption.
- AC2: next-step validation (open, non-deleted task of this project, otherwise 400 `taskId`), If-Match optional. `stalled` is computed the same way in memory (`ProjectService.views`) and in SQL (the `stalled` subquery for `?stalled=`), and both match the recorded assumption. `needsNextStep` uses `findActiveById` and status ACTIVE. `CompletedTaskResponse` is flat (`@JsonUnwrapped`), and the OpenAPI schema keeps the TaskResponse required fields plus `needsNextStep`.
- AC3: search upsert on create, update, restore and area-delete, and removal on soft delete. ChangeEvents for project/area/context CRUD, task reorders and purge detaches (`PROJECT DELETED`/`UPDATED` through TrashService). `ProjectTrashContributor` is registered for `ItemType.PROJECT`. A project resolver already exists (GtdItemResolvers). Backup is out of scope here (separate task `backup-contrib-gtd`).
- AC4/AC5: re-run above, pass.
- §4.2: PATCH/DELETE require If-Match (428/412), responses carry ETags, errors are Problem Details. No new migration was needed: the schema came from gtd-model, and FKs `area_id`/`project_id` are `on delete set null` but are now also handled in code.
- Architecture: code stays in `gtd.{api,domain,infra}`. PATCH parsing is shared in `PatchBodies.kt` without changing task PATCH behaviour. There are no cross-module repository accesses and no dependency cycle (AreaService -> ProjectService -> AreaRepository).
- Security: endpoints sit under the authenticated `/api/v1`, with no new logging of content and no secrets in the diff.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A project's `stalled` flag changes without a PROJECT change event**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/TaskService.kt:137` (also the task delete/move/PATCH paths through `modify`, and `TaskTrashContributor.restore`)
    - Category: Integration
    - Problem: `stalled` is derived from the state of the next-step task. Completing, uncompleting, trashing, restoring or moving that task (to another project) flips the project's `stalled` flag. Only a `TASK` ChangeEvent is published, never `PROJECT UPDATED`. `TaskService.complete` even computes `needsNextStep` (the project has just become stalled) but publishes nothing for the project. The test report notes this as an observation, but no assumption in the task records it.
    - Why it matters: SPEC §9.1 says the web client refreshes feature stores from SSE change events, and §5.3/§8.7 show stalled projects in the project list and dashboard. A project store that listens only to `PROJECT` events shows a stale `stalled` flag after the user completes or trashes the next step elsewhere (for example on Android).
    - Suggested fix: publish `PROJECT UPDATED` for the task's project when a change affects its next step (at least on complete with `needsNextStep`, uncomplete, delete/restore, or a project change of the next-step task). Alternatively, record an assumption (`tasks.py assume`) that clients must refresh projects on `TASK` events, so the web/Android project stores implement that.

- [ ] **CR-2 | Unused `PatchBody.contains` operator**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/PatchBodies.kt:17`
    - Category: Quality
    - Problem: `operator fun contains(name: String)` has no callers: no `"x" in patch` exists anywhere in `server/src`, and the test report lists it as uncovered for that reason.
    - Why it matters: dead code in a new shared helper suggests an API that nothing relies on.
    - Suggested fix: remove it, or use it where controllers check presence today.

## Review conclusion

No material issues found. All acceptance criteria are implemented and covered by passing tests on Postgres via Testcontainers, and the three round-1 findings are verified as resolved. The two P3 items are optional within this task. CR-1 needs at least an assumption or a follow-up decision. Evidence for criteria 1-5 still has to be recorded with `tasks.py verify` by the parent worker.
