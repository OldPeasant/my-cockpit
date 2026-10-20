# Code Review

Date: 2026-10-20

Task: p0-server-deps - Server dependencies, profiles and test infrastructure

Scope: re-review of the full task diff at the final revision, `git diff 18122fd..50e57c0 -- . ':!tasks' ':!reviews'`. Committed changes only; the working tree was clean. The previous review covered a pre-rebase revision (8df6951 on base 151aebf). Since then the task commits were rebased onto 18122fd, and commit 50e57c0 changed `tests/test_quality_gate.py`.

Requirements:
- tasks/p0-server-deps.md: acceptance criteria #1-#5, goal, confirmed assumptions #1-#4, and the unconfirmed assumption about the `tests/test_quality_gate.py` fix
- SPEC.md §2.1 (tech stack), §11 (`SPRING_DATASOURCE_*`)
- docs/ARCHITECTURE.md (stack table, Install, Run, Environment variables, Testing)
- AGENTS.md project notes (lint, migration layout, logging rules, stack table updates, touches)
- Previous review (CR-1..CR-5), reviews/p0-server-deps/fixes.md, reviews/p0-server-deps/quality.json

Git range:
- Branch: main
- HEAD: 50e57c0e19cb90fd43b7d8be4eb9d9c5208b2ab7
- Base/start: 18122fd02d23a9a1dddcf2568d9c4057f35fee7f
- Commits reviewed: 8 (2411068, e1ee084, 2d79e4f, ae008e6, 5506575, fb49b54, 8f7823f, 50e57c0). 2d79e4f, 5506575 and 8f7823f touch only `reviews/`.

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## What was checked

- Rebase equivalence: `git range-diff 151aebf..8df6951 18122fd..fb49b54` shows all six earlier commits as identical (`=`). The code reviewed before is unchanged, and so are the fixes for CR-1..CR-3: `kotlin("plugin.jpa")` plus `allOpen` in `server/build.gradle.kts:4,82-86`, the GreenMail dynamic port in `ExternalToolingSmokeTests.kt:56`, and the `current_schema()`-scoped query in `PostgresTestBaseTests.kt:40-41`.
- I re-read the current code in full against the criteria:
  - AC1: `server/build.gradle.kts:25-72` declares every listed library. Argon2 comes from spring-security-crypto (via the security starter) plus `bcprov-jdk18on`. TOTP is `java-otp`, UUIDv7 is `java-uuid-generator`, and webauthn4j and logstash-logback-encoder are declared. These match SPEC.md §2.1 and confirmed assumption #2. Versions that the Boot BOM does not manage are pinned. The other dependencies come from the BOM.
  - AC2: `application.properties` reads the datasource from `${SPRING_DATASOURCE_*}`, with no default password. It sets `flyway.locations=classpath:db/migration` and `out-of-order=true`, Jackson ISO dates and durations, and `logging.structured.format.console=logstash`. `dev` clears the logging format back to plain text, and `e2e` keeps JSON. `DatasourcePropertiesTests`, `FlywayConfigurationTests`, `ProfileTests` and `PostgresTestBaseTests` cover these settings. The Flyway fixture test proves that subfolders are scanned and that out-of-order works, in a throwaway schema it drops afterwards.
  - AC3: `PostgresTestBase` starts one `postgres:16-alpine` container in a companion object, which runs once per JVM, and wires it in with `@ServiceConnection`. The tests assert the server version is `16.*` and that the JDBC URL equals the container URL, including under the `dev` and `e2e` profiles.
  - AC4: `tasks.test` excludes the `external` tag. `integrationTest` is registered separately, includes only `external`, and `check` does not depend on it.
  - AC5: the ARCHITECTURE.md stack table, Install, Run, Environment variables and Testing sections were updated. They describe `test` vs `integrationTest`, the Podman variables, and the new requirement that `./gradlew test` (and therefore `.taskcheck`) needs Docker or Podman.
- New commit 50e57c0 (`tests/test_quality_gate.py:47`): `tasks.py:23` resolves `ROOT` from `TASKS_ROOT`, and `agents.py:413` exports `TASKS_ROOT` for agents. The old filter removed only `TASK_*`, so the gate test's `tasks.py` calls worked on the runner's real clone. The one-line change also removes `TASKS_*`, which fixes this. No other `TASKS_*` variable is read by `tasks.py`, so the wider prefix removes nothing the test needs. The file is outside the task's `touches`, which the assumption in the task file records (not yet confirmed by a human). Note, not a finding for this task: `tests/test_done_gate.py:56` uses the same `TASK_`-only filter. It is not part of `.taskcheck`, but run under the agent runner it would have the same leak.
- Security: no secrets are committed. The only literal passwords are the local-dev/e2e default `cockpit` and fictitious test fixture values. The base profile has no default password. No logging of sensitive values was added.
- Evidence: `python tasks.py verify p0-server-deps` lists all five criteria as verified. The recorded commands and results match the repository: Podman `./gradlew test` with 9 suites and 21 tests, `integrationTest` running only `ExternalToolingSmokeTests`, warm `check` at 11.957 s, and `dependencies` resolving. I could not repeat the Testcontainers runs because Docker is not running on this machine. I did not re-execute anything, so I take the recorded runs as the evidence.
- `reviews/p0-server-deps/quality.json` still names `reviewed_revision` 930738a. It has to be updated to the final revision 50e57c0 together with this report. That is the parent worker's job, so it is not a code finding.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-4 | Until the auth task lands, every profile logs the generated Spring Security password at WARN (carried over, deferred)**
    - File: `server/build.gradle.kts:31`
    - Category: Security
    - Problem: `spring-boot-starter-security` is on the classpath, but there is no `SecurityFilterChain` or `UserDetailsService`. Boot therefore creates a default user and logs "Using generated security password" at WARN. Confirmed assumption #3 documents this interim state. `quality.json` marks it deferred to p0-auth-setup.
    - Why it matters: a real, short-lived credential is written to the logs at WARN, which goes against the AGENTS.md logging rule. It would ship if the auth task does not replace the default user.
    - Suggested fix: no change in this task. Make sure p0-auth-setup replaces the default user store, for example with a note in that task.

- [ ] **CR-5 | The "open for Hibernate proxies" check cannot fail for the `@MappedSuperclass` fixture (carried over, accepted)**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/support/JpaPluginFixtures.kt:11` (used at `JpaCompilerPluginTests.kt:28-31`)
    - Category: Tests
    - Problem: `FixtureBase` is `abstract`, and an abstract class is never final. The non-final assertion therefore passes for it even without the `allOpen` entry for `MappedSuperclass`. The test also checks only class modifiers, not that members are open.
    - Why it matters: coverage of the `MappedSuperclass` entry and of open members is weaker than the test name claims. The risk is low, because the build config is correct.
    - Suggested fix: make `FixtureBase` non-abstract, or also assert that a member getter (for example `getId`) is not final.

## Review conclusion

No material issues found. The rebased task code is identical to the code reviewed before, and CR-1..CR-3 stay fixed. The new `tests/test_quality_gate.py` change is a correct, minimal fix for the `TASKS_ROOT` leak. CR-4 (deferred to the auth task) and CR-5 (accepted test weakness) remain as P3s. The parent worker still has to update `quality.json` to the final reviewed revision and run `.taskcheck` before `done`.
