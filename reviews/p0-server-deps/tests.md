# Test Report

Date: 2026-10-20

Task: p0-server-deps - Server dependencies, profiles and test infrastructure

Scope: re-test of the rebased final revision. `git diff 18122fd02d23a9a1dddcf2568d9c4057f35fee7f..50e57c0e19cb90fd43b7d8be4eb9d9c5208b2ab7`
(task commits `git log 18122fd..HEAD`: `2411068`, `e1ee084`, `2d79e4f`, `ae008e6`, `5506575`, `fb49b54`, `8f7823f`,
`50e57c0`), as supplied by the parent. Code in scope: `server/build.gradle.kts`, the three `application*.properties`,
`server/src/test/kotlin/ch/sonensei/cockpit/support/*` plus two adjusted context tests and two Flyway fixture
migrations, `docs/ARCHITECTURE.md`, and the one-line env filter in `tests/test_quality_gate.py` (commit `50e57c0`).
Branch `main`, HEAD `50e57c0`. No uncommitted source or test changes; the only working-tree change during the
runs was `reviews/p0-server-deps/review.md` (written by the parallel code-reviewer, not a test input).

Requirements:
- `tasks/p0-server-deps.md` acceptance criteria, confirmed assumptions and the TASKS_ROOT note
- `AGENTS.md` project notes, `docs/ARCHITECTURE.md` "Testing" and "Coverage", `docs/QUALITY.md`
- Previous report for pre-rebase revision `f01b7c1` (superseded by this one)
- Parent instructions (re-assess on the current code; Docker Desktop is not running)

## Test files changed

- None. The existing tests already cover every testable criterion and the review fixes (see the table below).
  The rebase did not change any test or production file contents relative to the previously tested
  revision apart from the `tests/test_quality_gate.py` fix, which is itself test code.

## Tests added or updated

Count: 0

Assessment of the new `tests/test_quality_gate.py` change: `tasks.py` line 23 resolves
`ROOT = Path(os.environ.get("TASKS_ROOT", ".")).resolve()`. The old filter dropped only `TASK_*` variables, so a
runner-provided `TASKS_ROOT` leaked into the subprocess and pointed the gate tests at the real clone. The new
filter `k.startswith(("TASK_", "TASKS_"))` removes it, so `tasks.py` falls back to the per-test temp `cwd`.
Correct by inspection; it is a test-harness fix with no production behavior, so no further test was added.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: build declares all listed libraries; dependencies resolve | Indirect: main and test source sets compile against Kotest, WireMock, GreenMail, Testcontainers, Flyway, Jakarta Persistence | `cd server && ./gradlew check --rerun-tasks` (compile + ktlint steps) | Pass for compilation and ktlint; `./gradlew dependencies` not run by this helper (parent records it) |
| CR-1 fix: `plugin.jpa` + `allOpen` for Entity/MappedSuperclass/Embeddable | `JpaCompilerPluginTests` (4) | `cd server && ./gradlew test --tests '*DatasourcePropertiesTests' --tests '*JpaCompilerPluginTests' jacocoTestReport --rerun-tasks` | Pass (4/4) |
| AC2: datasource from `SPRING_DATASOURCE_*` (base, dev, e2e) | `DatasourcePropertiesTests` (4) | same | Pass (4/4) |
| AC2: Flyway `out-of-order=true`, `locations=classpath:db/migration` incl. subfolders | `FlywayConfigurationTests` (2), `PostgresTestBaseTests.Flyway ran against the container` | `cd server && ./gradlew check --rerun-tasks` | Fail (environment): `initializationError`, Testcontainers "Could not find a valid Docker environment" |
| AC2: JSON logging in non-dev profiles; profiles `dev`/`e2e` exist | `ProfileTests$Default` (1), `$Dev` (2), `$E2e` (2) | same | Fail (environment): same Docker error |
| AC2: Jackson ISO dates | `PostgresTestBaseTests.Jackson writes dates as ISO-8601 strings` | same | Fail (environment): same Docker error |
| AC3: one shared Postgres 16 container per JVM; sample `@SpringBootTest` passes | `PostgresTestBaseTests` (4), `CockpitServerApplicationTests`, `HealthEndpointTests`, `ProfileTests` datasource-override tests | same | Fail (environment): Docker Desktop not running, no Podman socket reachable from this helper |
| CR-2 fix: GreenMail on a dynamic port | `ExternalToolingSmokeTests.GreenMail receives a message over SMTP` | `cd server && ./gradlew integrationTest --rerun-tasks` | Pass |
| AC4: `integrationTest` runs only `external` tests and is not part of `check` | `build/test-results/integrationTest` contains only `ExternalToolingSmokeTests` (2 tests, 0 failures); the `check` run's task list has no `integrationTest` and `build/test-results/test` holds 9 suites with no external suite | `./gradlew integrationTest --rerun-tasks`; `./gradlew check --rerun-tasks --console=plain` | Pass |
| AC4: `check` < 60 s warm | n/a | `./gradlew check --rerun-tasks` | Not run meaningfully: the run failed in 20 s because the container tests could not start, so it is no timing evidence |
| AC5: ARCHITECTURE.md updated | Documentation | n/a | Not testable |
| TASKS_ROOT filter in `tests/test_quality_gate.py` | the whole `Gate` suite | `python3 tests/test_quality_gate.py -q` / `python3 -m unittest ...` | Not run: this helper's sandbox requires approval for these commands; `pytest` is not installed. Parent must run it (it is the first step of `.taskcheck`) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*DatasourcePropertiesTests' --tests '*JpaCompilerPluginTests' jacocoTestReport --rerun-tasks --console=plain`

Result:

Pass (BUILD SUCCESSFUL in 3 s; `DatasourcePropertiesTests` 4/4, `JpaCompilerPluginTests` 4/4, 0 failures/errors/skipped).

Also: `cd server && ./gradlew integrationTest --rerun-tasks --console=plain` -> BUILD SUCCESSFUL in 4 s;
`ExternalToolingSmokeTests` 2 tests, 0 failures/errors (GreenMail and WireMock need no container runtime).

## Full test execution

Command:

`cd server && ./gradlew check --rerun-tasks --console=plain`

Result:

Fail (environment, not a production defect). ktlint (main, test, scripts) and compilation passed. `test`: 15 tests,
7 failed, all with `initializationError` caused by
`java.lang.IllegalStateException: Could not find a valid Docker environment` from
`DockerClientProviderStrategy` while initializing `PostgresTestBase.postgres` (the first class fails with
`ExceptionInInitializerError`, the rest with `NoClassDefFoundError` of the same companion). Affected:
`CockpitServerApplicationTests`, `HealthEndpointTests`, `FlywayConfigurationTests`, `PostgresTestBaseTests`,
`ProfileTests$Default/$Dev/$E2e`. Passing: `DatasourcePropertiesTests` 4, `JpaCompilerPluginTests` 4.
`docker info` at the same time: "Cannot connect to the Docker daemon at unix:///Users/peterguertler/.docker/run/docker.sock";
`podman info` could not be run from this helper (approval required).

These suites passed (21/21) on the pre-rebase revision on Podman per the previous report, and the rebase did
not change them, but that is not evidence for revision `50e57c0`. They must be re-run with a working
Docker/Podman before the AC2/AC3/AC4 timing evidence is recorded for this revision.

## .taskcheck

Result:

Not run: `sh scripts/check.sh` requires approval in this helper's sandbox. It would also fail here at the
`server: ktlint + tests` step for the Docker reason above. The parent must run it with a container runtime available.

## Bugs found

None. All failures are environmental (no container runtime).

## Coverage

Status: not applicable for production code (the task changed no JaCoCo-instrumentable production file). The
JaCoCo run itself is fresh and exit 0, but it covers only the Docker-free subset of the suite.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no executable production lines changed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p0-server-deps`, base
  `18122fd02d23a9a1dddcf2568d9c4057f35fee7f`, HEAD `50e57c0e19cb90fd43b7d8be4eb9d9c5208b2ab7`, commits from
  `git log 18122fd..HEAD` as supplied by the parent and matched against the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `50e57c0`, no uncommitted source/test
  changes (`git status --porcelain` empty at 08:41:08Z; at 08:42:17Z only `reviews/p0-server-deps/review.md`
  modified by the parallel reviewer).
