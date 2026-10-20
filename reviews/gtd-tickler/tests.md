# Test Report

Date: 2026-10-20

Task: gtd-tickler - Tickler and scheduled tasks job

Scope: `git diff d40fb120f981f9aefe34c7a20fbe14662f5ac748..756ea8f723fb8ed4ca3ed93be5cd2778ed2d84d8` (one task commit,
`756ea8f7 gtd-tickler: tickler and scheduled tasks job with on-read check`). The parent named `ee033562` as the task
commit. `756ea8f7` is the same commit rebased: `git diff ee033562 756ea8f7` only touches `tasks/gtd-tickler.md` and
`tasks/web-reminders.md`, so the code is the same. Branch `main`, no uncommitted production changes.

Requirements:
- `tasks/gtd-tickler.md` acceptance criteria 1-3 and the recorded assumptions (Notes)
- `SPEC.md` §5.1 (Scheduled / Tickler rows) and Phase 1 acceptance scenario 5
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/TicklerEdgeCaseTests.kt` (new, Spring Boot + MockMvc + Postgres Testcontainers, test clock)
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/TicklerJobTests.kt` (new, unit tests with MockK and `ApplicationContextRunner`)
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/UserZoneCronTriggerTests.kt` (3 tests added)

The existing tests in `TicklerWebTests.kt` (8) were not changed.

## Tests added or updated

Count: 22 (14 in TicklerEdgeCaseTests, 5 in TicklerJobTests, 3 in UserZoneCronTriggerTests)

TicklerEdgeCaseTests:
- `a task in a tickler is hidden from the context, due, estimate and energy filters until its date`
- `a task in a tickler is skipped by paging without ending the page early`
- `a done task with a tickler date still to come is hidden from the Logbook, and stays done afterwards`
- `a task whose tickler date is today is listed (the date is inclusive)`
- `a released project task keeps its project and its place within the project`
- `a released scheduled project task keeps its place within the project`
- `ticklers due on the same run go to the Inbox in tickler date order, after what is already there`
- `a tickler put away from the Inbox comes back to its own place in the Inbox`
- `a scheduled task released to Next goes after the existing next actions`
- `reading a single task releases it when the job has not run`
- `reading a single task that is not due leaves it in the tickler`
- `a second run moves nothing and announces nothing`
- `a release changes the version, so an edit based on the tickler state is rejected`
- `a task in the tickler reappears in every list query on its date without the job`

TicklerJobTests:
- `the job returns the number of moved tasks`
- `a failing run is swallowed, so a read never fails because of it, and the next run tries again`
- `the schedule registers one trigger task that runs the job at 00_05 in the user's zone`
- `the schedule is on by default`
- `the schedule is off when the scheduler is disabled, as in the tests`

UserZoneCronTriggerTests:
- `the run stays at 00_05 local time across the switch to summer time`
- `the run stays at 00_05 local time across the switch back to winter time`
- `the run is exactly once per day, the next one planned after the last completed run`

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: tasks with `ticklerDate` are left out of all list queries until that date | TicklerWebTests `a task in a tickler is hidden from every list, and two days later the job puts it back in the Inbox`; TicklerEdgeCaseTests filter, paging, Logbook, inclusive-date and `reappears in every list query` tests | `cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.gtd.*' jacocoTestReport` | Pass |
| AC2: job at 00:05 in the user's local time | UserZoneCronTriggerTests (5, DST included); TicklerJobTests `the schedule registers one trigger task ...`, `on by default`, `off when the scheduler is disabled` | same | Pass (trigger and registration tested; the real scheduler firing at wall-clock 00:05 was not observed, see "Unable to test") |
| AC2: due ticklers go to INBOX, due SCHEDULED tasks go to NEXT, ChangeEvents UPDATED | TicklerWebTests (scheduled, tickler, waiting/scheduled fields cleared, done/deleted untouched, user zone); TicklerEdgeCaseTests positions, project tasks, ordering, idempotency, version | same | Pass |
| AC2: on-read check (GET list and GET by id) | TicklerWebTests `reading the tasks releases a due tickler when the job has not run`; TicklerEdgeCaseTests `reading a single task releases it ...`, `... not due leaves it in the tickler`; TicklerJobTests `a failing run is swallowed ...` | same | Pass |
| AC3: `./gradlew test --tests 'ch.sonensei.cockpit.gtd.*'` passes on Postgres via Testcontainers, test clock advanced 2 days puts the task back in the Inbox | whole gtd package, 17 suites (TicklerWebTests first test is scenario 5) | same | Pass: 224 tests, 0 failures, 0 errors, 0 skipped |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.Tickler*' --tests 'ch.sonensei.cockpit.gtd.UserZoneCronTriggerTests'`

