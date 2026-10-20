# Test Report

Date: 2026-10-20

Task: gtd-tickler - Tickler and scheduled tasks job

Scope: `git diff 4d86a3771f810407062f496857260f99d8a13dc5..ce9907782cc99a0f0ebc8a0ff43405b13c50e3fc` (final revision,
re-run after the review fixes). Task commits: `4d181e2c` (implementation), `c5da2943` (edge-case tests and the first test
report), `24714dcb` (review report), `ce990778` (fixes for CR-1, the KDoc link in `TaskService.kt`, and CR-2, the
`getTask` OpenAPI description in `TaskController.kt` plus the regenerated `api/openapi.yaml`). Branch `main`, working
tree clean when the run started.

Requirements:
- `tasks/gtd-tickler.md` acceptance criteria 1-3 and the recorded assumptions (Notes)
- `SPEC.md` §5.1 (Scheduled / Tickler rows) and Phase 1 acceptance scenario 5
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`
- `reviews/gtd-tickler/review.md` and `fixes.md` (CR-1, CR-2)

## Test files changed

None in this re-run. The tests from the first round are committed in `c5da2943` and were not changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/TicklerWebTests.kt` (8 tests, implementation commit)
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/TicklerEdgeCaseTests.kt` (14 tests)
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/TicklerJobTests.kt` (5 tests)
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/UserZoneCronTriggerTests.kt` (5 tests)

## Tests added or updated

Count: 0

`ce990778` changes no behavior. It changes a KDoc link and adds an `@Operation` description. The description is checked
by the existing `OpenApiContractTest` (`api openapi yaml matches the generated contract`), which compares the committed
`api/openapi.yaml` with the contract the server generates. That test passes, so the regenerated contract matches the
code. A new test would only repeat it.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: tasks with `ticklerDate` are left out of all list queries until that date | TicklerWebTests `a task in a tickler is hidden from every list, and two days later the job puts it back in the Inbox`; TicklerEdgeCaseTests filter, paging, Logbook, inclusive-date and `reappears in every list query` tests | `cd server && ./gradlew cleanTest test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest' jacocoTestReport` | Pass |
| AC2: job at 00:05 in the user's local time | UserZoneCronTriggerTests (5, DST included); TicklerJobTests `the schedule registers one trigger task ...`, `on by default`, `off when the scheduler is disabled` | same | Pass (the trigger and its registration are tested. The real scheduler firing at wall-clock 00:05 was not observed, see "Unable to test") |
| AC2: due ticklers go to INBOX, due SCHEDULED tasks go to NEXT, ChangeEvents UPDATED | TicklerWebTests (scheduled, tickler, cleared fields, done/deleted untouched, user zone); TicklerEdgeCaseTests positions, project tasks, ordering, idempotency, version | same | Pass |
| AC2: on-read check (GET list and GET by id) | TicklerWebTests `reading the tasks releases a due tickler when the job has not run`; TicklerEdgeCaseTests `reading a single task releases it ...`, `... not due leaves it in the tickler`; TicklerJobTests `a failing run is swallowed ...` | same | Pass |
| AC3: `./gradlew test --tests 'ch.sonensei.cockpit.gtd.*'` passes on Postgres via Testcontainers; the test clock advanced 2 days puts the task back in the Inbox | the whole gtd package, 17 suites (the first TicklerWebTests test is scenario 5) | same | Pass: 224 gtd tests, 0 failures/errors/skipped |
| CR-2: the `getTask` description is in the committed contract | OpenApiContractTest `api openapi yaml matches the generated contract` | same | Pass (1/1) |

## Focused test execution

Command:

`cd server && ./gradlew cleanTest test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest' jacocoTestReport`

Result:

Pass: 18 suites, 225 tests (224 gtd + 1 OpenApiContractTest), 0 failures, 0 errors, 0 skipped. BUILD SUCCESSFUL,
2026-10-20T21:15:19+02:00 to 21:15:59+02:00.

Note: the first attempt (`./gradlew test --rerun ...`) failed before any test ran, with `java.io.EOFException` /
`KryoException: Buffer underflow` in `Test.getPreviousFailedTestClasses`. Gradle's binary test-result store from an
earlier run was truncated. Running Gradle's own `cleanTest` task, which only removes the test task outputs, fixed it.
This was a build-cache problem, not a test failure.

## Full test execution

Command:

`sh scripts/check.sh` (`.taskcheck`; it includes `cd server && ./gradlew --quiet check`, the whole server suite with ktlint)

Result:

Pass: the server ran 160 suites and 1857 tests with 0 failures, 0 errors and 0 skipped. The web and Android checks passed
too, and the script ended with "all checks passed".

## .taskcheck

Result:

Pass (`sh scripts/check.sh`, 2026-10-20T21:16:44+02:00 to 21:21:51+02:00, "== all checks passed").

## Bugs found

None. No test failed against the final production code.

The observations from the first round still apply. They are not defects of this task, and the parent recorded them as
assumptions:
- SPEC scenario 5 creates a task "with tickler = today + 2 days". `CreateTaskRequest` has no `ticklerDate`, so the tests
  set the tickler with create + `POST /tasks/{id}/move`.
- A released tickler that was put away from the Inbox keeps its old Inbox position. A test documents this.

## Coverage

