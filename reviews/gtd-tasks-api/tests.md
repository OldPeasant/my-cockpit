# Test Report

Date: 2026-10-20

Task: gtd-tasks-api - Tasks API

Scope: `git diff a67434529b8fa4cd07205312c877dfda021e9b5d..e4a9c73f39afb4f6fde842f1437a68116b41ecb0 -- server/src/main/kotlin/ch/sonensei/cockpit/gtd server/src/test/kotlin/ch/sonensei/cockpit/gtd server/src/main/resources/db/migration/gtd`. Branch `main` (local clone of agent-6), HEAD `e4a9c73f39afb4f6fde842f1437a68116b41ecb0`. Task commits f98c23a, c438ac2, 9d13a3c (the fixes for CR-1..CR-4), merge 4b839e5 (main merged in, generated files regenerated), e4a9c73 (fixer report only). Before testing, the working tree was clean. The only uncommitted inputs are the two new test files listed below.

Requirements:
- `tasks/gtd-tasks-api.md`: acceptance criteria 1-6 and the four ASSUMPTION notes (filter semantics, move rules, PATCH/If-Match rules, idempotent create)
- `SPEC.md` §4.2 (ETag/If-Match, Trash), §5.4 (via the task); `docs/ARCHITECTURE.md` "Coverage"; `docs/QUALITY.md`
- `reviews/gtd-tasks-api/review.md` and `fixes.md` (CR-1..CR-4 fixed behavior)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/TaskEdgeCaseTests.kt` (new, 22 tests)
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/ChecklistEdgeCaseTests.kt` (new, 5 tests)

No existing test was changed, weakened or disabled. No production code was changed.

## Tests added or updated

Count: 27

Both classes follow the existing `TaskWebTests` pattern: MockMvc against the full Spring context, Postgres via Testcontainers (`PostgresTestBase`), `MutableClock`, and `@RecordApplicationEvents` for ChangeEvents. The test data is fictitious. `TaskEdgeCaseTests` pins the `timeZone` setting to Europe/Zurich before each test and resets it afterwards, so it does not depend on settings left behind by other test classes.

## Requirement coverage

