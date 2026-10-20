# Test Report

Date: 2026-10-20

Task: p0-server-deps - Server dependencies, profiles and test infrastructure

Scope: final revision after review fixes. `git diff 7b924a409a95caed4378653a810545cb123782c7..f01b7c1fcd074f06d5342f1d94fe50ee9f0a532d`
restricted to the task's code commits `2d3e527` (libraries, profiles, Postgres Testcontainer base,
`integrationTest`), `4364631` (Flyway/datasource/profile tests) and `45fdd8e` (fixes CR-1..CR-3: `kotlin("plugin.jpa")`
+ `allOpen` in `server/build.gradle.kts`, GreenMail dynamic port in `ExternalToolingSmokeTests`, schema-scoped query in
`PostgresTestBaseTests`). Commits `e0b6dda` and `f01b7c1` touch only `reviews/`; the `tasks/`-only bookkeeping commits
and the human-authored dashboard commit (`dashboard/`, `TEAM.md`) are excluded. Branch `main`, HEAD `f01b7c1`.
Uncommitted inputs: two untracked test files (listed below), no other working-tree changes.

Requirements:
- `tasks/p0-server-deps.md` acceptance criteria and the four confirmed assumptions
- `reviews/p0-server-deps/review.md` (CR-1..CR-4) and `reviews/p0-server-deps/fixes.md`
- `AGENTS.md` project notes, `docs/ARCHITECTURE.md` "Testing" and "Coverage", `docs/QUALITY.md`
- Parent instructions (cover the CR-1 fix, check the other fixes, re-measure on the final code)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/support/JpaCompilerPluginTests.kt` (new, untracked)
- `server/src/test/kotlin/ch/sonensei/cockpit/support/JpaPluginFixtures.kt` (new, untracked; fixtures in package
  `ch.sonensei.jpafixture`, outside the application's `ch.sonensei.cockpit` entity scan, so `ddl-auto=validate`
  never looks for their tables)

Both files were left untracked by an interrupted earlier run of this helper. Reviewed and adjusted in this run:
removed a deprecated `isAccessible` call (the plugin-generated constructor is public), replaced the nullable check
with a boolean helper, and added a negative control class/test.

Already committed in the task and re-run here unchanged: `FlywayConfigurationTests`, `DatasourcePropertiesTests`,
`PostgresTestBaseTests`, `ProfileTests`, `ExternalToolingSmokeTests`, `CockpitServerApplicationTests`,
`HealthEndpointTests`, and the two Flyway fixture migrations under `server/src/test/resources/ch/sonensei/cockpit/support/flyway/`.

## Tests added or updated

Count: 4 (new test methods in `JpaCompilerPluginTests`)

- `JPA-annotated Kotlin classes get a no-arg constructor` (`@Entity`, `@MappedSuperclass`, `@Embeddable` fixtures)
- `JPA-annotated Kotlin classes are open for Hibernate proxies`
- `an entity can be instantiated through its no-arg constructor`
- `classes without JPA annotations stay final and keep their constructors` (control: an unannotated Kotlin class
  in the same file is final and has no no-arg constructor, so the assertions above would fail if `plugin.jpa` or the
  `allOpen` block were removed, and `allOpen` is shown to be scoped to the JPA annotations)

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: build declares all listed libraries; `./gradlew dependencies` resolves | Not a test concern; the test source set compiles and runs against Kotest, WireMock, GreenMail, Testcontainers, Flyway and Jakarta Persistence | `cd server && ./gradlew ktlintCheck test jacocoTestReport integrationTest --rerun-tasks` | Pass (compiles and runs). `./gradlew dependencies` not run by this helper; parent records it |
| CR-1 fix: `plugin.jpa` + `allOpen` for Entity/MappedSuperclass/Embeddable | `JpaCompilerPluginTests` (4) | same | Pass |
| CR-2 fix: GreenMail on a dynamic port | `ExternalToolingSmokeTests.GreenMail receives a message over SMTP` (sends via `greenMail.smtp.serverSetup`) | same (`integrationTest`) | Pass |
| CR-3 fix: Flyway history count scoped to `current_schema()` | `PostgresTestBaseTests.Flyway ran against the container`, run in the same JVM after `FlywayConfigurationTests` created its `flyway_fixture` schema | same | Pass |
| AC2: datasource from `SPRING_DATASOURCE_*` | `DatasourcePropertiesTests` (4) | same | Pass |
| AC2: Flyway `out-of-order=true`, `locations=classpath:db/migration` incl. module subfolders | `FlywayConfigurationTests` (2), `PostgresTestBaseTests.Flyway ran against the container` | same | Pass |
| AC2: JSON logging in non-dev profiles | `ProfileTests$Default` (1), `ProfileTests$E2e` (2), `ProfileTests$Dev` (2) | same | Pass |
| AC2: Jackson ISO dates | `PostgresTestBaseTests.Jackson writes dates as ISO-8601 strings` | same | Pass |
| AC2: profiles `dev` and `e2e` exist | `ProfileTests$Dev`, `ProfileTests$E2e` | same | Pass |
| AC3: one shared Postgres 16 container per JVM; sample `@SpringBootTest` passes on Podman | `PostgresTestBaseTests` (4), `ProfileTests` datasource-override tests, `CockpitServerApplicationTests`, `HealthEndpointTests` | same; Testcontainers log: `Resolved dockerHost=unix:///run/user/1000/podman/podman.sock`, `Server Version: 5.8.9` (Podman) | Pass |
| AC4: `integrationTest` runs only `external` tests | `build/test-results/integrationTest` holds only `ExternalToolingSmokeTests` (2 tests); `build/test-results/test` holds 9 suites / 21 tests and no external suite | same | Pass |
| AC4: `check` < 60 s warm | n/a | `cd server && time ./gradlew --quiet check test --rerun` | Pass: 11.6 s real (tests forced to rerun) |
| AC5: ARCHITECTURE.md updated | Documentation, not testable by unit tests | n/a | Not testable |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests '*JpaCompilerPluginTests'`

Result:

Pass (BUILD SUCCESSFUL; `JpaCompilerPluginTests`: 4 tests, 0 failures, 0 errors, 0 skipped; ktlint clean on main, test and script sources)

## Full test execution

Command:

`cd server && ./gradlew ktlintCheck test jacocoTestReport integrationTest --rerun-tasks`

Result:

Pass (BUILD SUCCESSFUL in 13 s, all 15 tasks executed). `test`: 9 suites, 21 tests, 0 failures/errors/skipped
(`CockpitServerApplicationTests` 1, `HealthEndpointTests` 1, `DatasourcePropertiesTests` 4, `FlywayConfigurationTests` 2,
`JpaCompilerPluginTests` 4, `PostgresTestBaseTests` 4, `ProfileTests$Default` 1, `$Dev` 2, `$E2e` 2).
`integrationTest`: `ExternalToolingSmokeTests` 2 tests, 0 failures/errors.

Also: `cd server && time ./gradlew --quiet check test --rerun` -> success, 11.6 s real.

## .taskcheck

Result:

Not run: the helper's sandbox requires approval for `sh scripts/check.sh` and none was available. Its server part
(`./gradlew check`) passed above. The parent must run `.taskcheck`.

## Bugs found

None.

## Coverage

Status: not applicable for production code (the task changed no instrumentable production file); the JaCoCo run
itself is verified (fresh, exit 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no executable production lines changed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p0-server-deps`, base
  `7b924a409a95caed4378653a810545cb123782c7`, HEAD `f01b7c1fcd074f06d5342f1d94fe50ee9f0a532d`; code commits `2d3e527`,
  `4364631`, `45fdd8e` and review-only commits `e0b6dda`, `f01b7c1`, as supplied by the parent and checked with
  `git show --stat`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `f01b7c1` (no tracked modifications) plus
  untracked `JpaCompilerPluginTests.kt` sha256 `be7398da7c6b1ce0312349910171cf45fa44cc271db836f109aa9a709e6ca529` and
  `JpaPluginFixtures.kt` sha256 `3690f8b628ae35b4f3baff8875c9a961128abe1efb5663fc4f887eaf67027306`. Hashes and
  `git status` identical before (10:32:54) and after (10:33:20) the run.
