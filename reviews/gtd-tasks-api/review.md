# Code Review

Date: 2026-10-20

Task: gtd-tasks-api - Tasks API (re-review on the final code)

Scope: `git diff a67434529b8fa4cd07205312c877dfda021e9b5d..HEAD -- server api web/src/app/api/generated`. Task commits: f98c23a, c438ac2 (implementation, API tests), 9d13a3c (fixes for CR-1..CR-4 of the first review), 4b839e5 (merge of main; `api/openapi.yaml` and the web client regenerated), e4a9c73 (fixer report), fdb5e3f (test-writer edge-case tests, tests.md). Reviewed revision: fdb5e3fe0608e4f4f9d167ff895514c2b2321104. Committed code only. The working tree was clean.

Requirements:
- tasks/gtd-tasks-api.md: acceptance criteria 1-6 and the 4 ASSUMPTION notes
- SPEC.md §4.2 (API conventions, ETag/If-Match, Problem Details, UUIDv7), §5.1, §5.2, §5.4 (projects have an ordered task list)
- docs/ARCHITECTURE.md (server module and package conventions), docs/QUALITY.md, AGENTS.md project notes
- Previous reports: reviews/gtd-tasks-api/review.md (first review, revision 3ac3056), fixes.md, tests.md

Git range:
- Branch: main
- HEAD: fdb5e3fe0608e4f4f9d167ff895514c2b2321104
- Base/start: a67434529b8fa4cd07205312c877dfda021e9b5d
- Commits reviewed: 6 (f98c23a, c438ac2, 9d13a3c, 4b839e5, e4a9c73, fdb5e3f)

Files read in full: `TaskService.kt`, `TaskController.kt`, `TaskDtos.kt`, `ChecklistService.kt`, `ChecklistController.kt`, `TaskCommands.kt`, `TaskTrashContributor.kt`. Also the diffs of `Task.kt`, `GtdEnums.kt`, `GtdItemResolvers.kt` and `GtdRepositories.kt`, the `TaskPatchRequest` and `maxMinutes` parts of `api/openapi.yaml`, the generated `task-patch-request.ts` and `list-tasks.ts`, the CR-1/CR-4 tests in `TaskWebTests.kt`, and the repositioning tests in `TaskEdgeCaseTests.kt`.

Commands run:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest'` (Postgres via Testcontainers, without `UPDATE_OPENAPI`) -> BUILD SUCCESSFUL. Fresh JUnit XML (17:21): TaskWebTests 21, TaskEdgeCaseTests 22, ChecklistWebTests 3, ChecklistEdgeCaseTests 5, GtdRepositoryTests 22, GtdItemResolverTests 8, OpenApiContractTest 1; 0 failures, 0 errors, 0 skipped.

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## Status of the previous findings

