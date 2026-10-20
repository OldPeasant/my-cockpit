# Code Review

Date: 2026-10-20

Task: p0-server-deps - Server dependencies, profiles and test infrastructure

Scope: final re-review of the full task diff `git diff 151aebf..8df6951`, excluding `tasks/`. Committed changes only; the working tree is clean. Review reports under `reviews/` are not code. The new code since the previous review is in commit 8df6951, which adds `JpaCompilerPluginTests.kt` and `JpaPluginFixtures.kt`. The focus is on that commit.

Requirements:
- tasks/p0-server-deps.md acceptance criteria, goal and confirmed assumptions #1-#4
- SPEC.md §2.1 (tech stack), §11 (`SPRING_DATASOURCE_*`)
- docs/ARCHITECTURE.md (stack table, Install, Testing), docs/QUALITY.md (artifact contract, freshness)
- AGENTS.md project notes (lint, migration layout, logging rules, stack table updates)
- Previous review of revision 45fdd8e (CR-1..CR-4) and reviews/p0-server-deps/fixes.md

Git range:
- Branch: main
- HEAD: 8df695197ac4c20592b71d74e4fa6df4a7ec2ac5
- Base/start: 151aebf9f608fb2222709fd03bf5a138fd26289a (`git merge-base HEAD origin/main`)
- Commits reviewed: 6 (01bb4d1, f8171c6, 52a91b0, ff131c5, b4b3a1b, 8df6951). 52a91b0 and b4b3a1b touch only `reviews/`.

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## What was checked

- Rebase equivalence: `git range-diff 7b924a4..45fdd8e 151aebf..ff131c5` shows all four task commits as
  identical (`=`) to the commits reviewed before (2d3e527->01bb4d1, 4364631->f8171c6, e0b6dda->52a91b0,
  45fdd8e->ff131c5). The earlier review of AC1-AC5 and of the CR-1..CR-3 fixes therefore still holds
  for this code.
- New commit 8df6951: `git diff ff131c5 8df6951 -- server docs` adds only the two test files. It makes
  no change to production code, build files or docs.
  - `JpaPluginFixtures.kt` puts its fixtures in package `ch.sonensei.jpafixture`, outside the
    `@SpringBootApplication` package `ch.sonensei.cockpit`. Boot's entity scan does not pick them up,
    so `ddl-auto=validate` does not look for their tables. The Spring context tests in the same run
    pass, which confirms this. The file sits in the `support/` folder, inside the task's `touches`.
  - `JpaCompilerPluginTests.kt` checks the CR-1 fix (`build.gradle.kts:4` `kotlin("plugin.jpa")`,
    lines 81-86 `allOpen`). It checks that `@Entity`, `@MappedSuperclass` and `@Embeddable` classes get
    a no-arg constructor and that the entity can be instantiated through it. It also checks that
    `@Entity` and `@Embeddable` classes are not final. A control class (`PlainFixture`) stays final and
    has no no-arg constructor, so the tests would catch a removed plugin or a removed `allOpen` block
    for those annotations. The tests are plain JUnit, need no container, and use Kotest assertions
    in the style of the surrounding tests.
- Test results: `server/build/test-results/` is from 10:33, just before commit 8df6951 at 10:34.
  `test` has 9 suite files with 21 tests: JpaCompilerPluginTests 4, DatasourcePropertiesTests 4,
  PostgresTestBaseTests 4, FlywayConfigurationTests 2, ProfileTests Default/Dev/E2e 1/2/2,
  CockpitServerApplicationTests 1, HealthEndpointTests 1. All have 0 failures, errors and skips, and
  no external suite is among them. `integrationTest` holds only ExternalToolingSmokeTests (2 tests,
  0 failures/errors), so the `external` tag split works. These numbers match the test-writer's report.
- Evidence: `python tasks.py verify p0-server-deps` lists all five criteria as unverified. The parent
  worker must record each one before `done`: AC1 needs a `./gradlew dependencies` run (tests.md says
  the test-writer did not run it). AC3 needs the Podman `./gradlew test` run. AC4 needs the warm
  `check` time under 60 s (tests.md reports 11.6 s). AC5 is a documentation check.
- Note on tests.md (no finding): its Scope line names pre-rebase hashes (`f01b7c1`, `45fdd8e`). The
  code content is identical (see rebase equivalence above), and the freshness rule in
  `docs/QUALITY.md` compares the task's code change, not commit hashes. `quality.json` must use the
  current full hash for `reviewed_revision`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-4 | The generated Spring Security password is logged at WARN in every profile until auth lands (accepted)**
    - File: `server/build.gradle.kts:31`
    - Category: Security
    - Problem: `spring-boot-starter-security` is on the classpath without a `SecurityFilterChain` or
      `UserDetailsService`. Boot therefore creates a default user and logs "Using generated security
      password" at WARN. This is the documented interim state (confirmed assumption #3), and the parent
      worker accepted it for this task.
    - Why it matters: A real, short-lived credential is written to logs at WARN, which goes against the
      AGENTS.md logging rule. If the auth task does not replace the default user, this ships.
    - Suggested fix: No change in this task. Make sure the auth task (p0-auth-setup / p0-auth-login)
      replaces the default user, for example with a note in that task.

- [ ] **CR-5 | The "open for Hibernate proxies" check cannot fail for the `@MappedSuperclass` fixture**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/support/JpaPluginFixtures.kt:11` (used at `JpaCompilerPluginTests.kt:28-31`)
    - Category: Tests
    - Problem: `FixtureBase` is declared `abstract`, and abstract classes are never final. The assertion
      `Modifier.isFinal(type.modifiers) shouldBe false` therefore passes for `FixtureBase` even without
      the `allOpen` annotation for `jakarta.persistence.MappedSuperclass`. The test also checks only
      class modifiers, not that members (for example the `id` getter) are open. Hibernate proxies need
      open members as well.
    - Why it matters: The test claims to cover all three `allOpen` annotations, but it does not cover
      `MappedSuperclass`, and it does not cover open members. The real risk is small, because the
      build config is correct and the plugin opens classes and members together.
    - Suggested fix: Make `FixtureBase` a non-abstract class so the class check means something. Or
      add an assertion that a member (for example `getId`/`getTitle`) is not final, which is
      meaningful for all three fixtures.

## Review conclusion

No material issues found. The rebased task commits are identical to the code reviewed before, and
CR-1..CR-3 stay fixed. The new commit 8df6951 adds test-only coverage for the JPA compiler plugin
fix and does not break anything. CR-4 stays open as an accepted P3, deferred to the auth task. CR-5
is an optional test improvement. The parent worker still has to record evidence for all five
criteria with `tasks.py verify` and run `.taskcheck` before `done`.
