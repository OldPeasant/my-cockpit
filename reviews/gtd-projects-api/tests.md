# Test Report

Date: 2026-10-20

Task: gtd-projects-api - Projects, areas, contexts API and next-step rules

Scope: `git diff bc7f6be150a8b21139b5ec76d81773d61daf3a74..69d6a1b8969c245ab1a2d66e75fbf3a776fffb07` on branch `main`
(task commits 7bc184a2 implementation, 69d6a1b8 tests + regenerated web client, supplied by the parent worker), plus the
untracked test file `server/src/test/kotlin/ch/sonensei/cockpit/gtd/ProjectEdgeCaseTests.kt`. No uncommitted production
changes.

Requirements:
- `tasks/gtd-projects-api.md` acceptance criteria and recorded assumptions (stalled rule, needsNextStep, next-step and
  order semantics, area/context hard delete)
- SPEC.md §5.1-5.2 (via the task), `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`
- Existing test conventions in `server/src/test/kotlin/ch/sonensei/cockpit/gtd/` (`PostgresTestBase`, MockMvc,
  `@RecordApplicationEvents`, `MutableClock`)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/ProjectEdgeCaseTests.kt` (new, untracked; must be committed by the parent)

## Tests added or updated

Count: 29 (all in `ProjectEdgeCaseTests`).

An earlier, interrupted test-writer run left 27 tests in this file without a report. I reviewed them against the
production code and the recorded assumptions. They compile, pass ktlint and pass, and they test behavior the
criteria require, so I kept them as they are. I added 2 tests:
- `the search API finds a project by title and description, follows renames and loses it in the Trash`: end-to-end
  through `GET /api/v1/search?types=PROJECT`. Covers title and description hits, subtitle and deep link, re-index on
  PATCH (the old words no longer match), removal on delete, and re-indexing on restore. The existing tests only
  checked the `search_document` row.
- `next-step treats a blank If-Match like an absent one`: covers the last uncovered branch in `ProjectController`.

Existing tests (`ProjectWebTests` 17, `AreaContextWebTests` 9, from commit 69d6a1b8) were not modified.

## Requirement coverage

Focused command (F): `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.gtd.*'`. Contract command
(C): `cd server && ./gradlew test --tests '*OpenApiContractTest'`.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 CRUD projects (status, area, dueDate, reviewDate) | `ProjectWebTests`: `POST creates an active, stalled project...`, `POST rejects a missing or blank title and an unknown area`, `GET of an unknown project is 404`, `PATCH changes and clears fields...`, `PATCH rejects unknown fields...`, `status COMPLETED records the completion time...`, `list filters by status, area and stalled...`, `list pages by title...`; `ProjectEdgeCaseTests`: `create with a status other than ACTIVE is never stalled...`, `title, description, area title and context name are accepted up to their maximum length`, `PATCH areaId null takes a project out of its area`, cursor tests (`list limits outside 1 to 200...`, `an empty cursor is the first page...`, `paging through a filtered list...`, `a cursor stays valid...`), optimistic locking tests | F | Pass |
| AC1 areas (ordered) and contexts (ordered, color) | `AreaContextWebTests` (9 tests: create/list order/events, validation, If-Match, 404, PUT order, delete cascades, unique names, color); `ProjectEdgeCaseTests`: `GET returns a context with its ETag, and PATCH changes only the position...`, `a context create that loses the race for its name is a 409, not a 500`, `deleting a context also strips it from done and trashed tasks...`, `deleting an area also takes trashed projects out of it...` | F | Pass |
| AC1 ordered tasks in a project (`PUT .../order`) | `ProjectWebTests`: `PUT order puts the listed tasks first...`, `PUT order rejects duplicates and tasks of other projects`; `ProjectEdgeCaseTests`: `PUT order with an empty list keeps the order and changes nothing`, `PUT order skips and rejects tasks in the Trash, and 404s for a project in the Trash` | F | Pass |
| AC2 `POST /projects/{id}/next-step {taskId}` | `ProjectWebTests`: `next-step designates an open task of the project...`, `next-step rejects tasks of other projects, without a project, done, deleted or unknown`; `ProjectEdgeCaseTests`: `next-step and task order bump versions, so stale ETags are refused afterwards`, `a next step set concurrently with another change is not lost silently`, `next-step treats a blank If-Match like an absent one` | F | Pass |
| AC2 `stalled` flag in list and detail | `ProjectWebTests`: `a project is stalled when its next step is done, deleted or moved away, and only while ACTIVE`; `ProjectEdgeCaseTests`: `the stalled flag of every listed project matches the stalled filter`, `stalled combines with the other filters and never lists projects in the Trash`, `a next step restored from the Trash un-stalls the project, a purged one leaves it without a next step`, `list uses the stalled rule with the next steps of a whole page loaded at once` | F | Pass |
| AC2 `needsNextStep: true` on completing the next task | `ProjectWebTests`: `completing the next step of an active project returns needsNextStep`, `completing the next step of a project that is not active does not ask for a new one`; `ProjectEdgeCaseTests`: `completing the next step again keeps asking...`, `uncomplete restores the next step, and completing it again asks again`, `completing a former next step or the next step of a project in the Trash does not ask for a new one`, `completing the next step of a project in each non-active status does not ask for a new one` | F | Pass |
| AC3 search indexing | `ProjectWebTests` (index row on create, PATCH, delete, restore); `ProjectEdgeCaseTests`: `the search API finds a project by title and description, follows renames and loses it in the Trash`, `the Trash contributor restores and purges...` (description in index), `a project changed concurrently after the If-Match check is not overwritten` (index not changed on rollback) | F | Pass |
| AC3 ChangeEvents | `ProjectWebTests` and `AreaContextWebTests` (CREATED/UPDATED/DELETED for PROJECT, AREA, CONTEXT, TASK on order); `ProjectEdgeCaseTests`: `restoring a project from the Trash announces it...`, `PUT order with an empty list ... changes nothing`, both delete-cascade tests (only items outside the Trash announced) | F | Pass |
| AC3 trash for projects | `ProjectWebTests`: `DELETE moves the project to the Trash...`, `purging a project from the Trash keeps its tasks without a project`; `ProjectEdgeCaseTests`: `the Trash contributor lists deleted projects...`, `the Trash contributor restores and purges only projects that are in the Trash`, `restoring a project from the Trash announces it and keeps its next step and stalled state` | F | Pass |
| AC4 gtd tests pass on Postgres (Testcontainers), incl. stalled detection and the next-step prompt flag | all 146 tests in `ch.sonensei.cockpit.gtd.*` (10 classes) | F | Pass (146 tests, 0 failures, 0 errors, 0 skipped) |
| AC5 OpenApiContractTest passes without the flag | `OpenApiContractTest` | C (no `UPDATE_OPENAPI`) | Pass (1 test). `api/openapi.yaml` unchanged afterwards |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.gtd.*'`