Result:

Pass (before the last test was added): TicklerWebTests 8/8, TicklerEdgeCaseTests 13/13, UserZoneCronTriggerTests 5/5,
TicklerJobTests 5/5. The final snapshot (14 edge case tests) passed in the gtd package run below.

`cd server && ./gradlew ktlintFormat ktlintCheck` -> exit 0, no remaining lint findings.

## Full test execution

Command:

`cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.gtd.*' jacocoTestReport` (AC3 package)

Result:

Pass: 17 suites, 224 tests, 0 failures, 0 errors, 0 skipped (exit 0, 2026-10-20 20:59:22 to 21:00:11 +02:00).

Command:

`sh scripts/check.sh` (`.taskcheck`; includes `cd server && ./gradlew --quiet check`, the whole server suite)

Result:

Pass: the server ran 160 suites and 1857 tests with 0 failures, 0 errors and 0 skipped. Web: 30 files and 471 tests passed. The Android checks and the TFP gate tests passed too.

## .taskcheck

Result:

Pass (`sh scripts/check.sh`, exit 0, 2026-10-20 21:00:33 to 21:05:12 +02:00, "all checks passed").

## Bugs found

None. No test failed against the production code.

Observations, not defects of this task (for the parent to decide):
- SPEC scenario 5 says "Create 'Renew passport' with tickler = today + 2 days". `CreateTaskRequest` has no
  `ticklerDate`, so a tickler is set with create + `POST /tasks/{id}/move`, and the tests do it that way. The create
  contract is not in this task's acceptance criteria.
- When a released tickler is already on INBOX (it was put away from the Inbox), it keeps its old position instead of
  going to the end of the Inbox. That matches `moveTo` (it only repositions when the list changes) and the test
  documents it. Neither the spec nor the task says which is right.

## Coverage

