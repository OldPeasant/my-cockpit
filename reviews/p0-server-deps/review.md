# Code Review

Date: 2026-10-20

Task: p0-server-deps - Server dependencies, profiles and test infrastructure

Scope: `git diff 7b924a4..f2adb39`, excluding `tasks/` (committed changes only; working tree clean)

Requirements:
- tasks/p0-server-deps.md acceptance criteria, goal and recorded assumptions (`python tasks.py assumptions`, #1-#4)
- SPEC.md §2.1 (tech stack), §11 (`SPRING_DATASOURCE_*`)
- docs/ARCHITECTURE.md (stack table, Install, Testing), docs/QUALITY.md
- AGENTS.md project notes (lint, migrations layout, logging rules, stack table updates)

Git range:
- Branch: main
- HEAD: f2adb39c47c8535b9dc2e3b2f775f1ac63b47f60
- Base/start: 7b924a409a95caed4378653a810545cb123782c7
- Commits reviewed: 7 (dd660ef and f2adb39 carry the code; dc9a7e0, 27def89, 9c05056, 536971d are `assume:` commits touching only `tasks/`)

Summary: 0 P0, 0 P1, 1 P2, 3 P3

## What was checked

- AC1: every listed library is declared in `server/build.gradle.kts` (Security, Data JPA, Flyway +
  `flyway-database-postgresql`, PostgreSQL driver, springdoc 3.0.0, Testcontainers postgresql +
  junit-jupiter, Kotest, MockK, springmockk, WireMock, GreenMail, Angus Mail, ical4j, ez-vcard,
  flexmark, jsoup, Thumbnailator, spring-security-crypto + BouncyCastle, java-otp, webauthn4j,
  java-uuid-generator, logstash-logback-encoder). Test runs compiled and passed against them, so they resolve.
- AC2: `application.properties` takes the datasource from `SPRING_DATASOURCE_*` with no default password,
  sets Flyway `locations=classpath:db/migration` and `out-of-order=true`, Jackson ISO dates/durations, and
  Logstash structured console logs. `dev` blanks the format (plain logs), `e2e` keeps JSON. The test output
  confirms this: `ProfileTests$Dev` logs plain text, `ProfileTests$E2e` logs JSON.
- AC3: `PostgresTestBase` starts one `postgres:16-alpine` container in a companion-object initializer
  with `@ServiceConnection`. `PostgresTestBaseTests` checks for server version 16.x and the container JDBC URL.
- AC4: `test` excludes the `external` tag. `integrationTest` includes only that tag, is a separate `Test`
  task and is not wired into `check`.
- AC5: the ARCHITECTURE.md stack table, Install, environment variables and Testing sections are updated.
- Security: there are no committed secrets. The dev/e2e defaults (`cockpit`) are local-only placeholders,
  and the base profile has an empty default password.
- Running the tests: I was not allowed to run Gradle in this review session (the command needed approval).
  I read the existing results in `server/build/test-results/` from 10:25 local time, which matches the
  final test code committed at 10:26. `test` has 8 suites, 17 tests, 0 failures/errors. `integrationTest`
  has only `ExternalToolingSmokeTests` (2 tests). Neither `tasks.py verify` evidence nor the check timing
  was recorded yet. The parent worker must record both (AC3 run on Podman, AC4 warm `check` time < 60 s).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Kotlin JPA compiler plugin (no-arg / all-open for `@Entity`) not configured**
    - File: `server/build.gradle.kts:1`
    - Category: Architecture
    - Problem: The task adds Spring Data JPA/Hibernate, but the `plugins` block has only `kotlin("plugin.spring")`.
      `kotlin("plugin.jpa")` (no-arg constructors for `@Entity`/`@Embeddable`/`@MappedSuperclass`) is missing,
      and so is the usual `allOpen { annotation("jakarta.persistence.Entity") ... }` block. No other task
      on the board plans to add them.
    - Why it matters: Hibernate needs a no-arg constructor. Without the plugin, the first Kotlin entity
      in any module (gtd, items-links, auth, settings, ...) fails at runtime, or its author adds the plugin
      themselves. Several parallel module tasks would then edit the plugins/config section of the shared
      build file. That undermines this task's goal ("so later tasks rarely touch the build file") and the
      board's one-line-per-dependency conflict rule. Final entity classes also block Hibernate lazy proxies.
    - Suggested fix: Add `kotlin("plugin.jpa") version "2.3.21"` and an `allOpen` block for
      `jakarta.persistence.Entity`, `MappedSuperclass` and `Embeddable`. List it in the ARCHITECTURE.md stack table.

## P3 Low

- [ ] **CR-2 | GreenMail smoke test binds the fixed port 3025**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/support/ExternalToolingSmokeTests.kt:50`
    - Category: Tests
    - Problem: `GreenMailExtension(ServerSetupTest.SMTP)` listens on the fixed port 3025. Several agent
      clones run on the same host, so two concurrent `./gradlew integrationTest` runs collide with
      "address already in use". The WireMock test next to it already uses `dynamicPort()`.
    - Why it matters: Integration runs fail intermittently and for reasons unrelated to the code. Later
      tasks will also copy this template for their GreenMail tests.
    - Suggested fix: Use `ServerSetupTest.SMTP.dynamicPort()` (GreenMail 2.x), or `ServerSetup(0, ...)`,
      and send through the port the server actually bound.

- [ ] **CR-3 | "Flyway ran against the container" check is not schema-scoped**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/support/PostgresTestBaseTests.kt:38`
    - Category: Tests
    - Problem: The query counts `flyway_schema_history` tables in any schema. `FlywayConfigurationTests`
      creates `flyway_fixture.flyway_schema_history` in the same shared container, so the assertion depends
      on test order and cleanup. A leftover fixture schema would give count 2 (false failure). Without
      application migrations it would only prove that some Flyway ran.
    - Why it matters: The test is order-dependent in the shared singleton container that all later database
      tests reuse.
    - Suggested fix: Add `and table_schema = current_schema()` (or `'public'`) to the query.

- [ ] **CR-4 | The generated Spring Security password is logged at WARN in every profile until auth lands**
    - File: `server/build.gradle.kts:30`
    - Category: Security
    - Problem: Adding `spring-boot-starter-security` without a `SecurityFilterChain`/`UserDetailsService`
      turns on Boot's default user. The test logs show "Using generated security password" (4 occurrences).
      That credential grants access to every endpoint, and it is logged at WARN in the production-like
      default profile. The AGENTS.md logging rule says no secrets at INFO or above. This is the documented
      interim state (assumption #3), not a regression. Nothing is deployed yet.
    - Why it matters: The credential is short-lived, but it is a real credential written to logs. If the
      auth task forgets to replace the default user, it ships.
    - Suggested fix: No change needed in this task if the parent accepts it. Make sure the auth task
      (p0-auth-login) removes the default user, or note it there. Alternatively, define an empty
      `UserDetailsService`/exclude `UserDetailsServiceAutoConfiguration` once main code is in scope.

## Review conclusion

No material issues found. All five acceptance criteria are implemented. The open items are one P2
maintainability gap (CR-1) and three P3 cleanups. The parent worker still has to record evidence with
`tasks.py verify`: the Podman `./gradlew test` run and the warm `./gradlew check` time under 60 s.
