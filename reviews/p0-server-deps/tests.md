# Test Report

Date: 2026-10-20

Task: p0-server-deps - Server dependencies, profiles and test infrastructure

Scope: `git diff 7b924a409a95caed4378653a810545cb123782c7..dd660efb7575acc337a17edd77f6556c3bd18e05`
(branch `main`, HEAD `dd660efb7575acc337a17edd77f6556c3bd18e05`; task commit `dd660ef` plus four
`assume:` commits that touch only `tasks/`). The parent named the task commit `4733c89`; HEAD now holds
the same change as `dd660ef` (after rebase), and its diff matches the change list the parent supplied.
Uncommitted inputs at measurement time: only the test files listed below (written by this helper).

Requirements:
- `tasks/p0-server-deps.md` acceptance criteria and recorded assumptions
- `AGENTS.md` project notes, `docs/ARCHITECTURE.md` "Testing" and "Coverage", `docs/QUALITY.md`
- Parent instructions (keep `check` fast, no test migrations in `src/main`)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/support/FlywayConfigurationTests.kt` (new)
- `server/src/test/kotlin/ch/sonensei/cockpit/support/DatasourcePropertiesTests.kt` (new)
- `server/src/test/kotlin/ch/sonensei/cockpit/support/PostgresTestBaseTests.kt` (extended)
- `server/src/test/kotlin/ch/sonensei/cockpit/support/ProfileTests.kt` (extended)
- `server/src/test/resources/ch/sonensei/cockpit/support/flyway/base/gtd/V202610200900__gtd_fixture_later.sql` (new test fixture)
- `server/src/test/resources/ch/sonensei/cockpit/support/flyway/late/calendar/V202610200800__calendar_fixture_earlier.sql` (new test fixture)

The fixture migrations live on the test classpath under a package path, not under `db/migration`, so
they are never picked up by the application's Flyway; the test applies them in a throwaway schema
(`flyway_fixture`, dropped afterwards).

## Tests added or updated

Count: 11 (9 new test methods, 1 existing test method extended, 1 new nested context)

- `FlywayConfigurationTests`: `application Flyway scans db migration and allows out-of-order migrations`;
  `module subfolders are scanned and a later-merged older migration is still applied` (copies the
  application's Flyway configuration, migrates `base/gtd/V202610200900`, then adds `late/calendar/V202610200800`
  and asserts it is applied out of order). Mutation check: forcing `.outOfOrder(false)` in the test copy made
  this test fail; reverted.
- `DatasourcePropertiesTests` (plain unit test, no Spring context): base config takes URL/username/password
  from `SPRING_DATASOURCE_*`; base fallbacks (localhost, user `cockpit`, empty password); dev and e2e profiles
  also honour the variables; e2e defaults to `cockpit_e2e`.
- `PostgresTestBaseTests`: new `datasource uses the running shared container through the service connection`;
  `Jackson writes dates as ISO-8601 strings` extended with `OffsetDateTime` and `Duration`.
- `ProfileTests`: new nested `Default` context (`default profile logs JSON in Logstash format`); new
  `service connection overrides the dev profile's local datasource` and
  `service connection overrides the e2e profile's datasource` (the profile files set their own URLs; the
  test proves `@ServiceConnection` wins and every context uses the one shared container).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: build declares all listed libraries; `./gradlew dependencies` resolves | Not a test concern; the test source set compiles against and runs Kotest, WireMock, GreenMail, Testcontainers and the Flyway API | `cd server && ./gradlew ktlintCheck test --rerun integrationTest --rerun` | Pass (compiles and runs). `./gradlew dependencies` itself was not run by this helper: the parent records it |
| AC2: datasource from `SPRING_DATASOURCE_*` | `DatasourcePropertiesTests` (4 tests) | same | Pass |
| AC2: Flyway `out-of-order=true`, `locations=classpath:db/migration` (subfolders scanned) | `FlywayConfigurationTests` (2 tests), `PostgresTestBaseTests.Flyway ran against the container` | same | Pass |
| AC2: JSON logging in non-dev profiles | `ProfileTests$Default`, `ProfileTests$E2e.e2e profile starts with JSON logs`, `ProfileTests$Dev.dev profile starts with plain-text logs`; JSON lines (`"@timestamp"`) seen in the default/e2e test output | same | Pass |
| AC2: Jackson ISO dates | `PostgresTestBaseTests.Jackson writes dates as ISO-8601 strings` | same | Pass |
| AC2: profiles `dev` and `e2e` exist | `ProfileTests$Dev`, `ProfileTests$E2e` | same | Pass |
| AC3: one shared Postgres 16 container per JVM; sample `@SpringBootTest` passes on Podman | `PostgresTestBaseTests` (4), `ProfileTests` datasource-override tests, `CockpitServerApplicationTests`, `HealthEndpointTests` | same, on Podman | Pass |
| AC4: `integrationTest` runs only `external` tests | `ExternalToolingSmokeTests` (2) ran in `integrationTest`; `build/test-results/integrationTest` holds only that suite, `build/test-results/test` holds 8 suites / 17 tests and no external suite | same | Pass |
| AC4: `check` < 60 s warm | n/a | `cd server && time ./gradlew --quiet check test --rerun` | Pass: 11.1 s real (tests forced to rerun) |
| AC5: ARCHITECTURE.md updated | Documentation, not testable by unit tests | n/a | Not testable |

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*FlywayConfigurationTests'` and `cd server && ./gradlew ktlintCheck test`

