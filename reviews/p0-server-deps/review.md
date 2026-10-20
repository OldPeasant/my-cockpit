# Code Review

Date: 2026-10-20

Task: p0-server-deps - Server dependencies, profiles and test infrastructure

Scope: re-review of the final revision. `git diff 7b924a4..45fdd8e`, excluding `tasks/` (committed changes only; working tree clean). Also excluded: commit 6734e50 ("Add the TFP dashboard to the repo": `dashboard/`, `TEAM.md`). A human authored it and it is not part of this task. Review reports under `reviews/` are not code.

Requirements:
- tasks/p0-server-deps.md acceptance criteria, goal and recorded (confirmed) assumptions #1-#4
- SPEC.md §2.1 (tech stack), §11 (`SPRING_DATASOURCE_*`)
- docs/ARCHITECTURE.md (stack table, Install, Testing), docs/QUALITY.md
- AGENTS.md project notes (lint, migrations layout, logging rules, stack table updates)
- Previous review of revision f2adb39 (CR-1..CR-4) and reviews/p0-server-deps/fixes.md

Git range:
- Branch: main
- HEAD: 45fdd8e006be44bc0f5f296e1e35ebad4905fcfb
- Base/start: 7b924a409a95caed4378653a810545cb123782c7
- Commits reviewed: 4 task commits (2d3e527, 4364631, e0b6dda, 45fdd8e). The `assume:`, `confirm:`, `heartbeat:` and `claim:` commits touch only `tasks/`. The dashboard commit 6734e50 is out of scope (see Scope).

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## What was checked

- Code before the fixes: `git diff f2adb39 e0b6dda -- server docs` is empty. The code under the earlier
  review and the pre-fix code on main are identical, so the earlier review of AC1-AC5 still holds. Only
  the fix commit 45fdd8e needed a fresh review.
- CR-1 (fixed): `server/build.gradle.kts:4` adds `kotlin("plugin.jpa") version "2.3.21"`, which matches
  the Kotlin version. Lines 81-86 add `allOpen` for `jakarta.persistence.Entity`, `MappedSuperclass` and
  `Embeddable`. The ARCHITECTURE.md stack table now lists both compiler plugins.
- CR-2 (fixed): `ExternalToolingSmokeTests.kt:56` now uses `ServerSetupTest.SMTP.dynamicPort()`. The send
  at lines 23-29 goes through `greenMail.smtp.serverSetup`, which carries the port the server actually
  bound. No fixed ports are left in the external tests.
- CR-3 (fixed): `PostgresTestBaseTests.kt:39-40` filters by `table_schema = current_schema()`. The
  `flyway_fixture` schema from `FlywayConfigurationTests` can no longer affect the count.
- CR-4 (accepted): the parent worker accepted the generated Boot default user and its password logged
  at WARN as the documented interim state (confirmed assumption #3), to be removed by the auth task.
  It is carried below as P3 so the auth task does not lose track of it.
- New issues: the fix commit introduces none. It adds no dependencies beyond the Kotlin compiler plugin,
  changes no production code, and the test changes only narrow the existing assertions.
- Test results: `server/build/test-results/` is from 10:29 local time, just before the fix commit at
  10:30. That matches the fixer's `ktlintCheck test integrationTest --rerun-tasks` run. `test` has
  9 suites, 19 tests (including the `ProfileTests` nested classes), 0 failures/errors.
  `integrationTest` has only `ExternalToolingSmokeTests` (2 tests, 0 failures/errors), so the `external`
  tag split works. I could not run Gradle or `tasks.py verify` in this session (permission denied). The
  parent worker must record the AC3 evidence (the Podman `./gradlew test` run) and the AC4 evidence
  (warm `./gradlew check` time under 60 s) with `tasks.py verify` and run `.taskcheck`.

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
      `UserDetailsService`, so Boot creates a default user and logs "Using generated security password"
      at WARN. This is the documented interim state (confirmed assumption #3), and the parent worker
      accepted it for this task.
    - Why it matters: A real, short-lived credential is written to logs at WARN, which goes against the
      AGENTS.md logging rule. If the auth task does not replace the default user, it ships.
    - Suggested fix: No change in this task. Make sure the auth task (p0-auth-setup / p0-auth-login)
      replaces the default user, for example with a note in that task.

## Review conclusion

No material issues found. CR-1, CR-2 and CR-3 are fixed correctly in 45fdd8e, and the fix commit
introduces no new issues. CR-4 stays open as an accepted P3, deferred to the auth task. The parent
worker still has to record the AC3 and AC4 evidence with `tasks.py verify` and run `.taskcheck`.