Status: verified (fresh run on the final test snapshot, exit 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. JaCoCo XML per-line entries (executable lines only) intersected with the added/modified
lines of `git diff -U0 d40fb120..HEAD` per file. 48/48 changed executable lines are covered, and 8/8 branches on
changed lines are covered (TaskService 6/6, TicklerJob 2/2).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `gtd-tickler`; base
  `d40fb120f981f9aefe34c7a20fbe14662f5ac748` (from the parent); HEAD `756ea8f723fb8ed4ca3ed93be5cd2778ed2d84d8`;
  task-owned commit `756ea8f7` (rebased copy of `ee033562`, same code); the file scope comes from
  `git diff --name-only d40fb120..HEAD -- server/src/main`, which matches the task `touches`.
- Source/test snapshot identifier (including uncommitted inputs): production = HEAD `756ea8f7` with no uncommitted
  production changes. Uncommitted test inputs (sha256):
  `TicklerEdgeCaseTests.kt` 8dab906d991a59be11a5f1a43123e1f6a12413be9f991a77af95ea2d23eaffc8,
  `TicklerJobTests.kt` f1e4b3ca3f1820cd1f7f3a7de32028e41c1851c60c4c82c678a83917009d3330,
  `UserZoneCronTriggerTests.kt` d3e8893ca8259b5a430fd018a17d512f2f87ac3b575fbc807c86fe664770a7f7.
- Exact included files; renamed/deleted/excluded files and reasons: included are
  `server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/TaskController.kt`, `.../gtd/domain/TaskService.kt`,
  `.../gtd/domain/TicklerJob.kt`, `.../gtd/infra/GtdSchedulingConfiguration.kt`, `.../gtd/infra/GtdRepositories.kt`.
  `GtdRepositories.kt` is in the report but has no executable bytecode (Spring Data interfaces and `@Query` strings),
  so it is N/A. Its two new queries (`findDueTicklers`, `findDueScheduled`) run against Postgres in the web tests.
  Excluded: `api/openapi.yaml` (generated, documentation only), `server/src/test/resources/config/application.properties`
  (test config), and the test files. Nothing was renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo 0.8.15 (Gradle `jacoco` plugin, Gradle
  9.8.1); `./gradlew test --rerun --tests 'ch.sonensei.cockpit.gtd.*' jacocoTestReport`; working directory
  `server/`; the selection is the gtd package (17 suites, 224 tests).
- Run start/completion time and exit status: 2026-10-20T20:59:22+02:00 to 2026-10-20T21:00:11+02:00, exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (sha256 bc7cb86a6f4ac5899fe1dfc44fe3e48426aca6690a3089c24efb52fb872803fc, written 21:00:11),
  `server/build/jacoco/test.exec` (sha256 8044867b968d2d35cd05f7de6cc0ed982104d1f92b1480879ae4d0ffa536d622).
- Evidence outputs are fresh and inputs stayed unchanged: `--rerun` forced the test task to run, and the report
  timestamp is the end of this run. The test-file hashes were checked again after the run (`sha256sum -c`: all OK),
  and `git status` showed no production changes. The later `.taskcheck` run (`./gradlew check`) does not regenerate
  the JaCoCo XML used here.
- Final run after latest test additions and production fixes: yes (no production fixes were made; this run includes
  the last added test).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Statements = JaCoCo
INSTRUCTION counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/TaskController.kt` | 530/537 (98.7%) | 26/28 (92.9%) | 18/20 (90.0%) | 81/82 (98.8%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/TaskService.kt` | 2204/2214 (99.5%) | 266/269 (98.9%) | 38/38 (100.0%) | 258/258 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/TicklerJob.kt` | 40/40 (100.0%) | 2/2 (100.0%) | 2/2 (100.0%) | 10/10 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/infra/GtdSchedulingConfiguration.kt` | 55/55 (100.0%) | N/A | 5/5 (100.0%) | 11/11 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/infra/GtdRepositories.kt` | N/A | N/A | N/A | N/A |
| Aggregate (summed counters, 4 measured files; GtdRepositories.kt N/A as above, no files missing) | 2829/2846 (99.4%) | 294/299 (98.3%) | 63/65 (96.9%) | 360/361 (99.7%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `TaskService.kt:304,306` (first run: `moveTo` with the task already on the target list) | the place of a released tickler that was put away from the Inbox | Added `a tickler put away from the Inbox comes back to its own place in the Inbox`; covered in the final run |
| `TaskController.kt:149` (create retry after a concurrent duplicate create) | pre-existing idempotent create, not changed by this task | Left uncovered: outside the task scope |
| `TaskController.kt:341,360` (companion constants) | pre-existing, not changed | Left uncovered: not material |
| `TaskService.kt:102` (missing title on create), `:325` (`recurrenceMode` null in next occurrence) | pre-existing, not changed by this task | Left uncovered: outside the task scope |

## Unable to test

- The real Spring scheduler firing `TicklerJob.run()` at wall-clock 00:05. The tests disable the scheduler
  (`cockpit.gtd.tickler.scheduler.enabled=false`). Instead they check the trigger's planned times (user zone, zone
  change, both DST switches, once per day), that the configuration registers exactly one trigger task running the job,
  and that the property switches the configuration on and off. Waiting for a real 00:05 is not practical. No follow-up
  needed unless the parent wants a manual check.
- Two releases running at the same time (job and on-read check, or two reads), where one of them hits an
  optimistic-lock failure. This can't be made deterministic with the existing infrastructure. The swallow-and-retry
  behavior of `TicklerJob` is unit tested with a mocked `OptimisticLockingFailureException`. Follow-up is optional.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. 22 tests added, all validation passed
(gtd package 224/224, `.taskcheck` passed). The test files and this report are not committed. The test-writer role
does not commit, so the parent worker should verify and commit them.