Existing = already covered by `TaskWebTests` / `ChecklistWebTests`; New = added here. All commands ran in `server/` and used Postgres 16 via Testcontainers.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 filters: list, projectId, context, energy, creativity, maxMinutes, due, completed, cursor | Existing: `GET filters by list, project, context, energy, creativity and estimate`, `GET filters by due window ...`, `GET pages with a cursor, also for done tasks`, `GET rejects invalid parameters`. New: `maxMinutes includes the limit itself and never matches tasks without an estimate`, `filters combine with the Logbook, a done task does not appear in open lists`, `the last page has no cursor, a cursor of the open list is rejected for the Logbook`, `due windows and the tickler use today in the user's time zone, not in UTC`, `a done task in the Trash is not in the Logbook ...` | `./gradlew cleanTest test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest' jacocoTestReport` | Pass |
| AC2 CRUD with ETag/If-Match (412 stale, 428 missing), complete/uncomplete, move validation (SCHEDULED needs a date, WAITING sets waitingSince, tickler in the future) | Existing: PATCH/move/complete/DELETE tests in `TaskWebTests`. New: `If-Match accepts a list of tags and the wildcard, but not a weak tag`, `move with a stale If-Match is 412 ...`, `PATCH and DELETE of a task in the Trash are 404 ...`, `completing a done task keeps the first completion time ...`, `move reports every violation at once ...`, `move keeps the tickler only while it is sent ...`, `WAITING with a contact, leaving clears it ...`, `an explicit waitingSince is kept on create ...`, `move to another list puts the task at the end of that list ...`, `PATCH into another project puts the task at the end ...`, `PATCH rejects an unknown context, null for uncleareable fields ...`, `PATCH clears enums and recurrence with null ...`, `POST accepts values at the limits ...`, `POST keeps a recurrence rule and mode as given ...`, `POST with the id of a task in the Trash is 409 ...` | same | Pass |
| AC3 checklist CRUD with item ETags; contexts set by id list | Existing: `ChecklistWebTests` (3), PATCH contextIds in `TaskWebTests`. New: `POST returns Location and ETag and accepts done ...`, `PATCH changes only the sent fields and can reorder items`, `PATCH rejects blank and too long text ...`, `DELETE needs If-Match, unknown items are 404 ...`; for contexts: `PATCH rejects an unknown context ...`, the context round trip in the restore/purge test | same | Pass |
| AC4 search indexing, ChangeEvents, soft delete, Trash (TrashContributor) | Existing: `POST creates an inbox task ...`, `DELETE moves the task to the Trash, restore and purge ...`. New: `every checklist change announces the task as UPDATED and re-indexes it`, `PATCH clears enums ... and updates the search index` (body indexed), `restore announces the task, purge deletes its checklist and contexts and clears it as next step`, `restore and purge of a task that is not in the Trash are 404`, `a done task in the Trash ... shows as Done in the Trash` | same | Pass |
| AC5 `./gradlew test --tests 'ch.sonensei.cockpit.gtd.*'` passes, with API tests for filters, move validation and 412 | all of the above (81 gtd tests) | `./gradlew cleanTest test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest' jacocoTestReport` | Pass: GtdRepositoryTests 22, GtdItemResolverTests 8, TaskWebTests 21, TaskEdgeCaseTests 22, ChecklistWebTests 3, ChecklistEdgeCaseTests 5; 0 failures, 0 skipped |
| AC6 `OpenApiContractTest` passes without `UPDATE_OPENAPI` | `OpenApiContractTest` (existing) | same run, flag not set | Pass (1 test) |
| CR-1 a task in a trashed project can still be changed | Existing: `a task whose project is in the Trash can still be changed, completed and moved` | same | Pass |
| CR-2 / CR-3 OpenAPI types (`maxMinutes` integer, nullable PATCH fields, `null` in enums) | `OpenApiContractTest` (compares with the committed `api/openapi.yaml`). The run covered the `TaskPatchNullEnums` customizer | same | Pass |
| CR-4 create/PATCH enforce the list rules | Existing: `create and PATCH enforce the list rules of move`. New: `an explicit waitingSince is kept on create ...`, `POST accepts values at the limits ...` (WAITING with text) | same | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.TaskEdgeCaseTests' --tests 'ch.sonensei.cockpit.gtd.ChecklistEdgeCaseTests'`

Result:

Pass (21 + 5 tests at that point. The 22nd TaskEdgeCaseTests test was added afterwards, and the gtd run below included it.)

Command:

`cd server && ./gradlew cleanTest test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest' jacocoTestReport`

Result:

Pass: BUILD SUCCESSFUL, 82 tests (81 gtd + 1 OpenApiContractTest), 0 failures, 0 errors, 0 skipped.

Command:

`cd server && ./gradlew ktlintCheck`

Result:

Pass (BUILD SUCCESSFUL, no violations)

## Full test execution

Command:

`sh scripts/check.sh` (= `.taskcheck`: `server ./gradlew check`, `web npm run check`, `android ktlintCheck testDebugUnitTest lintDebug`)

Result:

Pass: "== all checks passed". Server JUnit results after this run: 1239 tests, 0 failures, 0 errors, 0 skipped. Wall time about 4.5 min (17:12:17-17:16:56 +02:00, including the web and Android parts). That is above the 2 min warning and well below the 15 min limit.

## .taskcheck

Result:

Pass (see Full test execution; `.taskcheck` contains `sh scripts/check.sh`)

## Bugs found

None. Every new test passed against the implementation at HEAD e4a9c73. No production defect was found.

Observations (not defects; for the parent to judge, no fix required):
- Moving a task *with a project* to another list gives it the next position in its project, even though the project did not change: `modify` repositions on a change of list or project. This matches the recorded assumption "a task changing list/project goes to the end of the new list/project". Within the project, though, this reorders the task. Recorded only so the assumption can be confirmed.
- The existing `TaskWebTests` read the default `timeZone` setting and do not pin it. Other test classes (e.g. `SettingsWebTests`) can change the setting in the shared database. The existing tests still pass, because at the fixed test time (08:00 UTC) the date is the same in the likely zones. The new tests pin the zone explicitly.

