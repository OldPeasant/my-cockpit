# Test Report

Date: 2026-10-26

Task: gtd-scheduled-marker - Today view: keep 'scheduled for today' visible after the scheduled release

Scope: `git diff ad5a240be3b61f185d8fe8d49d2a762a5b01cb7c..28e44631` (base = task claim commit ad5a240b, HEAD 28e44631 on `main`), plus one uncommitted test addition by this run in `server/src/test/kotlin/ch/sonensei/cockpit/gtd/TicklerWebTests.kt`. Task-owned commits: c141a864 (server), 00e5aaf0 (web), ddb2ec2f and 456ac139 (earlier test-writer tests), 28e44631 (revert of shared PostgresTestBase). 3b7fb520 only adds `tasks/and-today-released.md` (no code).

Requirements:
- `tasks/gtd-scheduled-marker.md` acceptance criteria and the recorded assumptions (`releasedOn` field, cleared on list change and tickler release, kept on complete/edit/project change; backup field optional; `GET /tasks?scheduled=today`)
- SPEC.md §5.1 (tickler/scheduled release), §5.3 (Today view "Scheduled for today"), §14 (backup round trip)
- docs/ARCHITECTURE.md "Coverage"

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/TicklerWebTests.kt` (this run: 1 test added; earlier run: 11 tests)
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/backup/GtdBackupContributorTests.kt` (earlier run)
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/backup/GtdBackupFixture.kt` (earlier run)
- `web/src/app/features/gtd/data/task-store.spec.ts` (earlier run)
- `web/src/app/features/gtd/today/today-view.spec.ts` (earlier run)

## Tests added or updated

Count: 24 in the task scope (1 added in this run, 23 added or updated by the earlier test-writer run and reviewed here).

This run reviewed the earlier tests against the criteria. They cover release marking, the user-zone day boundary, clearing on list change and tickler release, keeping it on complete/uncomplete/edit/project move, recurrence, trash, filter combinations, 400 on an invalid value, backup export/import and old-backup compatibility, store matching, optimistic move/rollback, refetch on event, and the Today view rendering. One gap was found and closed: no server test showed that a SCHEDULED task moved to Next by hand gets no marker (only the web store tested this). Added: `a scheduled task moved to Next by hand gets no marker and is not scheduled for today`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: server keeps a marker on released tasks (`releasedOn` set by the tickler release, in the user's zone) | TicklerWebTests: `a released scheduled task remembers the day it was released, ...`, `the release marker is the day in the user's time zone, not the UTC day`, `a scheduled task for today or a past day that is released on read is scheduled for today` | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest' jacocoTestReport ktlintCheck` | Pass |
| AC1: marker lifecycle (cleared on leaving Next and on tickler release, kept on complete/uncomplete/edit/project move, not copied to next occurrence, no marker on a manual move) | TicklerWebTests: `a completed released task keeps its marker, a task that leaves Next loses it`, `a released tickler clears the release marker`, `editing a released task keeps its marker, a trashed one is not scheduled for today`, `the next occurrence of a released recurring task is not marked as released`, `an uncompleted released task keeps its marker ...`, `a scheduled task in a tickler is not scheduled for today ...`, `a scheduled task moved to Next by hand gets no marker and is not scheduled for today` (new) | same | Pass |
| AC1: `GET /tasks?scheduled=today` filter, combinations, validation | TicklerWebTests: `scheduled for today combines with the other filters`, `an unknown scheduled filter is a 400`, plus the tests above | same | Pass |
| AC1: Flyway migration | Every Postgres-backed test above runs all Flyway migrations, including `V202610260700__gtd_task_released_on.sql`, and reads/writes `released_on` | same | Pass |
| AC1: `api/openapi.yaml` regenerated | `OpenApiContractTest` (fails when the committed spec differs from the generated one) | same | Pass |
| AC1 / SPEC §14: backup covers the marker | GtdBackupContributorTests: `the release marker is exported as a date and restored, a task without one keeps none`, `a task line of a backup made before the release marker existed restores without one`, the full-field export test (expects `releasedOn`), GtdBackupFixture round trip | same | Pass |
| AC2: Today view "Scheduled for today" shows tasks released today | today-view.spec.ts: `keeps tasks the server released to Next today in Scheduled for today`, `shows a released task that is also due today only once, under Due today`, `drops a released task from Scheduled for today when it is completed`, the request-params test (`{scheduled:'today'}`), error/retry tests | `cd web && npx ng test --watch=false --include src/app/features/gtd/today/today-view.spec.ts --include src/app/features/gtd/data/task-store.spec.ts` | Pass (74 tests) |
| AC2: live updates match the new filter (store) | task-store.spec.ts: `drops the release marker of a task that leaves Next, keeps it within Next`, `drops a released task from a scheduled-for-today list when it leaves Next, and brings it back on an error`, `refetches scheduled for today on a task event, ...`, `a scheduled task moved to Next by hand is no longer scheduled for today`; matchesTask: `matches scheduled for today: ...`, `combines scheduled for today with the list, completion and other filters` | same | Pass |
| AC2: `cd web && npm run check` passes | whole web check (api:check, lint, e2e typecheck, test:ci, build) | `cd web && npm run check` | Pass (ended with the production build output; only the existing initial-bundle budget warning) |

## Focused test execution

Commands:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest' jacocoTestReport ktlintCheck`