Status: verified (fresh run on the final revision `ce990778`, BUILD SUCCESSFUL).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. I took the executable lines from JaCoCo's XML per-line entries and kept those that
`git diff -U0 4d86a377..HEAD` adds or modifies in each file. All 48 changed executable lines are covered
(TaskController 4/4, TaskService 23/23, TicklerJob 10/10, GtdSchedulingConfiguration 11/11). All 8 branches on changed
lines are covered (TaskService 6/6, TicklerJob 2/2).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `gtd-tickler`; base
  `4d86a3771f810407062f496857260f99d8a13dc5` (from the parent); HEAD `ce9907782cc99a0f0ebc8a0ff43405b13c50e3fc`;
  task commits `4d181e2c`, `c5da2943`, `24714dcb`, `ce990778` (`git log 4d86a377..HEAD`). The file scope is
  `git diff --name-only 4d86a377..HEAD -- server/src/main`, which matches the task `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `ce990778`, with no uncommitted or untracked
  source or test changes (`git status --short` was empty before and after the run). Test file sha256:
  `TicklerEdgeCaseTests.kt` 8dab906d991a59be11a5f1a43123e1f6a12413be9f991a77af95ea2d23eaffc8,
  `TicklerJobTests.kt` f1e4b3ca3f1820cd1f7f3a7de32028e41c1851c60c4c82c678a83917009d3330,
  `TicklerWebTests.kt` 09d446ebfb256d4690d5e3bef744b1059730b917c8f708738bbff7a2a476a679,
  `UserZoneCronTriggerTests.kt` d3e8893ca8259b5a430fd018a17d512f2f87ac3b575fbc807c86fe664770a7f7.
- Exact included files; renamed/deleted/excluded files and reasons: included are
  `server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/TaskController.kt`, `.../gtd/domain/TaskService.kt`,
  `.../gtd/domain/TicklerJob.kt`, `.../gtd/infra/GtdSchedulingConfiguration.kt` and `.../gtd/infra/GtdRepositories.kt`.
  `GtdRepositories.kt` is in the report but has no counters: it holds only Spring Data interfaces and `@Query` strings,
  so it is N/A. The web tests run its two new queries against Postgres. Excluded: `api/openapi.yaml` (generated
  contract, checked by OpenApiContractTest), `server/src/test/resources/config/application.properties` (test config),
  the test files and `reviews/`. Nothing was renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo 0.8.15 (Gradle `jacoco` plugin, Gradle 9.8.1);
  `./gradlew cleanTest test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest' jacocoTestReport`, run in
  `server/`; the selection is the gtd package plus OpenApiContractTest (18 suites, 225 tests).
- Run start/completion time and exit status: 2026-10-20T21:15:19+02:00 to 2026-10-20T21:15:59+02:00, BUILD SUCCESSFUL.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (sha256 d8d197e947f78a5cafd78530b693ef5947cb1f5f5e28e05778b4219235db0270, written 21:15:59),
  `server/build/jacoco/test.exec` (sha256 d45622606f8c5fddc5c6bddd980d8fafaa4f41c51790fa0f2aa4f380704cdfb7, at report
  time).
- Evidence outputs are fresh and inputs stayed unchanged: `cleanTest` removed the previous test outputs, and the test
  task ran (3 tasks executed). The report timestamp is the end of this run, and its hash differs from the first round's
  report (bc7cb86a...). `git status` was clean after the run. The later `.taskcheck` run did not regenerate the XML
  report: its hash was the same afterwards.
- Final run after latest test additions and production fixes: yes. This run measures `ce990778`, the commit with the
  CR-1/CR-2 fixes. No tests were added afterwards.

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
| `TaskController.kt:149` (create retry after a concurrent duplicate create, 2 branches) | an idempotent create that existed before this task and that it did not change | Left uncovered: outside the task scope |
| `TaskController.kt:347,366` (companion constants; lines 341 and 360 before the CR-2 fix moved them) | existed before this task, unchanged | Left uncovered: not material |
| `TaskService.kt:102` (missing title on create), `:325` (`recurrenceMode` null in the next occurrence) | existed before this task, unchanged | Left uncovered: outside the task scope |

## Unable to test

- The real Spring scheduler firing `TicklerJob.run()` at wall-clock 00:05. The tests turn the scheduler off
  (`cockpit.gtd.tickler.scheduler.enabled=false`). Instead they check the trigger's planned times (user zone, zone
  change, both DST switches, once per day), that the configuration registers exactly one trigger task that runs the
  job, and that the property switches the configuration on and off. Waiting for a real 00:05 is not practical. No
  follow-up is needed unless the parent wants a manual check.
- Two releases running at the same time (the job and the on-read check, or two reads), where one hits an
  optimistic-lock failure. The existing tools can't make this deterministic. A unit test covers `TicklerJob`'s
  swallow-and-retry behavior with a mocked `OptimisticLockingFailureException`. Follow-up is optional.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The re-run on the final revision
`ce990778` needed no new tests. The CR-1/CR-2 fixes change only documentation, and the existing OpenApiContractTest
checks the contract change. The gtd package and the contract test pass (225/225), changed-line coverage is 48/48 lines
and 8/8 branches, and `.taskcheck` passed (server 1857/1857). The parent worker should verify and commit this report.