- Exact included files; renamed/deleted/excluded files and reasons: `git diff --name-only 18122fd..HEAD -- server/src/main web/src android/app/src/main`
  -> `server/src/main/resources/application.properties`, `application-dev.properties`, `application-e2e.properties`:
  configuration, not instrumentable, covered behaviourally (table above). Excluded: `server/build.gradle.kts`
  (build config; the CR-1 effect is covered by `JpaCompilerPluginTests`), `tests/test_quality_gate.py` (test
  code), test sources/resources, `docs/`, `reviews/`, `tasks/`. No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin;
  `./gradlew test --tests '*DatasourcePropertiesTests' --tests '*JpaCompilerPluginTests' jacocoTestReport --rerun-tasks`
  in `server/`. The full `test` + `jacocoTestReport` could not produce a report because `test` fails without Docker.
- Run start/completion time and exit status: 2026-10-20 ~08:41:50Z (JaCoCo session start 1792485710275 ms),
  ~3 s, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`,
  session `R1963MACOS.local-8031bd8e` (file hash not taken: `shasum` needs approval here).
- Evidence outputs are fresh and inputs stayed unchanged: yes (`--rerun-tasks`, session id from this run,
  snapshot unchanged).
- Final run after latest test additions and production fixes: yes (no changes since `50e57c0`).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/resources/application.properties` | N/A (not instrumentable) | N/A | N/A | N/A |
| `server/src/main/resources/application-dev.properties` | N/A (not instrumentable) | N/A | N/A | N/A |
| `server/src/main/resources/application-e2e.properties` | N/A (not instrumentable) | N/A | N/A | N/A |

No aggregate: the task coverage scope contains no instrumentable production code. The report lists only
`CockpitServerApplication.kt`, which the task did not change.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| none in task scope | Config behaviour (Flyway, JSON logs, ISO dates, profiles, shared container) | Tests exist (`FlywayConfigurationTests`, `ProfileTests`, `PostgresTestBaseTests`) but could not run on this revision without a container runtime; re-run required |

## Unable to test

- All Postgres-backed `@SpringBootTest` suites (AC2 Flyway/logging/Jackson parts, AC3, the warm `check` timing
  of AC4): Docker Desktop is not running and no Podman socket was usable. Follow-up: parent re-runs
  `cd server && ./gradlew check` with Docker or Podman (`DOCKER_HOST`, `TESTCONTAINERS_RYUK_DISABLED` per
  ARCHITECTURE.md) and records the result and time.
- `tests/test_quality_gate.py` and `.taskcheck`: commands need approval in this helper's sandbox. Follow-up: parent runs both.
- `./gradlew dependencies` (AC1): left to the parent.
- AC5: documentation, not testable.

## Summary

No additional tests were necessary for the reviewed scope; no production defects found. Validation is
incomplete because of the environment: the Docker-free unit tests (8/8), ktlint and `integrationTest` (2/2)
pass on `50e57c0`, but the 7 Testcontainers-based suites fail to start without a container runtime, and the
quality-gate Python tests and `.taskcheck` were not run. These must be re-run by the parent with Docker/Podman
before the task is completed.
