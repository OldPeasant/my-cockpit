# Test Report

Date: 2026-10-20

Task: gtd-tasks-api - Tasks API

Scope: task commit `54f957029479fc0e3a6d71238ed05cdcd68e72af` against base `dc7514b180e106c50322a2595ca1d3c43841041e` (`git diff dc7514b1 54f95702`), measured on HEAD `f21a7faeca2228c7c379fd0b3a057f7dbb4ca0ba` (merge of `origin/wip/gtd-tasks-api` into main). `git diff 54f95702 f21a7fae` touches none of the gtd main or test sources, so the task code at HEAD is the code of 54f95702, including the CR-5 and CR-6 fixes. This report replaces the earlier test report, which described the code before those fixes.

Requirements:
- `tasks/gtd-tasks-api.md`: acceptance criteria 1-6 and the four recorded assumptions (two of them confirmed by a human)
- `SPEC.md` §5.4 (Tasks API) and §4.2 (ETag/If-Match, Problem Details)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- Parent instructions: CR-5 (a project task keeps its project position on a list-only move) and CR-6 (a concurrent idempotent create is answered like the repeat)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/TaskPositionAndCursorTests.kt` (new, untracked; not committed, see Summary)

No existing test was changed, weakened, skipped or deleted. No production code was changed.

## Tests added or updated

Count: 8 (all in `TaskPositionAndCursorTests`)

- `move into another project puts the task at the end of that project, naming its own project keeps its position` (CR-5, project change by move)
- `a task without a project moving to another list goes after the loose tasks there, project positions do not count` (CR-5, list ordering)
- `PATCH projectId null puts the task at the end of the loose tasks of its list` (positions when a task leaves its project)
- `POST that loses the insert race against a create that is in the Trash is 409` (CR-6: the race fallback goes through `existingCreate`, which keeps the Trash rule)
- `open tasks with the same position are paged without gaps or repeats` (cursor tie-break on id)
- `done tasks completed at the same instant are paged without gaps or repeats, also per project` (Logbook cursor tie-break, `projectId` with `completed=true`)
- `a blank If-Match on a workflow action counts as not sent`
- `contexts sent twice are stored once` (contexts set by an id list)

## Requirement coverage

Command for every row (one run): `cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest' jacocoTestReport` -> 92 tests, 0 failures, 0 errors, 0 skipped.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `GET /tasks` filters list, projectId, context, energy, creativity, maxMinutes, due (overdue/today/week), completed, cursor paging | TaskWebTests: `GET filters by list, project, context, energy, creativity and estimate`, `GET filters by due window in the user's time zone`, `GET pages with a cursor, also for done tasks`, `GET rejects invalid parameters`; TaskEdgeCaseTests: `due windows and the tickler use today in the user's time zone, not in UTC`, `maxMinutes includes the limit itself and never matches tasks without an estimate`, `filters combine with the Logbook, a done task does not appear in open lists`, `the last page has no cursor, a cursor of the open list is rejected for the Logbook`; new: `open tasks with the same position are paged without gaps or repeats`, `done tasks completed at the same instant are paged without gaps or repeats, also per project` | as above | Pass |
| AC2: POST, GET, PATCH, DELETE with ETag/If-Match (428 missing, 412 stale) | TaskWebTests: `POST creates an inbox task with ETag and Location, indexes it and announces it`, `POST with every field and contexts`, `POST validates fields`, `POST with a client id is idempotent`, `GET of an unknown task is 404`, `PATCH changes only the sent fields, null clears, and returns the new ETag`, `PATCH needs a current If-Match`, `PATCH rejects unknown, uncleareable and invalid fields`, `DELETE moves the task to the Trash, restore and purge go through the Trash`; TaskEdgeCaseTests: `POST with the id of a task in the Trash is 409 and does not restore it`, `POST accepts values at the limits and rejects values just above them, all at once`, `PATCH rejects an unknown context, null for uncleareable fields and values of the wrong type`, `PATCH and DELETE of a task in the Trash are 404, DELETE twice is 404`, `If-Match accepts a list of tags and the wildcard, but not a weak tag` | as above | Pass |
| AC2: CR-6, a concurrent create with the same client id is answered like the repeat | TaskEdgeCaseTests: `POST that loses the insert race against a create with the same id answers like the idempotent repeat` (200 with the existing task); new: `POST that loses the insert race against a create that is in the Trash is 409` | as above | Pass |
| AC2: complete / uncomplete (If-Match optional, checked when sent) | TaskWebTests: `complete and uncomplete, If-Match optional but checked when sent`; TaskEdgeCaseTests: `completing a done task keeps the first completion time, uncomplete returns it to its list`; new: `a blank If-Match on a workflow action counts as not sent` | as above | Pass |
| AC2: move with validation (SCHEDULED needs a date, WAITING sets waitingSince, waiting-for fields only on WAITING, future tickler, unknown project), 412 on stale If-Match | TaskWebTests: `move validates the list rules`, `move to SCHEDULED, WAITING and back`, `create and PATCH enforce the list rules of move`, `PATCH cannot remove the date of a SCHEDULED task`, `a task whose project is in the Trash can still be changed, completed and moved`; TaskEdgeCaseTests: `move reports every violation at once and rejects waitingForContactId outside WAITING`, `move keeps the tickler only while it is sent, tomorrow is the earliest tickler date`, `WAITING with a contact, leaving clears it, waiting again starts a new waitingSince`, `an explicit waitingSince is kept on create, a cleared one is set again by a move within WAITING`, `move with a stale If-Match is 412 and changes nothing, the wildcard is accepted` | as above | Pass |
| AC2: positions after a move or project change (assumption: a task changing list/project goes to the end; CR-5: a project task keeps its project position on a list-only move) | TaskEdgeCaseTests: `move to another list puts the task at the end of that list and returns its current ETag`, `move to another list keeps a project task at its position in the project`, `PATCH into another project puts the task at the end of that project unless a position is sent`; new: `move into another project puts the task at the end of that project, naming its own project keeps its position`, `a task without a project moving to another list goes after the loose tasks there, project positions do not count`, `PATCH projectId null puts the task at the end of the loose tasks of its list` | as above | Pass |
| AC3: checklist CRUD under `/tasks/{id}/checklist` with item ETags | ChecklistWebTests (3 tests), ChecklistEdgeCaseTests: `POST returns Location and ETag and accepts done, text up to 1000 characters`, `PATCH changes only the sent fields and can reorder items`, `PATCH rejects blank and too long text and keeps the item`, `DELETE needs If-Match, unknown items are 404, a task in the Trash takes no new items` | as above | Pass |
| AC3: contexts set by id list | TaskWebTests: `POST with every field and contexts`, `PATCH changes only the sent fields, null clears, and returns the new ETag`; TaskEdgeCaseTests: `PATCH rejects an unknown context, ...`; new: `contexts sent twice are stored once` | as above | Pass |
| AC4: every change indexes the task and publishes a ChangeEvent | TaskWebTests: `POST creates an inbox task ...`, `PATCH changes only the sent fields ...`, `complete and uncomplete ...`, `move to SCHEDULED, WAITING and back`; TaskEdgeCaseTests: `PATCH clears enums and recurrence with null and updates the search index`; ChecklistEdgeCaseTests: `every checklist change announces the task as UPDATED and re-indexes it` | as above | Pass |
| AC4: soft delete, the task is in the Trash (TrashContributor: list, restore, purge) | TaskWebTests: `DELETE moves the task to the Trash, restore and purge go through the Trash`; TaskEdgeCaseTests: `a done task in the Trash is not in the Logbook and shows as Done in the Trash`, `restore announces the task, purge deletes its checklist and contexts and clears it as next step`, `restore and purge of a task that is not in the Trash are 404` | as above | Pass |
| AC5: `./gradlew test --tests 'ch.sonensei.cockpit.gtd.*'` passes, Postgres via Testcontainers, incl. API tests for filters, move validation and 412 on a stale ETag | all gtd test classes (91 tests in 7 classes, all on `PostgresTestBase`) | as above | Pass |
| AC6: `OpenApiContractTest` passes without `UPDATE_OPENAPI` | `ch.sonensei.cockpit.core.web.OpenApiContractTest` (1 test) | as above, without the flag | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.TaskPositionAndCursorTests' ktlintCheck`