- Exact included files; renamed/deleted/excluded files and reasons: `git diff --name-only 7b924a4..HEAD -- server/src/main`
  lists only `application.properties`, `application-dev.properties`, `application-e2e.properties`: configuration
  with no JaCoCo-instrumentable code, covered behaviourally (table above). Excluded: `server/build.gradle.kts`
  (build config; its CR-1 effect is covered by `JpaCompilerPluginTests`), test sources/resources, `docs/`,
  `reviews/`, `tasks/`, `dashboard/`, `TEAM.md`. No production Kotlin file added or changed; no renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via Gradle `jacoco` plugin (session
  `dell26-60459680`); `./gradlew ktlintCheck test jacocoTestReport integrationTest --rerun-tasks` in `server/`;
  `test` task = all tests except tag `external`.
- Run start/completion time and exit status: 2026-10-20T10:32:59+02:00 (approx., JaCoCo session start
  1792485180937 ms) to 10:33:11+02:00, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (written 10:33:10, sha256 `5ffa716979807568c4973bd58bce28b05091ac1ff76259ccf94fdf8dc76145b5`, session
  `dell26-60459680`), HTML in `server/build/reports/jacoco/test/html/`.
- Evidence outputs are fresh and inputs stayed unchanged: yes (`--rerun-tasks`, report time matches the run, snapshot
  unchanged).