`cd web && npx ng test --watch=false --include src/app/features/gtd/today/today-view.spec.ts --include src/app/features/gtd/data/task-store.spec.ts`

Result:

Pass. Server: BUILD SUCCESSFUL in 4m 59s, exit 0. 434 test cases in the fresh result files, 0 failures/errors/skipped (TicklerWebTests 20/20, GtdBackupContributorTests 23/23, OpenApiContractTest 1/1). ktlint passed. Web: 2 files, 74 tests passed.

## Full test execution

Commands:

`cd web && npm run test:ci` -> Pass: 177 files, 3741 tests passed (14:23-14:25).

`cd web && npm run check` -> Pass.

Server full `./gradlew check`: Not run by this helper. The parent reports it passed at 28e44631 (12.5 min) and said not to rerun it. This run only added one test, and that test passed in the focused run above.

Note: the first `npm run coverage` attempt (14:18, while the Gradle run was in parallel) failed one unrelated test, `app.spec.ts > App > builds every navigation section, ...`, with a 5000 ms timeout under load. It wrote no coverage output. The later `npm run test:ci` and `npm run check` passed every web test, including this one.

## .taskcheck

Result:

Not run. `sh scripts/check.sh` includes the 12.5 min server `./gradlew check`, which the parent already ran at 28e44631 and asked not to repeat. Its parts were run separately: the server gtd tests + ktlint (focused), and the full web `npm run check`. The parent runs `.taskcheck` before `done`.

## Bugs found

None.

## Coverage