Result:

Pass (17 tests in `test`, 0 failures, 0 skipped)

## Full test execution

Command:

`cd server && ./gradlew ktlintCheck test --rerun integrationTest --rerun`

Result:

Pass (BUILD SUCCESSFUL in 13 s; `test`: 17 tests, `integrationTest`: 2 tests, all passing)

## .taskcheck

Result:

Not run: this helper's sandbox refused permission to execute `sh scripts/check.sh`. Its server step
(`./gradlew check`) passed separately (see above). The parent must run `.taskcheck`.

## Bugs found

None.

Observation (not a defect, no action required): once a `dev`-profile context has initialized logging,
the shared logging system stays in plain-text mode for later log lines in the same test JVM (seen in
shutdown-hook lines). This only affects test output, not runtime behaviour.

## Coverage

Status: not applicable for production Kotlin; the run itself was verified (fresh, exit 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no executable production lines changed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p0-server-deps`, base
  `7b924a409a95caed4378653a810545cb123782c7`, HEAD `dd660efb7575acc337a17edd77f6556c3bd18e05`; task-owned
  commits `dd660ef` (code) and `dc9a7e0`, `27def89`, `9c05056`, `536971d` (`tasks/` only), from the parent and
  `git log 7b924a4..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `dd660ef` plus uncommitted test inputs
  (sha256): `DatasourcePropertiesTests.kt` e225c04f..., `FlywayConfigurationTests.kt` 7f7ac55e...,
  `PostgresTestBaseTests.kt` baa81f0b..., `ProfileTests.kt` 7ffc1380..., fixture SQL a3413f61... (gtd) and
  5875c9b6... (calendar). Unchanged during the run.
- Exact included files; renamed/deleted/excluded files and reasons: `git diff --name-only 7b924a4..HEAD -- server/src/main`
  lists only `application.properties`, `application-dev.properties`, `application-e2e.properties`. These are
  configuration with no JaCoCo-instrumentable code; their behaviour is covered by the behavioural tests in the
  table above. `server/build.gradle.kts`, test sources and `docs/ARCHITECTURE.md` are excluded (build config,
  tests, docs). No production Kotlin file was added or changed; no renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via Gradle `jacoco` plugin;
  `./gradlew test --rerun jacocoTestReport` in `server/`; full `test` task (excludes tag `external`).
- Run start/completion time and exit status: 2026-10-20T10:24:53+02:00 to 2026-10-20T10:25:05+02:00, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (sha256 61836d06fccfe447d761f0d525367bbb7940d9dd13833ca1724b94a44ff70c11, session `dell26-d703eff8`), HTML in
  `server/build/reports/jacoco/test/html/`.
- Evidence outputs are fresh and inputs stayed unchanged: yes; `--rerun` forced test execution, the report's
  modification time (10:25:05) matches the run, and no source/test input changed during it.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/resources/application.properties` | N/A (not instrumentable) | N/A | N/A | N/A |
| `server/src/main/resources/application-dev.properties` | N/A (not instrumentable) | N/A | N/A | N/A |
| `server/src/main/resources/application-e2e.properties` | N/A (not instrumentable) | N/A | N/A | N/A |

No aggregate: the task coverage scope contains no instrumentable production code. For reference only (outside
the task scope, unchanged by the task), the repository's only production class `CockpitServerApplication.kt` has
3/12 instructions, 1/3 lines, 1/2 methods covered; the missed `main` function is bootstrap code.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| none in task scope | Config behaviour (datasource variables, Flyway out-of-order/subfolders, JSON logs, ISO dates, profiles) | Covered behaviourally by `DatasourcePropertiesTests`, `FlywayConfigurationTests`, `ProfileTests`, `PostgresTestBaseTests` |

## Unable to test

- `.taskcheck` (`sh scripts/check.sh`): not executed, the helper's sandbox denied permission. Follow-up: parent runs it.
- `./gradlew dependencies` resolution (AC1): left to the parent; compilation and both test tasks resolving the
  test runtime classpath is indirect evidence only.
- AC5 (ARCHITECTURE.md content): documentation, reviewed by the code-reviewer, not testable.

## Summary

Tests added and all validation passed (server `ktlintCheck`, `test`, `integrationTest`; `.taskcheck` left to
the parent). No production defects found. Task-scoped coverage is not applicable because the task changed no
instrumentable production code.
