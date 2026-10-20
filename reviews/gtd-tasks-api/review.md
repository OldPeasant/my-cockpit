# Code Review

Date: 2026-10-20

Task: gtd-tasks-api - Tasks API (re-review of the final code, revision eccf1aab)

Scope: `git diff dc7514b1 54f95702` (task implementation, squashed, including the CR-5 and CR-6 fixes) plus commit eccf1aab (new `TaskPositionAndCursorTests.kt`, tests.md). f21a7fae (merge of main) brings only files-storage code and does not touch `gtd`, `api/openapi.yaml` or `web/src/app/api/generated` (checked with `git diff --stat 54f95702 eccf1aab`). Committed code only. The working tree was clean.

Requirements:
- tasks/gtd-tasks-api.md: acceptance criteria 1-6 and the 4 ASSUMPTION notes (two confirmed by a human)
- SPEC.md §4.2 (ETag/If-Match, Problem Details, UUIDv7), §5.2/§5.4 (projects have an ordered task list), data model `position (for ordering within project/list)`, §14
- docs/ARCHITECTURE.md, docs/QUALITY.md, AGENTS.md project notes, api/README.md
- Previous reports: reviews/gtd-tasks-api/review.md (stale, revision fdb5e3f), fixes.md, tests.md

Git range:
- Branch: main
- HEAD: eccf1aab8b6f8b5ed77aff280153c4f286152434
- Base/start: dc7514b1
- Commits reviewed: 2 task commits (54f95702, eccf1aab), plus merge f21a7fae checked to leave gtd unchanged

Files read in full: `TaskService.kt`, `TaskController.kt`, `TaskDtos.kt`, `TaskCommands.kt`, `ChecklistService.kt`, `TaskTrashContributor.kt`, `GtdRepositories.kt`, `TaskPositionAndCursorTests.kt`; the race test and test index of `TaskEdgeCaseTests.kt`; the If-Match handling of `ChecklistController.kt`.

Checks run by this review:
- Web client drift: `npx --no-install ng-openapi-gen --input ../api/openapi.yaml --output ../.tmp/review-gen/generated` (same options as `web/ng-openapi-gen.json`), then `diff -r` against `web/src/app/api/generated` -> identical. The committed web client matches the committed `api/openapi.yaml`.
- Gradle tests could not be run: the sandbox denied the command. The latest JUnit XML in `server/build/test-results/test` is from 17:56 (+0200), shortly before commit eccf1aab (18:00), and the working tree is clean. It shows 0 failures, 0 errors and 0 skipped for TaskWebTests (21), TaskEdgeCaseTests (22), TaskPositionAndCursorTests (8), ChecklistWebTests (3), ChecklistEdgeCaseTests (5), GtdRepositoryTests (24), GtdItemResolverTests (8) and OpenApiContractTest (1). These are the test-writer's results, not results this review produced.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Status of the previous findings

- CR-5 (a list-only move put a project task at the end of its project): **resolved.** `TaskService.modify` (lines 244-246) now repositions only when `task.projectId != oldProject || (task.projectId == null && task.list != oldList)`. Tests: `move to another list keeps a project task at its position in the project` (TaskEdgeCaseTests:398) and `move into another project ... naming its own project keeps its position` (TaskPositionAndCursorTests:140). `PATCH projectId null` (project removed) moves the task to the end of the tasks without a project in its list (TaskPositionAndCursorTests:167). `uncomplete` and `complete` never reposition.
- CR-6 (two concurrent idempotent creates gave a 500): **resolved.** `TaskController.create` (lines 140-147) catches `DataIntegrityViolationException` and answers with `tasks.existingCreate(id)`: 200 with the existing task, or 409 if it is in the Trash. Any other integrity error, or a request without an id, is rethrown. This is correct here because the service transaction has already rolled back when the exception reaches the controller, `JpaTransactionManager` clears the EntityManager on rollback, and `spring.jpa.open-in-view=false` (application.properties:10). So `existingCreate` reads in a fresh transaction. Postgres only raises the PK violation after the competing insert has committed, so the re-read finds the row. Both outcomes have deterministic tests that use a real blocking insert: TaskEdgeCaseTests:203 (200 with ETag "0") and TaskPositionAndCursorTests:194 (409).

## Test-writer note: the max-position query counts the moving task

Confirmed, and it does not matter. `nextPosition` runs a JPQL `max(position)` query after the change has been applied to the managed entity. Hibernate auto-flushes before the query, so the moving task already counts in its new list or project with its old position. When its old position is at least the current maximum there, the result is `old + 1` instead of `max + 1`. For example, an Inbox task at position 7 moved to an empty NEXT list gets position 8, not 0. The task still ends up last, which is the required behavior (assumption #2). Positions are only sort keys: deletes already leave gaps, and nothing in SPEC.md or the API contract promises dense positions. No finding.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-7 | Two position scales in one list, and the CR-5 positioning rule is not recorded as an assumption**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/TaskService.kt:244-246` and `:69` (list sort)
    - Category: Correctness
    - Problem: A project task's `position` is its order within the project, and a task without a project has a position within its list (TaskResponse doc, `nextPosition`). After the CR-5 fix, project tasks keep their project position when they change list. `GET /tasks?list=NEXT` sorts by `position, id` across both kinds of task. So a project's step 0 and a loose NEXT task at position 0 are ordered by id, and the steps of several projects interleave with the loose tasks. The task notes only record "a task changing list/project goes to the end of the new list/project". They do not record that a project task changing only its list keeps its project position, or what order a list view has. The previous review asked for an assumption if this is intended.
    - Why it matters: SPEC.md's `position (for ordering within project/list)` leaves this open. The order of the Next Actions and other list views (web-gtd, android) depends on this decision, so a human should see it.
    - Suggested fix: No code change is needed for this task. Record the decision with `python tasks.py assume gtd-tasks-api "..."`: position is per project for project tasks and per list for other tasks, a list-only move keeps the project position, and list views ordered by position interleave both. Clients that need a meaningful list order then know to sort or group it themselves, or a later task can define it.

## Notes (not findings)

- Criteria 1-4 are implemented as specified and covered by tests against Postgres: filters, both cursor orders (now also with equal sort keys), CRUD with ETag/If-Match (412/428), workflow actions with validation, checklist CRUD with item ETags, contexts as an id list (duplicates stored once), search indexing, ChangeEvents, soft delete and the TrashContributor.
- Criterion 6: the committed `api/openapi.yaml` is unchanged since 54f95702, and the merge added no controllers. The web client matches it (see above). OpenApiContractTest passed in the latest results.
- Assumption #3 (If-Match optional on complete/uncomplete/move) deviates from SPEC.md §4.2. It has since been confirmed by a human, so it is not reported.
- A `move` with invalid fields returns 400 before the task is loaded, so an unknown id with an invalid body gets 400 instead of 404. This is harmless and not reported.

## Review conclusion

No material issues found. CR-5 and CR-6 are correctly resolved and tested. CR-7 (P3) only asks the parent worker to record an assumption; it needs no code change.