Result:

Pass. BUILD SUCCESSFUL. JUnit XML: AreaContextWebTests 9, GtdItemResolverTests 8, TaskPositionAndCursorTests 8,
GtdRepositoryTests 22, ProjectEdgeCaseTests 29, ChecklistWebTests 3, ChecklistEdgeCaseTests 5, TaskWebTests 21,
TaskEdgeCaseTests 24, ProjectWebTests 17. Total 146, with 0 failures, 0 errors and 0 skipped. ktlint (main + test) passed.

Command:

`cd server && ./gradlew test --tests '*OpenApiContractTest'`

Result:

Pass. `ch.sonensei.cockpit.core.web.OpenApiContractTest`: 1 test, 0 failures. Run 2026-10-20T16:46:13Z.

## Full test execution

Command:

`cd server && ./gradlew cleanTest test jacocoTestReport`

Result:

Pass. Final run on the final snapshot: 1509 tests, 0 failures, 0 errors, 0 skipped. BUILD SUCCESSFUL in 2m 59s.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` (server, web and android lint + unit tests) printed `== all checks passed`. Run after the
last test change.

## Bugs found

None. No production defect was found. All new and existing tests pass against the implementation.

Observation (not a defect, no requirement violated): completing or uncompleting a project's next step, and deleting or
restoring it, changes the project's derived `stalled` flag without publishing a `PROJECT` ChangeEvent. Only a `TASK`
event is published. Neither the task nor SPEC requires a project event here, so I did not report it as a finding.
The parent may want to consider it for clients that cache project lists.

## Coverage

Status: verified (fresh full-suite JaCoCo run on the final snapshot; exit status success).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 547/550 executable changed lines covered (99.5%). Method: I intersected the JaCoCo XML `line`
elements (covered = `ci > 0`) with the added/modified line numbers from
`git diff -U0 bc7f6be1..69d6a1b8`, file by file. Only lines JaCoCo reports as executable count, and removed lines are
not included. I did not measure changed-branch coverage.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `gtd-projects-api`. Base
  `bc7f6be150a8b21139b5ec76d81773d61daf3a74`, measured HEAD `69d6a1b8969c245ab1a2d66e75fbf3a776fffb07`. Task commits
  7bc184a2 and 69d6a1b8 were supplied by the parent. The file scope is
  `git diff --name-only bc7f6be1..HEAD -- server/src/main`. All changed files are inside the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 69d6a1b8 with no tracked changes, plus the
  untracked `ProjectEdgeCaseTests.kt` sha256 `cc5951c767d9e4898986d74116920dc3a80f5d8e59e3c38c153dfd4917b52ba9`.
  The hash and HEAD were the same before and after the run.
- Exact included files: the 14 `server/src/main/kotlin/ch/sonensei/cockpit/gtd/...` files in the table below. No renames
  or deletions. Excluded: `api/openapi.yaml` (generated spec) and `web/src/app/api/generated/**` (generated client).
  Both are generated artifacts, and the contract test checks the spec. No task-changed production files under
  `web/src` or `android/` were hand-written.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (Gradle
  9.8.1 default tool version). Command `./gradlew cleanTest test jacocoTestReport`, run in `server/`, on the full server
  test suite.
- Run start/completion time and exit status: 2026-10-20T18:47:12+02:00 to 18:50:12+02:00. BUILD SUCCESSFUL.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (mtime 18:50:12, sha256 `56ac05facb467f4863b1b739f0451a6fe5ae9853fb264c6f9c263a4987ef0721`) and
  `server/build/jacoco/test.exec` (mtime 18:50:09).
- Evidence outputs are fresh and inputs stayed unchanged: yes. `cleanTest` forced every test to rerun, the
  report mtime falls inside the run window, and the snapshot hash and HEAD were the same before and after.
- Final run after latest test additions and production fixes: yes. It ran after the last test edit. No production
  fixes were made.

Values below use covered/total (percentage). Statements = JaCoCo INSTRUCTION, Functions = METHOD. A zero-denominator
or unsupported metric is N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `gtd/api/AreaContextControllers.kt` | 374/374 (100.0%) | N/A | 22/22 (100.0%) | 46/46 (100.0%) |
| `gtd/api/PatchBodies.kt` | 171/187 (91.4%) | 18/20 (90.0%) | 5/6 (83.3%) | 17/18 (94.4%) |
| `gtd/api/ProblemResponses.kt` | N/A | N/A | N/A | N/A |
| `gtd/api/ProjectController.kt` | 287/289 (99.3%) | 6/6 (100.0%) | 13/14 (92.9%) | 39/39 (100.0%) |
| `gtd/api/ProjectDtos.kt` | 678/734 (92.4%) | 13/16 (81.2%) | 73/81 (90.1%) | 129/131 (98.5%) |
| `gtd/api/TaskController.kt` | 519/526 (98.7%) | 26/28 (92.9%) | 18/20 (90.0%) | 78/79 (98.7%) |
| `gtd/api/TaskDtos.kt` | 1301/1393 (93.4%) | 45/56 (80.4%) | 78/100 (78.0%) | 177/177 (100.0%) |
| `gtd/domain/AreaService.kt` | 369/369 (100.0%) | 31/32 (96.9%) | 10/10 (100.0%) | 57/57 (100.0%) |
| `gtd/domain/ContextService.kt` | 388/388 (100.0%) | 33/34 (97.1%) | 11/11 (100.0%) | 58/58 (100.0%) |
| `gtd/domain/ProjectCommands.kt` | 148/150 (98.7%) | N/A | 19/19 (100.0%) | 19/19 (100.0%) |
| `gtd/domain/ProjectService.kt` | 1106/1113 (99.4%) | 98/100 (98.0%) | 25/25 (100.0%) | 126/126 (100.0%) |
| `gtd/domain/TaskService.kt` | 1735/1742 (99.6%) | 213/215 (99.1%) | 35/35 (100.0%) | 190/190 (100.0%) |
| `gtd/infra/GtdRepositories.kt` | N/A | N/A | N/A | N/A |
| `gtd/infra/ProjectTrashContributor.kt` | 115/124 (92.7%) | 13/14 (92.9%) | 5/5 (100.0%) | 23/23 (100.0%) |
| **Aggregate (summed counters, 12 measured files)** | 7191/7389 (97.3%) | 496/521 (95.2%) | 314/348 (90.2%) | 959/963 (99.6%) |

The paths are relative to `server/src/main/kotlin/ch/sonensei/cockpit/`. `GtdRepositories.kt` (Spring Data repository
interfaces and query annotations) and `ProblemResponses.kt` (OpenAPI annotation classes) contain no JaCoCo-executable
code. They appear in the report with no counters, so their metrics are N/A and they are not missing. The repository
queries run indirectly through the service tests. No scoped file is missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `gtd/api/ProjectController.kt:166` (blank If-Match branch of next-step) | Optional If-Match handling | Added `next-step treats a blank If-Match like an absent one`. Now 6/6 branches |
| `gtd/api/ProjectDtos.kt:106, 128` and partial lines 51/52/69-78/103-127 | Kotlin data-class generated `equals`/`hashCode`/`copy`/`componentN` and default-argument constructors of request DTOs (`NextStepRequest`, `OrderRequest`). The `checkNotNull(createdAt/updatedAt)` null branches are unreachable for persisted entities | Justified gap: generated code, no behavior |
| `gtd/api/PatchBodies.kt:17` (`PatchBody.contains`), `:29` (`required` with a null value), `:52` | `contains` has no callers. The `checkNotNull` failure in `required` cannot happen because `parsePatch` rejects nulls for not-nullable fields first (tested by `PATCH rejects unknown fields, nulls for title and status...`) | Justified gap: unused or defensive code |
| `gtd/api/TaskController.kt:332` and branch `143` | `TaskController` changes only refactored PATCH parsing and the complete response, and those changed lines are 21/21 covered. Line 332 and branch 143 are older code (companion init and the concurrent-create fallback from gtd-tasks-api) | Outside this task's change |
| `gtd/api/TaskDtos.kt` partial branches | Older data-class or null-default branches. The task changed 5 lines (`CompletedTaskResponse`), all covered | Outside this task's change |
| `gtd/domain/ProjectService.kt:74` (`title ?: throw`) | Create without a title. The controller's `@NotNull` rejects this first (tested by `POST rejects a missing or blank title...`), so the service-level throw is defensive | Justified gap |
| `gtd/domain/TaskService.kt:98` | Older create-without-title defensive branch, not changed by this task | Outside this task's change |
| `gtd/domain/AreaService.kt:71`, `ContextService.kt:88` (one branch each) | Defensive or duplicate-check branches in reorder/rename paths. Reorder validation is tested by `PUT areas/contexts order needs every ... exactly once` | Non-material |
| `gtd/infra/ProjectTrashContributor.kt:26` (`checkNotNull(deletedAt)` null branch) | Unreachable: `findAllDeleted` only returns rows with `deleted_at` set | Non-material |
| `gtd/domain/ProjectCommands.kt:8` (partial) | Data-class default constructor | Non-material |

## Unable to test

- Nothing in this task's criteria is untested. All five criteria ran in the configuration they name: Postgres via
  Testcontainers (Docker available), and the contract test without `UPDATE_OPENAPI`.
- Changed-branch coverage (as opposed to changed-line coverage) was not measured. The tooling does not report it
  directly.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. I kept the interrupted run's 27 edge-case
tests after review and added 2 more (search through the API, blank If-Match). The gtd suite (146 tests), the
OpenApiContractTest, the full server suite (1509 tests) and `.taskcheck` all pass. The parent must commit
`server/src/test/kotlin/ch/sonensei/cockpit/gtd/ProjectEdgeCaseTests.kt` together with this report.
