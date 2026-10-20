# Code Review

Date: 2026-10-20

Task: p0-auth-setup - First-run setup: owner account, password hashing, TOTP enrollment

Scope: `git diff bd8a0ab..HEAD -- . ':!tasks' ':!reviews'` (final revision 554fefe). This is a re-review after the fixes for CR-1 and CR-2 of the earlier review. Only committed changes were reviewed; the working tree was clean.

Requirements:
- tasks/p0-auth-setup.md acceptance criteria and recorded assumptions (`python tasks.py assumptions`), including the 11:58 assumption that supersedes the replace rule
- SPEC.md §4.1 (setup flow, Argon2id, TOTP, recovery codes, secrets at rest), §4.2 (UUIDv7, timestamps, version, Problem Details)
- docs/ARCHITECTURE.md, AGENTS.md project notes, docs/QUALITY.md
- reviews/p0-auth-setup/fixes.md and tests.md (claims checked against the code, not taken as evidence)
- Existing conventions in core/web (ConflictException, ProblemDetailsAdvice incl. OptimisticLockingFailureException -> 409), core/persistence (BaseEntity, ClockConfiguration auditing via the Clock bean), core/crypto

Git range:
- Branch: main
- HEAD: 554fefe38aafe3ea29508ed113f20f2667729a50
- Base/start: bd8a0ab04a406983ebb528963d0eb234651a09a8
- Commits reviewed: 3 task code commits (b0b88f7, 7d86858, 2a44a4c) plus 554fefe (test + report); the other commits in the range are TFP bookkeeping (heartbeat/verify/assume, tasks/ only)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Commands run

- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*' --tests '*OpenApiContractTest'`: BUILD SUCCESSFUL. Results read from `build/test-results/test/*.xml`: SetupWebTests 11, SetupEdgeCaseTests 16, TotpTests 6, RecoveryCodesTests 5, Base32Tests 4, SetupSecretHidingTests 4, SecurityConfigurationTests 3 (49 auth tests), OpenApiContractTest 1; 0 failures, 0 errors, 0 skipped. The persistence tests ran against Postgres via Testcontainers. OpenApiContractTest passing without `UPDATE_OPENAPI` shows the committed `api/openapi.yaml` matches the running server.
- `git diff --stat b0b88f7..HEAD -- server/src/main web api`: since the initial implementation, only `SetupService.kt`, `SetupController.kt` (OpenAPI texts) and `api/openapi.yaml` (descriptions only) changed in production code. The generated web client is unchanged, which fits a description-only spec change.

## Status of earlier findings

- **CR-1 (P2, Security) | Pending owner account could be replaced by anyone: Resolved.**
  `SetupService.start()` (`server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/SetupService.kt:44-50`) now throws `Setup is already complete` for an active account first. Only after that does it call `mayReplace` (lines 79-89). `mayReplace` allows replacement if the pending account is older than `ABANDONED_AFTER` (1 h, strictly after), or if the password matches the stored Argon2id hash and the username is equal. In every other case the answer is a 409 `Setup is already in progress`, which does not say which check failed.
  Points checked:
  - Ordering: the active check runs before the abandonment check, so an active account can never be reopened. The test `the one-hour abandonment rule never reopens setup once the account is active` advances the clock 2 days and asserts 409 with the row unchanged.
  - Clock: `createdAt` is set by JPA auditing from the same `Clock` bean (`ClockConfiguration.clockDateTimeProvider`). It is truncated to microseconds, and `isAfter` makes the boundary exclusive. The tests cover the boundary: exactly 1 h gives 409, 1 h + 1 s allows the replacement.
  - Timing: when the account is not abandoned, the password hash is always checked, even if the username differs, so response time does not show whether the username matched. The abandoned path skips the hash, but whether setup is abandoned is not secret, so this leaks nothing.
  - Races: if two replacements run at once, the stale delete hits the `version` check (`OptimisticLockingFailureException` -> 409 through the existing advice). A concurrent first insert hits the unique index (`DataIntegrityViolationException` -> 409). The same applies when a confirm-totp races with an abandonment replacement: the confirm's save fails with 409 and does not activate the replaced row.
  - Docs: the assumption, the KDoc, the `@Operation` text and the 409 description in `api/openapi.yaml` all describe the new rule.
  - Tests: SetupWebTests covers replacement with the same credentials (new row id, old secret rejected) and three kinds of rejection (other username, wrong password, both), each with the row and the recovery-code hashes unchanged. SetupEdgeCaseTests covers the 1 h window and the active-account case.
  - Residual risk, accepted by the recorded assumption and not a finding: after 1 h without confirmation, anyone who can reach the server can take over setup. Before any setup starts, the first requester wins, which is inherent in SPEC.md §4.1.
- **CR-2 (P3, Tests) | Single-user race protection untested: Resolved.**
  The new test `the database rejects a second app_user row` (SetupEdgeCaseTests) inserts a second row through JDBC. It expects a `DataIntegrityViolationException` that names `app_user_single_row`, and checks that the original row is the only one left. The existing test `concurrent setup requests leave exactly one account and fail only with a conflict` (4 threads) covers the service-level mapping and still passes under the new rule.

## What was checked (full task diff)

- Criterion 1: GET /status, POST /setup and POST /setup/confirm-totp are implemented. The password is checked to be 12-256 characters and hashed with Argon2id (Spring v5.8 defaults). The response holds the base32 secret, the otpauth URI and 10 recovery codes, which are stored only as Argon2id hashes. Confirm allows ±1 step and compares in constant time.
- Criterion 2: once the account is active, POST /setup and POST /confirm-totp answer 409 (tested, including after the abandonment window). GET /status stays 200 with `setupRequired=false`, as recorded in an assumption. The TOTP secret is an `EncryptedSecret` (AES-GCM converter, from p0-crypto), and a test checks that the column does not contain the plaintext.
- Criterion 3: the RFC 6238 vectors pass, the tests compute codes from the returned secret, and Postgres runs via Testcontainers.
- Criterion 4: `api/openapi.yaml` is regenerated, and the contract test passes without the flag.
- Secrets: the request, response and enrollment types mask their `toString`, and the new code adds no log lines that contain secrets or credentials.
- No new issues were found in the fix. The added logic is small, sits inside the auth module, and uses the existing Clock, ConflictException and Problem Details conventions.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None. CR-1 is resolved (see above).

## P3 Low

None. CR-2 is resolved (see above).

## Review conclusion

No material issues found. CR-1 and CR-2 are resolved at revision 554fefe38aafe3ea29508ed113f20f2667729a50. The acceptance criteria are met, and the auth and OpenAPI contract tests pass.
