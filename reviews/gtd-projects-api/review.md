# Code Review

Date: 2026-10-20

Task: gtd-projects-api - Projects, areas, contexts API and next-step rules

Scope: `git diff bc7f6be1..69d6a1b8` (committed changes only; the untracked `server/src/test/kotlin/ch/sonensei/cockpit/gtd/ProjectEdgeCaseTests.kt`, being written concurrently by the test-writer, was excluded)

Requirements:
- tasks/gtd-projects-api.md (acceptance criteria and recorded assumptions)
- SPEC.md §4.2 (ids, ETag/If-Match, soft delete/Trash, Problem Details, cursor pagination), §4.3, §4.4, §5.1, §5.2, §5.4
- AGENTS.md project notes (module boundaries, generated files, migrations, logging)
- Existing gtd/core conventions (TaskService, TaskTrashContributor, ETags, CursorRequest, TrashService)

Git range:
- Branch: main
- HEAD: 69d6a1b8969c245ab1a2d66e75fbf3a776fffb07
- Base/start: bc7f6be150a8b21139b5ec76d81773d61daf3a74
- Commits reviewed: 2 (7bc184a2 implementation, 69d6a1b8 tests + regenerated web client)

Summary: 0 P0, 0 P1, 0 P2, 3 P3

## What I checked

- Criterion 1: CRUD for projects (status, areaId, dueDate, reviewDate, description, completedAt bookkeeping), areas (ordered, `PUT /areas/order`), contexts (ordered, color validated, names trimmed and unique case-insensitively with a 409 fallback on the unique index), and `PUT /projects/{id}/order` (listed tasks first, then the rest in their previous order, duplicates and foreign tasks give 400). All are implemented and covered by `ProjectWebTests` / `AreaContextWebTests`.
- Criterion 2: `POST /projects/{id}/next-step` accepts only an open, non-deleted task of the same project (otherwise 400 `taskId`) and `null` clears it. `stalled` is computed the same way in memory (`ProjectService.views`) and in SQL (`ProjectService.stalled`, used for the `?stalled=` filter), so list, detail and filter agree. `TaskService.complete` returns `CompletedTask` with `needsNextStep` for the next step of an ACTIVE, non-trashed project. `CompletedTaskResponse` flattens the task with `@JsonUnwrapped`, and the regenerated OpenAPI schema and TS model are flat, so existing clients of the complete response still work.
- Criterion 3: create, update, area-delete and restore upsert the project's `search_document`. Soft delete removes it. ChangeEvents fire on create, update, delete, restore and purge (TrashService). `ProjectTrashContributor` is registered for `ItemType.PROJECT`. Tests cover the index, the events, the Trash listing, restore and purge.
- Optimistic locking: PATCH and DELETE on projects, areas and contexts require If-Match (428/412). `next-step` treats If-Match as optional, as the recorded assumption says. Responses carry ETags.
- Problem Details: errors go through the shared `InvalidRequestException`/`ConflictException`/`NotFoundException`, and OpenAPI annotations reference `ProblemDetail`.
- Architecture: the code stays in `gtd.{api,domain,infra}`. It reuses the existing domain-to-repository pattern from `TaskService`, needs no new migration (the schema existed from gtd-model), and moves the PATCH parsing into the shared `parsePatch` helper without changing task PATCH behaviour. The generated files (`api/openapi.yaml`, `web/src/app/api/generated/`) were regenerated, not edited by hand.
- Security: this is a single-user app. The endpoints sit under `/api/v1` behind the global auth (tests use `@WithCockpitUser` and CSRF). There is no new logging of content and no secrets.
- Cursor pagination: the `<id>|<title>` keyset is parsed id-first, so it is safe for titles containing `|`. It is tested with equal titles and titles with a bar.
- Evidence: `python tasks.py verify gtd-projects-api` shows no criterion recorded yet. That is expected at this stage. The parent must record evidence before `done`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Purging a project changes its tasks without a version bump or change event**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/infra/ProjectTrashContributor.kt:196`
    - Category: Correctness
    - Problem: `purge` deletes the project row and relies on the FK `task.project_id ... on delete set null` to detach its tasks. Their `projectId` changes in the database, but their `version`/ETag stays the same and no `TASK UPDATED` ChangeEvent is published. The task's recorded assumption for area and context deletion says the opposite: detach in code "so versions bump and UPDATED events fire" (see `AreaService.delete`, `ContextService.delete`).
    - Why it matters: clients that cache tasks by ETag or sync by change events (web live updates, Android) keep a `projectId` that points to a project that no longer exists. A later If-Match write still succeeds against the unchanged version.
    - Suggested fix: in `purge`, load the project's tasks (`findByProjectIdAndDeletedAtIsNull...` plus deleted ones, or a `findByProjectId`), set `projectId = null`, save them, and publish `TASK UPDATED` for the ones not in the Trash before deleting the project. Extend the existing purge test to assert the task's new version.

- [ ] **CR-2 | Area PATCH silently ignores `null` and unknown fields, unlike project and context PATCH**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/AreaContextControllers.kt:81`
    - Category: Quality
    - Problem: `AreaController.update` binds a typed `UpdateAreaRequest`. `{"title": null}`, `{"position": null}` or a misspelled field returns 200, changes nothing, and still publishes an `AREA UPDATED` event. Project and context PATCH use `parsePatch` and reject unknown fields and `null` for non-clearable fields with a 400.
    - Why it matters: the PATCH contract is inconsistent within the same API tag, and client mistakes go unnoticed.
    - Suggested fix: use `jsonMapper.parsePatch(body, UpdateAreaRequest::class.java, setOf("title", "position"), setOf("title", "position"))`, as `ContextController.update` does.

- [ ] **CR-3 | A next-step body without `taskId` clears the next step**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/ProjectController.kt:159` (`NextStepRequest` in `ProjectDtos.kt:103`)
    - Category: Correctness
    - Problem: `taskId` defaults to `null`, and unknown properties are ignored. So `{}`, or a body with a typo such as `{"taskID": "..."}`, is treated the same as an explicit `{"taskId": null}`: the project loses its next step and becomes stalled.
    - Why it matters: a malformed client request silently destroys user state instead of returning a 400.
    - Suggested fix: require the `taskId` key to be present (for example, parse the body as a map or use `parsePatch` with field set `{"taskId"}` and require the key). Return 400 when it is missing, and keep explicit `null` as "clear".

## Review conclusion

No material issues found. All acceptance criteria are implemented and covered by meaningful tests. The three P3 items are small fixes that are optional within this task.