Status: verified (task-scoped, whole-file). The server numbers come from a selected test set (all `ch.sonensei.cockpit.gtd.*` tests + OpenApiContractTest), not the full server suite.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured separately. Server: JaCoCo line entries (`ci`, `mb`/`cb`) intersected with the added/modified lines from `git diff -U0 ad5a240b..HEAD`. Web: v8 `statementMap`/`branchMap` entries whose span touches an added line in the same diff. Results are below.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: gtd-scheduled-marker; base ad5a240be3b61f185d8fe8d49d2a762a5b01cb7c (claim commit, from the parent); HEAD 28e44631; scope `git diff --name-only ad5a240b..HEAD -- server/src/main web/src`, without spec files, checked against the commit list and the task's assumptions.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 28e44631 + uncommitted `server/src/test/kotlin/ch/sonensei/cockpit/gtd/TicklerWebTests.kt` sha256 `326dba99ebbc9f7891192f22d3981a4a266598fadeef3e15e7e20a0641acf41f` (the same hash before and after the runs). No other modified or untracked source files.
- Exact included files: server `gtd/api/TaskController.kt`, `gtd/api/TaskDtos.kt`, `gtd/backup/GtdBackupContributor.kt`, `gtd/backup/GtdBackupRecords.kt`, `gtd/domain/Task.kt`, `gtd/domain/TaskCommands.kt`, `gtd/domain/TaskService.kt`; web `features/gtd/data/task-store.ts`, `features/gtd/today/today-view.ts`. Excluded: `db/migration/gtd/V202610260700__gtd_task_released_on.sql` (SQL, not instrumentable; it runs in every Postgres test), `web/src/app/api/generated/**` (3 generated files), `api/openapi.yaml` (generated; checked by OpenApiContractTest). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: server JaCoCo (Gradle `jacoco` plugin, JDK 25.0.4.1), `./gradlew test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest' jacocoTestReport ktlintCheck` in `server/`. Web Vitest 5.0.5 + `@vitest/coverage-v8` 5.0.5, `npx ng test --watch=false --coverage --coverage-reporters=json-summary --coverage-reporters=json --coverage-include='src/app/features/gtd/**' --include 'src/app/features/gtd/**/*.spec.ts'` in `web/` (all 774 gtd feature tests).
- Run start/completion time and exit status: server 14:17:50 to 14:22:56 (+01:00), exit 0. Web 14:21:42 to 14:22:05, 774/774 passed, exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` (mtime 14:22:56, sha256 `60fcd2e6ff043c007bfaec17f3e717603688ae8c298ecface001dfefd838c1a5`). `web/coverage/web/coverage-final.json` (sha256 `107388227318f26cafc4b2ad079d95e8561570663fdcf7a37d7d72d26049954f`) and `coverage-summary.json` (sha256 `c5a19210cb87a59b738f4cd1022573cbb62c117a880762669d4b984fd088b6b6`), both written at 14:22:05.
- Evidence outputs are fresh and inputs stayed unchanged: yes. Gradle reran the tests because a test source changed (the new test case is in the fresh XML, timestamp 13:21Z). The web `coverage/` folder did not exist before the run (the earlier failed run wrote none). The test-file hash matches before and after.
- Final run after latest test additions and production fixes: yes. It ran after the one test this run added. No production fixes were made.

Values below use covered/total (percentage). The server "Statements" column is JaCoCo INSTRUCTION; "Functions" is METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/.../gtd/api/TaskController.kt` | 607/614 (98.9%) | 44/46 (95.7%) | 19/21 (90.5%) | 92/93 (98.9%) |
| `server/.../gtd/api/TaskDtos.kt` | 1309/1401 (93.4%) | 45/56 (80.4%) | 79/101 (78.2%) | 180/180 (100%) |
| `server/.../gtd/backup/GtdBackupContributor.kt` | 1591/1604 (99.2%) | 68/72 (94.4%) | 32/32 (100%) | 247/247 (100%) |
| `server/.../gtd/backup/GtdBackupRecords.kt` | 1083/1085 (99.8%) | 82/84 (97.6%) | 85/85 (100%) | 128/128 (100%) |
| `server/.../gtd/domain/Task.kt` | 192/192 (100%) | 2/2 (100%) | 46/46 (100%) | 29/29 (100%) |
| `server/.../gtd/domain/TaskCommands.kt` | 353/473 (74.6%) | N/A (0 branches) | 43/46 (93.5%) | 45/48 (93.8%) |
| `server/.../gtd/domain/TaskService.kt` | 2342/2352 (99.6%) | 272/275 (98.9%) | 38/38 (100%) | 273/273 (100%) |
| Server aggregate (7 files, summed counters) | 7477/7721 (96.8%) | 513/535 (95.9%) | 342/369 (92.7%) | 994/998 (99.6%) |
| `web/.../gtd/data/task-store.ts` | 85/85 (100%) | 99/102 (97.1%) | 28/28 (100%) | 68/68 (100%) |
| `web/.../gtd/today/today-view.ts` | 44/44 (100%) | 19/21 (90.5%) | 16/16 (100%) | 35/35 (100%) |
| Web aggregate (2 files, summed counters) | 129/129 (100%) | 118/123 (95.9%) | 44/44 (100%) | 103/103 (100%) |

Excluded or unmeasured next to the aggregates: the migration SQL (not instrumentable) and 3 generated web API files. No scoped file is missing from a report.

Changed-line coverage: server 21/21 changed executable lines covered, 10/10 branches on changed lines (TaskController 4/4 lines and 6/6 branches, TaskService 9/9 lines and 4/4 branches, the other files 1-3 lines each, all covered). Web task-store.ts 7/7 changed statements and 13/13 changed branches. today-view.ts 3/3 changed statements, no branches on changed lines.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `TaskController.kt:373`, branches at `:175` | Code from before this task (another endpoint), not in the changed lines | Justified gap: outside the task |
| `TaskDtos.kt` branches at 83, 84, 140, 250-256; 22 methods | Kotlin data-class generated methods (`copy`/`componentN`) and null-safe branches from before this task | Justified gap: generated or not related to `releasedOn`. The changed lines are covered. |
| `GtdBackupContributor.kt` branches at 110, 121, 133, 150 | Code from before this task (other entities) | Justified gap: outside the task |
| `GtdBackupRecords.kt` branch at 117 | Code from before this task | Justified gap |
| `TaskCommands.kt:44, 72, 79` | Generated data-class members and default-argument constructors from before this task | Justified gap. The new `scheduledToday` line is covered. |
| `TaskService.kt` branches at 102, 330 | Code from before this task | Justified gap |
| `task-store.ts` branches 57[1], 96[1], 114[1] | `current ?? task` fallbacks in older optimistic update/complete/uncomplete code | Justified gap: not changed by this task |
| `today-view.ts` branches 43[0], 112[0] | Older route/toggle code (the uncomplete path of the checkbox) | Justified gap: not changed by this task |

## Unable to test

- Full `.taskcheck` and server `./gradlew check` were not run by this helper, at the parent's direction (the parent already ran the server check at 28e44631). No follow-up is needed beyond the parent's own `.taskcheck` before `done`.
- No E2E (Playwright) test. The criteria do not name one, and the server-side release is covered by the integration tests above.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