## Coverage

Status: verified. Fresh JaCoCo run, exit 0, every test passing, over the task's production files.

Metric: whole-file coverage of task-changed production files. It includes pre-existing code in `Task.kt`, `GtdEnums.kt`, `GtdItemResolvers.kt` and `GtdRepositories.kt`, and hits from pre-existing gtd tests (`GtdRepositoryTests`, `GtdItemResolverTests`).
Changed-line coverage: not measured (the configured tooling has no diff-coverage support).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: gtd-tasks-api. Base `a67434529b8fa4cd07205312c877dfda021e9b5d` and the task commits (f98c23a, c438ac2, 9d13a3c, merge 4b839e5, e4a9c73) came from the parent worker. The file scope is the parent's path-restricted diff, which matches the task's `touches`. HEAD is `e4a9c73f39afb4f6fde842f1437a68116b41ecb0`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD e4a9c73 with tracked files clean (`git diff --quiet HEAD -- server` passed), plus two untracked test files: `TaskEdgeCaseTests.kt` sha256 `59269f49aefa5de9d1080c9ea380f8e6a90b09edc38fcd3684a6c2f5f609b001` and `ChecklistEdgeCaseTests.kt` sha256 `a54985538bff3633fa75ea68c53511d709edf71f0c58fa72bbbf90655c5b3526`. The hashes were taken after the run and match the measured inputs: no edits were made between the run and the hashing.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/{ChecklistController,TaskController,TaskDtos}.kt`, `.../gtd/domain/{ChecklistService,GtdEnums,Task,TaskCommands,TaskService}.kt`, `.../gtd/infra/{GtdItemResolvers,GtdRepositories,TaskTrashContributor}.kt`. Excluded: test files (not production); `server/src/main/resources/db/migration/gtd` (no migration changed in the diff). No renames or deletions. `GtdRepositories.kt` contains only Spring Data interfaces and has no JaCoCo counters (N/A, not missing).
- Provider/version, command, working directory, and test selection: JaCoCo 0.8.15 (Gradle `jacoco` plugin, agent `org.jacoco:org.jacoco.agent:0.8.15`). Command `./gradlew cleanTest test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest' jacocoTestReport` in `server/`. Selection: all gtd test classes plus OpenApiContractTest (AC6).
- Run start/completion time and exit status: 2026-10-20T17:10:43+02:00 to 17:11:47+02:00, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` (written 17:11:47), sha256 `753217afd4cbbbfcc2d2ad007e27d5335028da6aee08d9fab6c2ad15fed083fc`, JaCoCo session `dell26-294ee592`. Counters were read per `<sourcefile>` with `.tmp/jacoco_scope.py`, a Git-ignored scratch script.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `cleanTest` forced the tests to rerun, and the report timestamp and session id belong to this run. No source or test file changed between the run and the hashing above.
- Final run after latest test additions and production fixes: yes. The run includes all 27 new tests and the production code with the CR-1..CR-4 fixes (9d13a3c). No production change followed it.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. "Statements" are JaCoCo INSTRUCTION counters, because JaCoCo has no statement counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `gtd/api/ChecklistController.kt` | 132/132 (100.0%) | 2/2 (100.0%) | 7/7 (100.0%) | 19/19 (100.0%) |
| `gtd/api/TaskController.kt` | 637/641 (99.4%) | 42/44 (95.5%) | 19/20 (95.0%) | 84/85 (98.8%) |
| `gtd/api/TaskDtos.kt` | 1286/1378 (93.3%) | 45/56 (80.4%) | 75/97 (77.3%) | 172/172 (100.0%) |
| `gtd/domain/ChecklistService.kt` | 232/232 (100.0%) | 18/18 (100.0%) | 7/7 (100.0%) | 34/34 (100.0%) |
| `gtd/domain/GtdEnums.kt` | 160/160 (100.0%) | 5/5 (100.0%) | 6/6 (100.0%) | 25/25 (100.0%) |
| `gtd/domain/Task.kt` | 178/178 (100.0%) | 2/2 (100.0%) | 42/42 (100.0%) | 27/27 (100.0%) |
| `gtd/domain/TaskCommands.kt` | 244/449 (54.3%) | N/A | 40/44 (90.9%) | 42/46 (91.3%) |
| `gtd/domain/TaskService.kt` | 1675/1682 (99.6%) | 199/201 (99.0%) | 34/34 (100.0%) | 183/183 (100.0%) |
| `gtd/infra/GtdItemResolvers.kt` | 106/106 (100.0%) | 13/13 (100.0%) | 7/7 (100.0%) | 31/31 (100.0%) |
| `gtd/infra/GtdRepositories.kt` | N/A | N/A | N/A | N/A |
| `gtd/infra/TaskTrashContributor.kt` | 122/131 (93.1%) | 15/16 (93.8%) | 5/5 (100.0%) | 23/23 (100.0%) |
| **Aggregate (summed counters, 10 measured files; `GtdRepositories.kt` has no counters)** | 4772/5089 (93.8%) | 341/357 (95.5%) | 242/269 (90.0%) | 640/645 (99.2%) |

Before the new tests (same command without `--tests '*OpenApiContractTest'`, run at 17:03 on the same HEAD), the aggregate was: instructions 4467/5089 (87.8%), branches 291/357 (81.5%), methods 240/269 (89.2%), lines 623/645 (96.6%). The new tests closed these gaps: the 409 on a client id in the Trash, the length and estimate limits, PATCH of position/recurrence, waitingForContactId on move, the malformed-cursor path, the restore/purge "not in Trash" paths, the "Done" Trash subtitle, and the checklist partial PATCH and text limit.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `TaskService.kt:101` (2/4 branches) | `title` missing on create | Justified gap: the `@NotNull` bean validation on `CreateTaskRequest.title` rejects this first (covered by `POST validates fields` with `{}`), so the service guard is unreachable through the API |
| `TaskController.kt:287` (1/6 branches) | `If-Match` sent but blank on a workflow action is treated as absent | Not material: an edge of a header-blank check. The present/absent behavior is tested |
| `TaskController.kt:304` (1/2), `:338` | Fallback field name `body` when a Jackson error has no property path; companion init line | Not material: defensive fallback. Conversion errors with a field (`energy`, `timeEstimateMin`, `dueDate`) are tested |
| `TaskDtos.kt:75-76` | `checkNotNull(createdAt/updatedAt)` failure branch | Unreachable for saved entities (defensive) |
| `TaskDtos.kt:94-122, 179-185` | Generated getters/`equals`/`hashCode`/`copy`/`componentN` of the request data classes | Not behavior: Kotlin data-class boilerplate, unused by the code |
| `TaskDtos.kt:232-238` (partial branches) | `TaskPatchNullEnums`: skip paths for missing components, non-enum and already-nullable properties | Main path covered by `OpenApiContractTest` (the generated contract matches). The skip paths are defensive |
| `TaskCommands.kt` (instructions 54.3%) | Generated `copy`/`equals`/`hashCode`/`toString`/`componentN` of the command data classes | Not behavior: data-class boilerplate. All constructors and properties used by the service are covered |
| `TaskTrashContributor.kt:26` (1/2 branches) | `checkNotNull(deletedAt)` failure | Unreachable: `findAllDeleted` returns only rows with `deleted_at` set |

## Unable to test

- Concurrent modification that surfaces as 409 (Hibernate optimistic lock between load and flush) is not tested. A deterministic test needs two interleaved transactions, and this suite has no such harness for MockMvc. The documented 409 response comes from the shared core error handling. Follow-up optional, not required by the acceptance criteria.
- Everything else in the acceptance criteria was executed. No browser, image build or external service is involved.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. 27 tests were added, and all gtd tests, `OpenApiContractTest`, `ktlintCheck` and `.taskcheck` passed.
