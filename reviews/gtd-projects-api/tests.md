# Test Report

Date: 2026-10-20

Task: gtd-projects-api - Projects, areas, contexts API and next-step rules (second test round, on the final code after fixes e2deb13)

Scope: `git diff c8f7715e781047aa1fd88c14ddd4ada82d9f75dc..46acd7bd765903cd1244846dbdc9a96744701a20` on branch `main`
(base and range supplied by the parent: c8f7715 is the takeover claim commit; the range contains the task's commits
96e52e9, 295d887, 9e00030, e2deb13, 00a09db merged from `origin/wip/gtd-projects-api` via ad53d18 and 46acd7b).
`git diff --stat` of the range lists only this task's files (server `gtd` package, `api/openapi.yaml`, generated web
client, `reviews/gtd-projects-api/`). Uncommitted input: the modified test file
`server/src/test/kotlin/ch/sonensei/cockpit/gtd/ProjectEdgeCaseTests.kt` (this round's additions). No uncommitted
production changes.

Requirements:
- `tasks/gtd-projects-api.md` acceptance criteria and recorded assumptions (stalled rule, needsNextStep, next-step and
  order semantics, area/context hard delete)
- `reviews/gtd-projects-api/review.md` findings CR-1..CR-3 and `fixes.md` (the fixed behavior: purge detaches tasks
  with a version bump and `TASK UPDATED`; area PATCH uses the strict PATCH parser; next-step requires the `taskId` key)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`
- Existing conventions in `server/src/test/kotlin/ch/sonensei/cockpit/gtd/` (`PostgresTestBase`, MockMvc,
  `@RecordApplicationEvents`, `MutableClock`)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/ProjectEdgeCaseTests.kt`

## Tests added or updated

Count: 8 added (all in `ProjectEdgeCaseTests`, now 37 tests). No existing test was changed, weakened or disabled.

The fixer's own tests (`ProjectWebTests`: purge with one live and one trashed task, next-step without `taskId`;
`AreaContextWebTests`: area PATCH nulls/unknown/bad values) cover the main path of each fix. The new tests close the
remaining gaps around the fixes:

- `purging a project moves its open and done tasks to the end of their own lists in project order` (CR-1): several
  live tasks in two lists (NEXT, WAITING) and a done task; after a project reorder, the purge keeps the project order
  when appending to the end of each task's own list, bumps every version by exactly 1 and announces each task exactly
  once as `UPDATED`.
- `a task trashed with its project can be restored after the project was purged, without a project` (CR-1): restore
  after purge works and the task has no dangling `projectId`.
- `purging a project that is not in the Trash or does not exist changes none of its tasks` (CR-1): `purge` returns
  false and leaves tasks, versions and events untouched.
- `next-step with a body that is not a JSON object is 400 and keeps the next step` (CR-3): `[]`, `null`, a JSON
  string and truncated JSON give 400 Problem Details (not 500), next step and version unchanged.
- `next-step body errors name every bad field, and a bad UUID names taskId` (CR-3): unknown fields and the missing
  `taskId` key are reported together; a non-UUID value is reported on `taskId`.
- `next-step body validation comes before the If-Match check and the project lookup` (CR-3): a bad body is 400 even
  with a stale If-Match; a valid body with a stale If-Match is 412; an unknown project is 404.
- `area PATCH changes only the fields sent and announces the change` (CR-2): position-only and title-only PATCH keep
  the other field, bump the ETag and publish `AREA UPDATED`.
- `area PATCH reports every unknown field at once and a body that is not an object as 400` (CR-2).

## Requirement coverage