Result:

Pass (8 tests, ktlint clean). The first run of the new tests failed one assertion of mine: I had expected an exact position (`1`) after `PATCH projectId:null`. The service gave `2`, because the auto-flush before the max-position query counts the task's own row. The task is still at the end of the list, which is what the requirement says, so I changed the assertion to "after every other loose task in the list". This is not a defect (see "Observations").

Then the requested command:

`cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest' jacocoTestReport`

Result:

Pass: 92 tests, 0 failures, 0 errors, 0 skipped (OpenApiContractTest 1, ChecklistEdgeCaseTests 5, ChecklistWebTests 3, GtdRepositoryTests 22, GtdItemResolverTests 8, TaskEdgeCaseTests 24, TaskPositionAndCursorTests 8, TaskWebTests 21). The CR-6 race test passed in this run.

## Full test execution

Command:

`sh scripts/check.sh` (contains `cd server && ./gradlew --quiet check`, the full server test suite with ktlint)

Result:

Pass ("== all checks passed").

## .taskcheck

Result:

Pass. I ran `sh scripts/check.sh`, the content of `.taskcheck`: TFP gate tests, server `./gradlew check`, web `npm run check` (lint, tests, build), android ktlint, unit tests and lint, all with the new test file present. The web build printed a warning that the initial bundle is 4.94 kB over its 500 kB budget. That warning does not fail the check, and the web code is outside this task's diff.