- CR-1 (a task in a trashed project could not be changed): **resolved.** `TaskService.modify` now calls `validate(task, checkProject = task.projectId != oldProject)` (line 231), and `create` checks the project (line 106). The test `a task whose project is in the Trash can still be changed, completed and moved` covers PATCH, complete, uncomplete and move, plus the 400 when a task is put into a trashed or unknown project.
- CR-2 (`maxMinutes` documented as a string): **resolved.** `TaskController.kt:79` uses `Schema(type = "integer", format = "int32", minimum = "0")`. The generated `list-tasks.ts` has `maxMinutes?: number`, and `OpenApiContractTest` passes without the flag.
- CR-3 (PATCH schema did not allow `null`): **resolved.** The clearable `TaskPatchRequest` properties have `types = [..., "null"]`. `TaskPatchNullEnums` adds `null` to the enums of energy, creativity and recurrenceMode. In the generated model they are `(... | null)`, and `title`, `position` and `contextIds` stay non-nullable as documented.
- CR-4 (create and PATCH could break the list rules): **resolved.** `validate` (lines 286-296) enforces SCHEDULED/scheduledDate and WAITING/waiting-for/waitingSince on every path. The test `create and PATCH enforce the list rules of move` covers create and PATCH, and checks that a rejected PATCH leaves the version unchanged.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-5 | A list change puts a project task at the end of its project, so the user's project order changes**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/TaskService.kt:232` (with `nextPosition` at 307-311)
    - Category: Correctness
    - Problem: `modify` gives the task a new position when `task.list != oldList || task.projectId != oldProject`. For a task with a project, `nextPosition` returns the end of the *project*, because `position` is the order within the project (TaskResponse doc, SPEC.md data model "position (for ordering within project/list)"). So a `/move` that only changes the list (e.g. NEXT -> WAITING of step 2 of 5) moves the task to the end of the project's ordered task list, although the project did not change. tests.md lists this as an observation. No test covers this case: `move to another list puts the task at the end of that list` only uses tasks without a project.
    - Why it matters: SPEC.md §5.2 says "a project has an ordered task list" with drag reorder in the project detail. A normal processing step (delegating a step, scheduling it) silently changes that order. Project detail and next-step selection (gtd-projects-api, web-gtd) depend on this order. The recorded assumption ("a task changing list/project goes to the end of the new list/project") does not decide this case: the task's project is not new.
    - Suggested fix: Reposition only when the project changed, or when the list changed and the task has no project (`task.projectId != oldProject || (task.list != oldList && task.projectId == null)`). Add a test that moves a project task to another list and checks that its position in the project stays the same. If the current behavior is intended, record it explicitly with `tasks.py assume`.

## P3 Low

- [ ] **CR-6 | Two concurrent idempotent creates with the same client id give a 500**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/TaskService.kt:94-109`
    - Category: Correctness
    - Problem: An idempotent create first runs `tasks.findById(id)` and then inserts (`BaseEntity` implements `Persistable`, so `saveAndFlush` runs a plain INSERT). When two requests with the same UUIDv7 overlap, the second INSERT fails on the primary key with a `DataIntegrityViolationException`. Neither `TaskService` nor `ProblemDetailsAdvice` handles it; the advice maps only `OptimisticLockingFailureException` to 409. The client gets a 500 instead of the documented 200 with the existing task.
    - Why it matters: The client id exists for offline capture. Such clients retry, e.g. after a timeout while the first request is still running, so this case is realistic. Data is not corrupted (the first create wins), but the client sees a server error for a request that is idempotent by contract.
    - Suggested fix: Catch `DataIntegrityViolationException` for a client-supplied id and answer like the idempotent path (reload, then 200, or 409 if the task is in the Trash), as `LinkService` and `SetupService` already do for their unique keys. An alternative is an insert with `ON CONFLICT DO NOTHING`. A test is optional, since such a race is hard to make deterministic.

## Notes (not findings)

- Criteria 1-4 are implemented as specified. Filters, cursor paging (open and Logbook), CRUD with ETag/If-Match (412/428), complete/uncomplete/move with validation, checklist CRUD with item ETags, contexts by id list, search indexing, ChangeEvents (checklist changes publish TASK UPDATED), soft delete and the TrashContributor (restore re-indexes; TrashService publishes the event) are all covered by tests against Postgres. Criteria 5 and 6 were re-run by this review (see Commands run).
- Assumption #3 (`If-Match` optional on complete, uncomplete and move) still deviates from SPEC.md §4.2 ("updates require If-Match"). It is recorded, a sent If-Match is checked, and `@Version` still prevents lost updates. A human should confirm it.
- The last Notes line of tasks/gtd-tasks-api.md names the fix commit as `bb6d7d0`, but the fixes are in `9d13a3c` (most likely rebased). This is task metadata, not code. The parent may want to cite the correct hash in quality.json.
- Not reported because it is minor and has no concrete impact: an empty PATCH body `{}` re-indexes the task and publishes `UPDATED` without changing the version. The same happens for a checklist PATCH without fields.

## Review conclusion

Material issues found; fix before completing the task. CR-1..CR-4 are resolved. CR-5 (P2) should be fixed, or accepted as an explicit assumption by the parent worker. CR-6 (P3) is optional.