Focused command (F): `cd server && ./gradlew cleanTest test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest'`
(Testcontainers Postgres). Full command (S): `cd server && ./gradlew cleanTest test jacocoTestReport`.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 CRUD projects (status, area, dueDate, reviewDate) | `ProjectWebTests` CRUD/PATCH/status/list tests; `ProjectEdgeCaseTests` create-status, max-length, `PATCH areaId null...`, cursor and optimistic-locking tests | F, S | Pass |
| AC1 areas (ordered), contexts (ordered, color) | `AreaContextWebTests` (10 tests incl. `area PATCH rejects nulls, unknown fields and bad values...`); `ProjectEdgeCaseTests`: context GET/PATCH, context race 409, delete cascades, new `area PATCH changes only the fields sent...`, `area PATCH reports every unknown field at once...` | F, S | Pass |
| AC1 ordered tasks in a project (`PUT .../order`) | `ProjectWebTests` `PUT order puts the listed tasks first...`, `PUT order rejects duplicates...`; `ProjectEdgeCaseTests` empty-list and Trash order tests | F, S | Pass |
| AC2 `POST /projects/{id}/next-step {taskId}` | `ProjectWebTests` `next-step designates...`, `next-step without a taskId key or with unknown fields is 400...`, `next-step rejects tasks of other projects...`; `ProjectEdgeCaseTests` version/concurrency/blank If-Match tests and the 3 new next-step body tests | F, S | Pass |
| AC2 `stalled` flag in list and detail | `ProjectWebTests` `a project is stalled when...`; `ProjectEdgeCaseTests` stalled filter/flag consistency, combined filters, restore/purge of next step, page-wide loading | F, S | Pass |
| AC2 `needsNextStep: true` on completing the next task | `ProjectWebTests` 2 tests; `ProjectEdgeCaseTests` repeated complete, uncomplete, former next step / trashed project, every non-active status | F, S | Pass |
| AC3 search indexing | `ProjectWebTests` index row on create/PATCH/delete/restore; `ProjectEdgeCaseTests` search API test, Trash contributor, rollback test | F, S | Pass |
| AC3 ChangeEvents | `ProjectWebTests`, `AreaContextWebTests` CREATED/UPDATED/DELETED; `ProjectEdgeCaseTests` restore, empty order, delete cascades, new purge and area PATCH event assertions | F, S | Pass |
| AC3 trash for projects | `ProjectWebTests` `DELETE moves the project to the Trash...`, `purging a project from the Trash keeps its tasks without a project, with a new version and a change event`; `ProjectEdgeCaseTests` Trash contributor list/restore/purge and the 3 new purge tests | F, S | Pass |
| AC4 gtd tests pass on Postgres (Testcontainers), incl. stalled detection and next-step prompt flag | all 156 tests in `ch.sonensei.cockpit.gtd.*` (10 classes) | F | Pass (156 tests, 0 failures, 0 errors, 0 skipped) |
| AC5 OpenApiContractTest passes without the flag | `ch.sonensei.cockpit.core.web.OpenApiContractTest` | F (no `UPDATE_OPENAPI`) | Pass (1 test); `api/openapi.yaml` unchanged afterwards (`git status`) |

## Focused test execution

Command:

`cd server && ./gradlew ktlintFormat ktlintCheck test --tests 'ch.sonensei.cockpit.gtd.ProjectEdgeCaseTests'`

Result:

Pass. BUILD SUCCESSFUL; ProjectEdgeCaseTests 37 tests, 0 failures. ktlint (main + test) passed.

Command:

`cd server && ./gradlew cleanTest test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest'`

Result:

Pass. BUILD SUCCESSFUL in 22s (2026-10-20T17:53:20Z-17:53:44Z). JUnit XML: AreaContextWebTests 10,
GtdItemResolverTests 8, GtdRepositoryTests 22, ChecklistWebTests 3, TaskEdgeCaseTests 24, TaskPositionAndCursorTests 8,
ChecklistEdgeCaseTests 5, ProjectWebTests 18, ProjectEdgeCaseTests 37, TaskWebTests 21 (156 gtd tests) and
OpenApiContractTest 1; 0 failures, 0 errors, 0 skipped.

## Full test execution

Command:

`cd server && ./gradlew cleanTest test jacocoTestReport`

Result:

Pass. BUILD SUCCESSFUL in 1m 54s (2026-10-20T17:53:49Z-17:55:44Z), full server suite.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` exited 0 and printed `== all checks passed` (finished 2026-10-20T17:57Z). Note: in this
environment it printed `android: SKIPPED (COCKPIT_NO_ANDROID=1)`; this task changes no Android code.

## Bugs found

None. All new and existing tests pass against the final code. The three fixes (CR-1, CR-2, CR-3) behave as described
in `fixes.md`, including the edge cases added this round.

Observation carried over from round 1 (not a defect, no requirement violated): completing, uncompleting, deleting or
restoring a project's next step changes the project's derived `stalled` flag without a `PROJECT` ChangeEvent (only a
`TASK` event). Neither the task nor SPEC requires it.

## Coverage

Status: verified (fresh full-suite JaCoCo run on the final snapshot; exit status success).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 562/565 executable changed lines covered (99.5%). Method: JaCoCo XML `line` elements
(covered = `ci > 0`) intersected with added/modified line numbers from `git diff -U0 c8f7715e..HEAD` per file; only
JaCoCo-executable lines count, removed lines excluded. Changed-branch coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `gtd-projects-api`; base
  `c8f7715e781047aa1fd88c14ddd4ada82d9f75dc` (supplied by the parent), measured HEAD
  `46acd7bd765903cd1244846dbdc9a96744701a20`; task commits 96e52e9, 295d887, 9e00030, e2deb13, 00a09db (merged via
  ad53d18, 46acd7b). File scope: `git diff --name-only c8f7715e..HEAD -- server/src/main` (14 files, all inside the
  task's `touches`).
- Source/test snapshot identifier (including uncommitted inputs): HEAD 46acd7bd plus the modified
  `ProjectEdgeCaseTests.kt` sha256 `e0589122e30858236d54ee0dd575c246844a38ca18eb604375ac370bf0d790a2`; identical before
  and after the runs; no other tracked changes.
- Exact included files: the 14 `server/src/main/kotlin/ch/sonensei/cockpit/gtd/...` files below. No renames or
  deletions. Excluded: `api/openapi.yaml` (generated spec, checked by OpenApiContractTest) and
  `web/src/app/api/generated/**` (generated client). No hand-written web/android production files changed.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (Gradle
  9.8.1 default tool version); `./gradlew cleanTest test jacocoTestReport` in `server/`; full server test suite.
- Run start/completion time and exit status: 2026-10-20T17:53:49Z to 17:55:44Z, BUILD SUCCESSFUL.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (mtime 2026-10-20 19:55:44 +02:00, sha256 `2a02e441b1db55af7982eea043b8398614da6362abc595229bd8558c47e35488`),
  `server/build/jacoco/test.exec` (mtime 19:55:43 +02:00).
- Evidence outputs are fresh and inputs stayed unchanged: yes (`cleanTest` forced a rerun, report mtimes inside the
  run window, snapshot hash and HEAD unchanged).
- Final run after latest test additions and production fixes: yes (after the last test edit; fixes e2deb13 are in HEAD).

Values below use covered/total (percentage). Statements = JaCoCo INSTRUCTION, Functions = METHOD. Paths relative to
`server/src/main/kotlin/ch/sonensei/cockpit/`.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `gtd/api/AreaContextControllers.kt` | 404/406 (99.5%) | N/A | 23/24 (95.8%) | 49/49 (100.0%) |
| `gtd/api/PatchBodies.kt` | 171/187 (91.4%) | 18/20 (90.0%) | 5/6 (83.3%) | 17/18 (94.4%) |
| `gtd/api/ProblemResponses.kt` | N/A | N/A | N/A | N/A |
| `gtd/api/ProjectController.kt` | 349/353 (98.9%) | 12/12 (100.0%) | 13/15 (86.7%) | 43/43 (100.0%) |
| `gtd/api/ProjectDtos.kt` | 678/734 (92.4%) | 13/16 (81.2%) | 73/81 (90.1%) | 129/131 (98.5%) |
| `gtd/api/TaskController.kt` | 519/526 (98.7%) | 26/28 (92.9%) | 18/20 (90.0%) | 78/79 (98.7%) |
| `gtd/api/TaskDtos.kt` | 1301/1393 (93.4%) | 45/56 (80.4%) | 78/100 (78.0%) | 177/177 (100.0%) |
| `gtd/domain/AreaService.kt` | 369/369 (100.0%) | 31/32 (96.9%) | 10/10 (100.0%) | 57/57 (100.0%) |
| `gtd/domain/ContextService.kt` | 388/388 (100.0%) | 33/34 (97.1%) | 11/11 (100.0%) | 58/58 (100.0%) |
| `gtd/domain/ProjectCommands.kt` | 148/150 (98.7%) | N/A | 19/19 (100.0%) | 19/19 (100.0%) |
| `gtd/domain/ProjectService.kt` | 1106/1113 (99.4%) | 98/100 (98.0%) | 25/25 (100.0%) | 126/126 (100.0%) |
| `gtd/domain/TaskService.kt` | 1735/1742 (99.6%) | 213/215 (99.1%) | 35/35 (100.0%) | 190/190 (100.0%) |
| `gtd/infra/GtdRepositories.kt` | N/A | N/A | N/A | N/A |
| `gtd/infra/ProjectTrashContributor.kt` | 165/176 (93.8%) | 18/20 (90.0%) | 5/5 (100.0%) | 31/31 (100.0%) |
| **Aggregate (summed counters, 12 measured files)** | 7333/7537 (97.3%) | 507/533 (95.1%) | 315/351 (89.7%) | 974/978 (99.6%) |

`GtdRepositories.kt` (Spring Data interfaces/queries) and `ProblemResponses.kt` (OpenAPI annotation classes) have no
JaCoCo-executable code: they appear in the report without counters (N/A, not missing). No scoped file is missing.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `gtd/infra/ProjectTrashContributor.kt:49` (`maxPositionInList ?: -1` null branch) | Purge position of a live task | Unreachable: the live task itself is in that list, so the max is never null. Purge order/positions now tested by `purging a project moves its open and done tasks...` |
| `gtd/infra/ProjectTrashContributor.kt:31` (`checkNotNull(deletedAt)`) | Trash listing | Unreachable: `findAllDeleted` returns only rows with `deleted_at` |
| `gtd/api/AreaContextControllers.kt` companion `getAREA_PATCH_FIELDS`, `gtd/api/ProjectController.kt` companion `getPATCH_FIELDS`/`getNEXT_STEP_FIELDS` | None (Kotlin-generated companion accessors; the fields themselves are used and covered) | Non-material |
| `gtd/api/ProjectDtos.kt:111, 133` and partial branches 51/52/78 | Kotlin default-argument constructors of `NextStepRequest`/`OrderTasksRequest`/`OrderRequest`; unreachable `checkNotNull(createdAt/updatedAt)` null branches | Non-material, generated code |
| `gtd/api/PatchBodies.kt:17` (`PatchBody.contains`), branches 29/52 | `contains` has no callers; `required` null branch is pre-empted by `parsePatch`'s not-null check (tested) | Non-material / defensive |
| `gtd/api/TaskController.kt:332`, branch 143; `gtd/api/TaskDtos.kt` partial branches; `gtd/domain/TaskService.kt:98` | Older gtd-tasks-api code; this task's changed lines in these files are all covered (21/21, 5/5, 7/7) | Outside this task's change |
| `gtd/domain/ProjectService.kt:74` (`title ?: throw`) | Create without title | Defensive: the controller's `@NotNull` rejects it first (tested by `POST rejects a missing or blank title...`) |
| `gtd/domain/AreaService.kt:71`, `ContextService.kt:88` (one branch each) | Defensive branches in reorder/rename | Non-material; reorder validation tested |

## Unable to test

- Nothing in the acceptance criteria is untested; all five ran in the configuration they name (Postgres via
  Testcontainers with Docker available; contract test without `UPDATE_OPENAPI`).
- Changed-branch coverage was not measured (JaCoCo does not report it per changed line directly).
- `.taskcheck` skipped the Android part in this environment (`COCKPIT_NO_ANDROID=1`); no Android code is in scope.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. 8 tests were added for the edge cases of
the round-1 fixes (purge detaching tasks, next-step body handling, area PATCH parser). The gtd suite (156 tests), the
OpenApiContractTest, the full server suite and `.taskcheck` pass.