## Bugs found

None. No test failed because of production behavior.

Observations, not defects (no fix required):
- `TaskService.modify` / `nextPosition` (`server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/TaskService.kt`): the max-position query auto-flushes the changed task first, so the task's own old position is counted. When the old position is the highest, the new position is old + 1, not max(others) + 1. The task still ends up last, which is what the assumption requires. The only effect is a gap in the positions.
- `TaskController.create` (`TaskController.kt:146`): a `DataIntegrityViolationException` without a client id, or with an id whose row no longer exists, is rethrown. That path is defensive and I did not test it, see Coverage.

## Coverage

Status: verified (fresh run, exit status 0, inputs unchanged during the run).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured separately. I intersected the added lines of `git diff --unified=0 dc7514b1..54f95702 -- server/src/main/kotlin/ch/sonensei/cockpit/gtd` with JaCoCo lines that have instructions, and counted a line as covered when `ci > 0`. Result: 572/577 executable changed lines covered (99.1%). Branch counters on the changed lines: 337/354. Those are JaCoCo line branch counters, not changed-branch coverage. The 5 missed changed lines are `TaskController.kt:346` (synthetic code of a companion object) and `TaskCommands.kt:30, 44, 68, 75` (the closing lines of data-class constructors with default arguments, i.e. synthetic default constructors).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `gtd-tasks-api`; base `dc7514b180e106c50322a2595ca1d3c43841041e`; task commit `54f957029479fc0e3a6d71238ed05cdcd68e72af` (given by the parent and matching the `release` note in the task file); measured HEAD `f21a7faeca2228c7c379fd0b3a057f7dbb4ca0ba`. `git diff 54f95702 f21a7fae` has no changes in `server/src/{main,test}/kotlin/ch/sonensei/cockpit/gtd`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD f21a7fae plus the one untracked file `TaskPositionAndCursorTests.kt` (sha256 `3c134c31f391c54755ee2aadb9992b1644728fe0b656c964e7a2c3c71625947b`). The sha256 of the `sha256sum` listing of `server/src/test/kotlin/ch/sonensei/cockpit/gtd/*.kt` plus `server/src/main/kotlin/ch/sonensei/cockpit/gtd/*/*.kt` (run from `server/src`) is `e7c36c5b4fe160fee91c665e6c3093fd20c50d93ced25cfb2b0f82bc92cdb061`. It was the same before and after the coverage run and after `.taskcheck`.
- Exact included files: `gtd/api/ChecklistController.kt`, `gtd/api/TaskController.kt`, `gtd/api/TaskDtos.kt`, `gtd/domain/ChecklistService.kt`, `gtd/domain/GtdEnums.kt`, `gtd/domain/Task.kt`, `gtd/domain/TaskCommands.kt`, `gtd/domain/TaskService.kt`, `gtd/infra/GtdItemResolvers.kt`, `gtd/infra/TaskTrashContributor.kt` (all under `server/src/main/kotlin/ch/sonensei/cockpit/`). `gtd/infra/GtdRepositories.kt` is in scope but has no JaCoCo counters: it contains only Spring Data interfaces, with no executable bytecode in the file. I report it as N/A, not as covered. Excluded: `api/openapi.yaml` and `web/src/app/api/generated/**` (generated), `reviews/**` (docs), test files. Nothing was renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin, as configured in `server/build.gradle.kts`; Gradle 9.8.1. Command `./gradlew test --rerun --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest' jacocoTestReport` in `server/`.
- Run start/completion time and exit status: 2026-10-20T17:50:54+02:00 to 17:51:38+02:00, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` (written 17:51:38, sha256 `269c1fea4456cdcb6cae7e13496b803106d1d9e8e8c288bd11c801afe586c9ad`), from `server/build/jacoco/test.exec` (written 17:51:35). The test results are in `server/build/test-results/test/` (timestamps 15:51 UTC).
- Evidence outputs are fresh and inputs stayed unchanged: yes. `--rerun` forced the test task to run, and the exec and XML files were written during this run. The snapshot hash was the same before and after.
- Final run after latest test additions and production fixes: yes. It includes the CR-5/CR-6 fixes (already in 54f95702) and all 8 new tests. Nothing changed after the run.

Values below use covered/total (percentage); JaCoCo has no separate statement counter, so "Statements" shows INSTRUCTION counters.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `gtd/api/ChecklistController.kt` | 132/132 (100.0%) | 2/2 (100.0%) | 7/7 (100.0%) | 19/19 (100.0%) |
| `gtd/api/TaskController.kt` | 655/662 (98.9%) | 45/48 (93.8%) | 19/20 (95.0%) | 88/89 (98.9%) |
| `gtd/api/TaskDtos.kt` | 1286/1378 (93.3%) | 45/56 (80.4%) | 75/97 (77.3%) | 172/172 (100.0%) |
| `gtd/domain/ChecklistService.kt` | 232/232 (100.0%) | 18/18 (100.0%) | 7/7 (100.0%) | 34/34 (100.0%) |
| `gtd/domain/GtdEnums.kt` | 160/160 (100.0%) | 5/5 (100.0%) | 6/6 (100.0%) | 25/25 (100.0%) |
| `gtd/domain/Task.kt` | 178/178 (100.0%) | 2/2 (100.0%) | 42/42 (100.0%) | 27/27 (100.0%) |
| `gtd/domain/TaskCommands.kt` | 244/449 (54.3%) | N/A (0 branches) | 40/44 (90.9%) | 42/46 (91.3%) |
| `gtd/domain/TaskService.kt` | 1695/1702 (99.6%) | 205/207 (99.0%) | 35/35 (100.0%) | 186/186 (100.0%) |
| `gtd/infra/GtdItemResolvers.kt` | 106/106 (100.0%) | 13/13 (100.0%) | 7/7 (100.0%) | 31/31 (100.0%) |
| `gtd/infra/TaskTrashContributor.kt` | 122/131 (93.1%) | 15/16 (93.8%) | 5/5 (100.0%) | 23/23 (100.0%) |
| `gtd/infra/GtdRepositories.kt` | N/A (interfaces only) | N/A | N/A | N/A |

Aggregate over the 10 measured files, computed from summed counters (excludes `GtdRepositories.kt`, which has no counters): instructions 4810/5130 (93.8%), branches 350/367 (95.4%), methods 243/270 (90.0%), lines 647/652 (99.2%). The previous run on the same code before the new tests had branches at 349/367: the new tests added the blank-If-Match branch. The other new tests exercise behavior on lines that existing tests already reach, so they add assertions but no counters.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `gtd/api/TaskController.kt:146` (2 of 4 branches) | Fallback after a `DataIntegrityViolationException` on create: no client id, or `existingCreate` returns null | Justified gap. Without a client id, no duplicate-key race is possible, and a lost race always leaves the winner's row. Both are defensive rethrows. The reachable CR-6 paths (a repeat that wins the race, a race against a task in the Trash) are tested. |
| `gtd/api/TaskController.kt:312` (1 branch) | `?: "body"` when a Jackson conversion error has no property path | Justified gap. The body is already a JSON object at this point, so every conversion error is tied to a field. |
| `gtd/api/TaskController.kt:346` | Synthetic code of the companion object | Not behavior. |
| `gtd/api/TaskDtos.kt:75-76` (1 branch each) | `checkNotNull(createdAt/updatedAt)` failure | Not reachable for persisted tasks (JPA auditing sets them). Defensive. |
| `gtd/api/TaskDtos.kt:122` and generated data-class methods (22 methods: `copy`, `equals`, `hashCode`, `component*`) | Kotlin-generated code of request/response DTOs | Not behavior. No tests added only to raise the numbers. |
| `gtd/api/TaskDtos.kt:232-238` (8 branches) | `TaskPatchNullEnums` OpenAPI customizer: null-safe navigation and skip conditions | Its output is checked by `OpenApiContractTest`, which compares against the committed `api/openapi.yaml`. The remaining branches are guards for schemas that are absent or already nullable. |
| `gtd/domain/TaskCommands.kt` (instructions 205 missed, 4 methods) | Data classes `TaskChanges`, `TaskMove`, `TaskFilter`, `ChecklistItemChanges`: generated `copy`/`equals`/`hashCode`/`toString` and default constructors | Not behavior. |
| `gtd/domain/TaskService.kt:98` (2 branches) | `title` missing in `create` | The API rejects a missing title before the service runs (`@NotNull`, tested in `POST validates fields`). This check guards other callers of the service. |
| `gtd/infra/TaskTrashContributor.kt:26` (1 branch) | `checkNotNull(deletedAt)` for rows returned by `findAllDeleted()` | Not reachable, because the query selects only rows with `deleted_at`. Defensive. |

## Unable to test

- A real concurrent create race through two HTTP requests. The CR-6 tests reproduce the race deterministically: a competing uncommitted JDBC insert holds the row lock, the POST waits on it (observed through `pg_stat_activity`), and then the competitor commits. This is the race CR-6 is about, so no follow-up is needed.
- The web client against the regenerated `api/openapi.yaml`. It is outside this server-focused scope (the parent's left-over item 1, `npm run api:gen`). The web part of `.taskcheck` (lint, tests, build) passed.

## Summary

Tests added and all validation passed.

The 8 new tests cover CR-5 project/list positions, the CR-6 race against a task in the Trash, cursor paging over equal sort keys, a blank optional If-Match and duplicate context ids. No production defects were found. The requested gtd + OpenApiContractTest run (92 tests), `.taskcheck` and fresh task-scoped JaCoCo coverage all pass. The remaining uncovered code is defensive or compiler-generated.

The new test file and this report are not committed. My role does not allow committing or staging, so the parent worker has to review and commit `server/src/test/kotlin/ch/sonensei/cockpit/gtd/TaskPositionAndCursorTests.kt` and `reviews/gtd-tasks-api/tests.md`.