- Final run after latest test additions and production fixes: yes (after fix commit `45fdd8e` and after this run's
  test edits).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/resources/application.properties` | N/A (not instrumentable) | N/A | N/A | N/A |
| `server/src/main/resources/application-dev.properties` | N/A (not instrumentable) | N/A | N/A | N/A |
| `server/src/main/resources/application-e2e.properties` | N/A (not instrumentable) | N/A | N/A | N/A |

No aggregate: the task coverage scope contains no instrumentable production code. For reference only (outside the
task scope, unchanged by the task): the repository's only production class `CockpitServerApplication.kt` has
3/12 instructions, 1/3 lines, 1/2 methods covered; the missed `main` is bootstrap code.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| none in task scope | Config behaviour (datasource variables, Flyway out-of-order/subfolders, JSON logs, ISO dates, profiles) and the JPA compiler plugin setup | Covered behaviourally by `DatasourcePropertiesTests`, `FlywayConfigurationTests`, `ProfileTests`, `PostgresTestBaseTests`, `JpaCompilerPluginTests` |

## Unable to test

- `.taskcheck` (`sh scripts/check.sh`): not executed, sandbox approval required. Follow-up: parent runs it.
- `./gradlew dependencies` (AC1): left to the parent; compiling and running both test tasks is indirect evidence only.
- AC5 (ARCHITECTURE.md content): documentation, reviewed by the code-reviewer, not testable.
- CR-4 (generated Spring Security password logged at WARN): accepted interim state per confirmed assumption #3,
  deferred to the auth task; no test added in this task.
- Hibernate proxying of a real entity at runtime: no real entities exist yet; the plugin effect is verified on
  compiled bytecode instead. Later entity tasks exercise it through repositories.

## Summary

Tests added and all validation passed (server `ktlintCheck`, `test` 21/21, `integrationTest` 2/2, warm `check`
11.6 s; `.taskcheck` left to the parent). The CR-1, CR-2 and CR-3 fixes are covered. No production defects found.
Task-scoped coverage is not applicable because the task changed no instrumentable production code.
