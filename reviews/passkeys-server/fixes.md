# Fix Report

Date: 2026-10-22

Task: passkeys-server - Passkeys (WebAuthn) backend

Scope: current task; findings TW-1 (`reviews/passkeys-server/tests.md`) and CR-1 (`reviews/passkeys-server/review.md`), the same defect.

## Fixed findings

### TW-1 / CR-1 — Passkey login loads the account before taking the row lock

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/domain/PasskeyService.kt`

Problem:
`verifyAssertion` loaded the passkey with `join fetch p.user` before `users.findFirstForUpdate()`. That put an
unlocked `UserAccount` (and the `Passkey`) into the persistence context. If a TOTP re-enrollment confirm (or another
passkey login) committed between the two reads, the locked read hit a newer version of an already-managed entity and
failed with an optimistic-locking error (409) instead of waiting and then seeing the committed state. The two
`PasskeyTotpReenrollmentTests` "...while a confirm is not committed yet waits..." tests failed.

Fix:
`verifyAssertion` now takes the account row lock first (`users.findFirstForUpdate()`, as `LoginService.verify` does)
and loads the passkey only afterwards. The passkey query's `join fetch p.user` then resolves to the already locked,
current account, and the passkey row (sign counter, version) is read after the lock, so concurrent passkey logins are
serialised by the account lock too. Throttle admission, single-use ceremony consumption, response parsing, and the
error mapping (every failure is `InvalidCredentials`, with the same log reasons) are unchanged. The repository query
was left as it is: once the account is managed and locked, the join fetch no longer causes a version check.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.passkey.*' --tests 'ch.sonensei.cockpit.core.auth.*'` — Pass (278 tests, 0 failures, 0 errors; `PasskeyTotpReenrollmentTests` 9/9, including the two TW-1 regression tests)
- `cd server && ./gradlew ktlintCheck` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.passkey.*' --tests 'ch.sonensei.cockpit.core.auth.*'` — Pass, 278 tests, 0 failed (Postgres via Testcontainers)

Full test suite:
- Not run by the fixer. `tests.md` reports an `OutOfMemoryError` in `FilesWebTests` during `./gradlew check` that is outside this task; the parent worker runs `.taskcheck`.

Lint/type-check/build:
- `cd server && ./gradlew ktlintCheck` — Pass (compile is part of the test run above)

`.taskcheck`:
- Not run (left to the parent worker, see above)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
