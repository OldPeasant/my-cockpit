# Code Review

Date: 2026-10-20

Task: p0-auth-setup - First-run setup: owner account, password hashing, TOTP enrollment

Scope: `git diff bd8a0ab..HEAD -- . ':!tasks' ':!reviews'` (task commit 2a2664d). Only committed changes; test files the test-writer may add at the same time were not reviewed.

Requirements:
- tasks/p0-auth-setup.md acceptance criteria and recorded assumptions (`python tasks.py assumptions`)
- SPEC.md §4.1 (setup flow, Argon2id, TOTP, recovery codes, secrets at rest), §4.2 (UUIDv7, timestamps, version, Problem Details)
- docs/ARCHITECTURE.md, AGENTS.md project notes, docs/QUALITY.md
- Existing conventions in core/web (ApiExceptions, ProblemDetailsAdvice), core/crypto (EncryptedSecret + converter), core/persistence (BaseEntity), core/spa
- tasks/p0-auth-login.md, to check what is deliberately left to the next task (SPA public, sessions, XSRF cookie, lockout)

Git range:
- Branch: main
- HEAD: 2a2664ddfb570dc7dc7dcfeca8479800d9b48133
- Base/start: bd8a0ab04a406983ebb528963d0eb234651a09a8
- Commits reviewed: 1 (2a2664d)

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## Commands run

- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'`: the first two runs stopped with a Gradle `java.io.EOFException` before any auth suite reported. This happened while another Gradle build was running in the same clone (the test-writer working in parallel), not because of a test failure. The third run passed: Base32Tests 4, SetupWebTests 10, TotpTests 5, SecurityConfigurationTests 3. That is 22 tests, 0 failures, 0 errors (read from `build/test-results/test/*.xml`).
- `cd server && ./gradlew test --tests '*OpenApiContractTest'`: BUILD SUCCESSFUL, 1 test, 0 failures. The committed `api/openapi.yaml` matches the running server.

## What was checked

- Criterion 1: `GET /status`, `POST /setup` and `POST /setup/confirm-totp` are implemented. The password must be at least 12 characters (`@Size(min = 12, max = 256)`) and is hashed with Argon2id (`Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8`; the test asserts `$argon2id$`). The response holds the base32 secret, the otpauth URI and 10 recovery codes. The codes are stored only as Argon2id hashes of their normalized form. Confirm verifies the code with ±1 step of drift and compares in constant time.
- Criterion 2: once the account is active, POST /setup and POST /confirm-totp answer 409 (tested). GET /status keeps answering 200 with `setupRequired=false`, as recorded in an assumption. The TOTP secret is an `EncryptedSecret` attribute, so it is persisted through the auto-applied AES-GCM converter. The test decrypts the column and checks it does not contain the plaintext.
- Criterion 3: the RFC 6238 Appendix B vectors for SHA1, SHA256 and SHA512 pass, and the web tests compute codes from the returned secret. The persistence tests run on Postgres via `PostgresTestBase`.
- Criterion 4: the OpenAPI document is regenerated, the contract test passes, and the generated web client is committed.
- Secrets: `SetupRequest`, `SetupResponse` and `SetupEnrollment` mask their `toString`. `EncryptedSecret` is redacted in `toString` and JSON. The INFO log lines hold no secrets. Problem details carry no internals.
- Single-user race: the unique index `app_user((true))` plus the `DataIntegrityViolationException` -> 409 mapping cover concurrent first inserts. Two concurrent replacements of a pending row hit the `version` check on delete, which the existing advice maps to 409.
- CSRF exemption of setup: cross-origin JSON POSTs need a CORS preflight, which is not enabled. A `text/plain` form post is rejected (415) by `@RequestBody`. The exemption only covers the setup paths, and the test proves that other POSTs still get 403.
- Requiring a login for the SPA (`anyRequest().authenticated()`) is not a regression: Boot's default security already answered 401, and making the SPA public is an acceptance criterion of p0-auth-login.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Any unauthenticated client can silently replace a pending owner account until its TOTP is confirmed**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/SetupService.kt:40`
    - Category: Security
    - Problem: `start()` deletes an existing account that is not yet active and creates a new one. It does not check who sent the request (`if (existing.isActive) throw setupComplete(); users.delete(existing)`). Between the owner's `POST /api/v1/setup` and their `confirm-totp`, anyone who can reach the public setup endpoint can send their own username and password. They receive a new secret, confirm it, and become the only account. The owner's confirm then fails with only a generic 400 ("is not a valid code"), which does not reveal what happened. SPEC.md §4.1 says setup is "only available while no user exists". The recorded assumption keeps it open for a pending user to handle a lost QR code, but that turns a window of one first request into one that lasts the whole enrollment, and makes a hijack possible after the owner has already started.
    - Why it matters: whoever controls this single account controls the whole instance, and right now it can be taken over without any credential.
    - Suggested fix: let `POST /setup` replace a pending account only if the request carries the pending account's current password (`passwordEncoder.matches`), or only after a short expiry such as 15 min since `createdAt`. Otherwise answer 409. A lost QR code can then be handled by the owner, who knows the password. Add a test that a second start with a different password returns 409 and keeps the first account. Update the assumption and the OpenAPI description.

## P3 Low

- [ ] **CR-2 | The single-user race protection is not tested**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/SetupService.kt:51`
    - Category: Tests
    - Problem: two layers enforce "at most one account": the `catch (DataIntegrityViolationException) -> ConflictException` path and the unique index `app_user_single_row` in `V202610201000__core_auth_user.sql`. No committed test covers either of them. SetupWebTests only checks a sequential replacement. A later migration or refactoring could drop the index or the mapping, and no test would fail.
    - Why it matters: this is the only guard that keeps two concurrent setup requests from creating two owner accounts.
    - Suggested fix: add a Postgres test that a second row inserted into `app_user` (via JDBC) is rejected. Also add a test where two `start()` calls run concurrently (for example with two threads and a latch) or where the repository throws, and check that the result is exactly one account and a 409 Problem Details response.

## Review conclusion

Material issues found; fix before completing the task. The acceptance criteria are met and verified by passing tests. CR-1 is a medium-severity security weakness that comes from a recorded assumption: the parent worker should either fix it or reject it with a written justification in `quality.json`. CR-2 is a non-blocking test gap.
