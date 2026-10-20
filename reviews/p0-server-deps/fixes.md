# Fix Report

Date: 2026-10-20

Task: p0-server-deps - Server dependencies, profiles and test infrastructure

Scope: current task. Findings CR-1, CR-2 and CR-3 from `reviews/p0-server-deps/review.md`
(base 7b924a4, reviewed HEAD f2adb39). CR-4 was accepted by the parent worker and not fixed.

## Fixed findings

### CR-1 - Kotlin JPA compiler plugin (no-arg / all-open for `@Entity`) not configured

Files changed:
- `server/build.gradle.kts`
- `docs/ARCHITECTURE.md`

Problem:
The build declared Spring Data JPA but had no `kotlin("plugin.jpa")` and no all-open config for JPA
annotations. The first Kotlin entity would then fail without a no-arg constructor or would be final,
so its author would have had to edit the shared build file.

Fix:
Added `kotlin("plugin.jpa") version "2.3.21"` to `plugins`. Added an `allOpen { ... }` block for
`jakarta.persistence.Entity`, `MappedSuperclass` and `Embeddable`. Added the Kotlin compiler plugins
to the server row of the ARCHITECTURE.md stack table.

Validation:
- `cd server && ./gradlew ktlintCheck test integrationTest --rerun-tasks` - Pass (build configures and compiles with the plugin)

### CR-2 - GreenMail smoke test binds the fixed port 3025

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/support/ExternalToolingSmokeTests.kt`

Problem:
`GreenMailExtension(ServerSetupTest.SMTP)` listened on the fixed port 3025. Concurrent runs on the
same host could collide.

Fix:
The extension now uses `ServerSetupTest.SMTP.dynamicPort()`. The test sends through
`GreenMailUtil.sendTextEmail(..., greenMail.smtp.serverSetup)`, which carries the port the server
actually bound.

Validation:
- `cd server && ./gradlew integrationTest` (part of the run above) - Pass. `ExternalToolingSmokeTests`: 2 tests, 0 failures. The SMTP send can only succeed against the actual bound port.

### CR-3 - "Flyway ran against the container" check is not schema-scoped

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/support/PostgresTestBaseTests.kt`

Problem:
The query counted `flyway_schema_history` tables in any schema. The result therefore depended on
whether the `flyway_fixture` schema from `FlywayConfigurationTests` was present in the shared container.

Fix:
Added `and table_schema = current_schema()` to the query.

Validation:
- `cd server && ./gradlew test` (part of the run above) - Pass. `PostgresTestBaseTests`: 4 tests, 0 failures.

## Not fixed

### CR-4 - The generated Spring Security password is logged at WARN until auth lands

Status:
Out of scope (accepted by the parent worker)

Reason:
This is the documented interim state (assumption #3). The parent worker accepted it as no change for
this task. It is to be removed by the auth task.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintCheck test integrationTest --rerun-tasks` - BUILD SUCCESSFUL.
  `ktlintCheck` clean. `test`: 8 suites, 17 tests, 0 failures/errors. `integrationTest`:
  `ExternalToolingSmokeTests` 2 tests, 0 failures/errors.

Full test suite:
- Server: the run above covers the full server `test` and `integrationTest` suites. Web/android are unchanged and not run.

Lint/type-check/build:
- `./gradlew ktlintCheck` - Pass. `ktlintFormat` was not needed.

`.taskcheck`:
- Not run. Running `sh scripts/check.sh` needed approval in the fixer session. The parent worker runs it.

Remaining unresolved findings:
0 (CR-4 accepted, not a defect to fix in this task)

## Final status

All validated findings fixed and validation passed.
