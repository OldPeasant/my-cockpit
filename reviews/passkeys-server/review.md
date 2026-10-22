# Code Review

Date: 2026-10-22

Task: passkeys-server - Passkeys (WebAuthn) backend

Scope: `git diff 99936ef802eab20a25c99700b72d6dfc6dee7c90 993b14dc77dfb64d3cdd39079dcb98dcf9c7336e` (base = merge-base with origin/main). Commits 149437b1 (implementation), 706267e8 (test-writer tests), cdf39218 (fix for CR-1/TW-1) and 993b14dc (server test heap). The generated `api/openapi.yaml` and `web/src/app/api/generated/` were checked for consistency only. Uncommitted test changes from the parallel test-writer were ignored. This report replaces the review of 3f6b7f4b + da26f8db.

Requirements:
- `tasks/passkeys-server.md`: acceptance criteria and all recorded assumptions, including the 2026-10-22 TOTP re-enrollment assumption and the test-heap assumption
- `SPEC.md` §4.1, §4.2 and §10.2; `docs/ARCHITECTURE.md`; the `AGENTS.md` project notes; `docs/QUALITY.md`
- Parent instructions: confirm that CR-1 is resolved and that the fix introduces nothing new

Git range:
- Branch: main
- HEAD: 993b14dc77dfb64d3cdd39079dcb98dcf9c7336e
- Base/start: 99936ef802eab20a25c99700b72d6dfc6dee7c90
- Commits reviewed: 4 (149437b1, 706267e8, cdf39218, 993b14dc). There were no uncommitted changes when the review started.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Verification of the previous finding

- **CR-1 (resolved).** The issue was that passkey login loaded the account before taking the row lock.
    - `git range-diff af1ceb6b..da26f8db 99936ef8..706267e8` shows that 149437b1 and 706267e8 are identical to the previously reviewed 3f6b7f4b and da26f8db. The earlier review still applies to those two commits. The only new code is cdf39218 and 993b14dc.
    - In `PasskeyService.verifyAssertion` (`server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/domain/PasskeyService.kt:245-247`), `users.findFirstForUpdate()` now runs before `passkeys.findByCredentialId(...)`. This lock is a `PESSIMISTIC_WRITE` on `select u from UserAccount u` (`UserAccountRepository.kt:19-21`).
    - As a result, the `join fetch p.user` in the passkey query resolves to the account that is already managed and locked, so the version check that caused the 409 no longer happens. The `Passkey` row (sign counter, version) is now read after the lock. This also serializes concurrent logins with the same passkey, so `saveAndFlush(passkey)` no longer hits a stale version.
    - This is the same order as `LoginService.verify` (`LoginService.kt:97`) and `TotpEnrollmentService` (`TotpEnrollmentService.kt:77`), which take the same lock.
- **Regression coverage.** The two TW-1 tests in `PasskeyTotpReenrollmentTests` cover the fix:
    - "a passkey token request that runs while a confirm is not committed yet waits..."
    - "a passkey web login that runs while a confirm is not committed yet waits..."

  Both run real concurrent transactions against Postgres: a `TransactionTemplate` holds the confirm's row lock, and `CountDownLatch` controls when it is released. `reviews/passkeys-server/fixes.md` records `./gradlew test --tests 'ch.sonensei.cockpit.core.auth.passkey.*' --tests 'ch.sonensei.cockpit.core.auth.*'` with 278 tests and 0 failures, including `PasskeyTotpReenrollmentTests` 9/9. I did not re-run the tests in this review (read-only inspection).
- **Nothing new introduced by the fix.**
    - Throttle admission, single-use ceremony consumption, the order of parsing before lookup, the uniform `InvalidCredentials` result and the log reasons are unchanged.
    - The active, owner-id and user-handle checks still run against the locked account.
    - Requests for an unknown passkey now hold the account lock briefly before they are rejected. This matches the password login, which locks on every attempt, and both are bounded by the shared `LoginThrottle`.
    - The updated comment describes the code accurately.

## Heap change (993b14dc)

- `server/build.gradle.kts:102-104` raises the test JVM `maxHeapSize` from 1g to 2g and explains why in a comment.
- The change is recorded as an assumption in the task, because it falls outside the task's `touches`. No document or CI file names the old 1g value, so no documentation is now stale.
- The `.taskcheck` duration above the 2-minute target is recorded in the assumption and assigned to `ci-split-check`. It is not a defect of this task.

## Verified without findings (carried over, code unchanged since the previous review)

- Challenges, ceremonies and relying party:
    - Each ceremony gets 32 random bytes from `SecureRandom`, is single use and expires after 5 minutes.
    - Registration and authentication ceremonies are kept in separate bounded stores, and a registration ceremony is bound to its owner.
    - The RP ID and origin come from the stored ceremony, not from the finishing request. The defaults derived from `COCKPIT_DOMAIN` behave as recorded in the assumptions.
- WebAuthn verification:
    - User verification and user presence are required, and attestation is `none`.
    - The sign counter is checked and persisted. The user handle must match the owner.
- Re-enrollment: registration is refused while TOTP re-enrollment is required. A passkey web login gets a session that may only enroll TOTP again, and a passkey device token request gets 403 `TotpEnrollmentRequired`. A restore wipes `auth_passkey`.
- Errors and logging: errors are Problem Details. Nothing secret is logged: no TOTP codes, challenges, credentials, tokens or user handles.
- Migration: `core/V202610221000__core_auth_passkey.sql` is new, follows the naming convention and does not collide with another version. No applied migration was edited.
- CSRF and permits: only the authentication-options, login and token endpoints are public. The web login still needs the CSRF header.
- Generated files: `api/openapi.yaml` and the generated web client match the controller and DTOs.

## Review conclusion

No material issues found.
