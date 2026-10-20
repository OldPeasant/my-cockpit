# Fix Report

Date: 2026-10-20

Task: p0-auth-setup - First-run setup: owner account, password hashing, TOTP enrollment

Scope: findings CR-1 (P2) and CR-2 (P3) from `reviews/p0-auth-setup/review.md`, with the required behavior for CR-1 as specified by the parent worker. `reviews/p0-auth-setup/tests.md` reported no production bugs.

Git scope:
- Branch: main
- Base: bd8a0ab04a406983ebb528963d0eb234651a09a8
- HEAD when fixing: bfa3e50fba611dec972f554f503773557edce0ca (reviewed code commit 2a2664d, test commit bfa3e50)
- Fixes are uncommitted working-tree changes in: `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/SetupService.kt`, `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/api/SetupController.kt`, `api/openapi.yaml` (regenerated), `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/SetupWebTests.kt`, `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/SetupEdgeCaseTests.kt`. The generated web client was regenerated and did not change.

## Fixed findings

### CR-1 — Any unauthenticated client can silently replace a pending owner account until its TOTP is confirmed

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/SetupService.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/api/SetupController.kt`
- `api/openapi.yaml` (regenerated)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/SetupWebTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/SetupEdgeCaseTests.kt`

Problem:
`start()` deleted any pending (unconfirmed) account and created a new one without checking who sent the request. Anyone who could reach the endpoint could take over a setup the owner had already started.

Fix:
`start()` replaces a pending account only if `mayReplace` allows it. That is the case when (a) the request's username equals the pending username and the password matches the stored Argon2id hash, or (b) the pending account's `createdAt` is more than `ABANDONED_AFTER` (1 hour) before the injected `Clock`'s current time. Otherwise it throws `ConflictException("Setup is already in progress")`, the same message the concurrent-insert path uses, so the response does not say which check failed. When the account is not abandoned, the password hash is always checked, even if the username differs, so response timing does not reveal whether the username matched. `createdAt` comes from JPA auditing, which reads the same Clock bean. The KDoc of `SetupService`, the `@Operation` description and the 409 response description of `SetupController` are updated. `api/openapi.yaml` was regenerated, and `npm run api:gen` produced no client change.
Tests:
- SetupWebTests: `starting again before confirmation replaces the pending account` relied on replacement with a different username. It became `starting again with the same username and password replaces the pending account` (new row id, 10 recovery codes, old secret rejected, new secret confirms).
- SetupWebTests (new): `starting again with another username or a wrong password is a conflict and keeps the pending account`. It covers a different username, a wrong password, and both together: each returns 409 with the same detail, and the row (id, username, password hash, encrypted secret) and the recovery code hashes stay unchanged. The original secret still confirms.
- SetupEdgeCaseTests (new): `a pending account older than one hour can be replaced by anyone, a younger one cannot`. With `MutableClock`, exactly 1 h later gives 409; 1 h + 1 s later, another user replaces the account, and the new secret confirms.
- SetupEdgeCaseTests: two existing boundary tests (`password of exactly 12 and 256 characters...`, `username of 64 characters...`) posted several setups in a row with different credentials, so they relied on replacement. They now delete the pending row between the posts. What they assert is unchanged.

Validation:
- `cd server && ./gradlew ktlintFormat` then `./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` — Pass (48 tests: SetupWebTests 11, SetupEdgeCaseTests 15, TotpTests 6, RecoveryCodesTests 5, Base32Tests 4, SetupSecretHidingTests 4, SecurityConfigurationTests 3; 0 failures)
- `cd server && UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'` then `./gradlew test --tests '*OpenApiContractTest'` (without the flag) — Pass
- `cd web && npm run api:gen` then `npm run api:check` — Pass ("src/app/api/generated is up to date", no file changed)

### CR-2 — The single-user race protection is not tested

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/SetupEdgeCaseTests.kt`

Problem:
No test showed that the unique index `app_user_single_row` rejects a second account row.

Fix:
New Postgres test `the database rejects a second app_user row`. After `setup.start(...)`, it inserts a second `app_user` row through `JdbcTemplate` and expects a `DataIntegrityViolationException` whose message names `app_user_single_row`. One row remains, with the original username. The existing concurrency test (`concurrent setup requests leave exactly one account and fail only with a conflict`, 4 threads with different usernames) still passes with the new logic. A request that loses the race now gets a 409 either from the new replacement check or from the unique index, and the test accepts both.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` — Pass
- `cd server && ./gradlew check --rerun-tasks` — Pass (concurrency tests included)

## Not fixed

None. Every supplied finding was fixed.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` — Pass, 48 tests, 0 failures

Full test suite:
- `cd server && ./gradlew ktlintFormat check` — first run: 594 tests, 1 failure in `ch.sonensei.cockpit.core.persistence.ClockConfigurationTests` "the application clock is the system clock in UTC". The test compares the microsecond-truncated application clock with a nanosecond `Instant.now()` taken just before it (`2026-10-20T09:56:46.051140Z should be >= 2026-10-20T09:56:46.051140235Z`). This flaky test is older than this task (last changed in a2de298, p0-db-conventions), and it is not related to these changes, so it was not touched here.
- `cd server && ./gradlew check --rerun-tasks` — Pass, BUILD SUCCESSFUL, 594 tests

Lint/type-check/build:
- `cd server && ./gradlew ktlintFormat check` — ktlint clean (formatting applied to the changed tests)
- `cd web && npm run api:check` — Pass

`.taskcheck`:
- `sh scripts/check.sh` — Pass ("== all checks passed": tfp gate tests, server ktlint + tests, web eslint + tests + build, android ktlint + unit tests + lint)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
