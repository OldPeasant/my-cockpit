# Code Review

Date: 2026-10-22

Task: passkeys-server - Passkeys (WebAuthn) backend

Scope: `git diff 99936ef802eab20a25c99700b72d6dfc6dee7c90 e111036da34cc43b3f139e955eea8db9569f8954` (base = merge-base with origin/main). Commits 149437b1 (implementation), 706267e8 (test-writer tests), cdf39218 (fix for CR-1/TW-1), 993b14dc (server test heap) and e111036d (two concurrency tests in `PasskeyTotpReenrollmentTests`, fresh `tests.md`). The generated `api/openapi.yaml` and `web/src/app/api/generated/` were checked for consistency only. This report replaces the review of `99936ef8..993b14dc`. The earlier conclusions are carried over because the production code did not change in e111036d. The new review work is on the test commit.

Requirements:
- `tasks/passkeys-server.md`: acceptance criteria and all recorded assumptions, including the 2026-10-22 TOTP re-enrollment assumption and the test-heap assumption
- `SPEC.md` §4.1, §4.2 and §10.2; `docs/ARCHITECTURE.md`; the `AGENTS.md` project notes; `docs/QUALITY.md`
- Parent instructions: review the final code, focusing on the new tests (correctness, flakiness risk, cleanup of executors and latches)

Git range:
- Branch: main
- HEAD: e111036da34cc43b3f139e955eea8db9569f8954
- Base/start: 99936ef802eab20a25c99700b72d6dfc6dee7c90
- Commits reviewed: 5 (149437b1, 706267e8, cdf39218, 993b14dc, e111036d). The working tree was clean at HEAD e111036d when the review started, so the files I read are the committed revision.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## New tests in e111036d

The two new tests are in `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/passkey/PasskeyTotpReenrollmentTests.kt:316-370`. The class KDoc (lines 52-58) gained one clause about the sign counter.

### "a second passkey login with the same passkey waits for the first, then continues from its sign count" (lines 316-341)

- `TestAuthenticator.authenticate` increments `signCount` before it signs. Registration stores count 0, the first assertion carries 1 and the second carries 2.
- The first login holds its transaction through `heldTransaction`, which owns the `findFirstForUpdate` lock. The second login must block in `PasskeyService.verifyAssertion:245` before it reads the passkey at line 246. After the release, the second login reads the committed row (version and count 1) and saves count 2.
- Each `@Transactional` call runs on its own executor thread, so each has its own persistence context and no stale entity carries over.
- The final `sign_count = 2` check and the `Success` assertion check the right things.

### "two concurrent assertions with the same sign count, as from a cloned authenticator, log in only once" (lines 343-370)

- Setting `authenticator.signCount = 0` before the second `authenticate` produces a second assertion with count 1, the same as the first.
- After the first login commits count 1, webauthn4j's counter check fails. It throws a `VerificationException`, which `verifyAssertion:252-253` maps to `InvalidCredentials`.
- `auth_session` = 2 is correct: one session is the `@BeforeEach` password `webLogin()` and the other is the first passkey login. `register()` creates no session.
- `sign_count = 1` confirms that the replay did not write.

### Flakiness

- The negative wait `shouldThrow<TimeoutException> { future.get(500 ms) }` cannot fail by mistake while the lock works, because the waiter is blocked on a database row lock until `release.countDown()`. On a very slow machine the worst case is a false pass: the second task may not have reached the lock within 500 ms. The positive assertions afterwards (success and sign count, or rejection) still hold in that case, so the test does not become flaky. This is the same pattern as the three earlier concurrency tests in the class.
- The pool has 2 threads. One is held by `heldTransaction` and the other runs the competing call, so neither task waits for a free thread. The main thread uses its own connection only after both futures finish.
- `heldTransaction` waits for `ran` before it returns. This ensures that the lock is held before the competing call is submitted. All waits are bounded to 10 s, so a regression fails the test instead of hanging the check.

### Cleanup

- Every test calls `release.countDown()` in `finally` and then `executor.shutdownNow()`. On the success path both futures have completed, including their commit, before `finally` runs, so no thread is left over.
- On a failure path the held transaction is released first. A thread still blocked on the row lock then proceeds, and `@AfterEach`'s `delete from app_user` waits on the same row lock, so cleanup is serialized by the database. There is no `awaitTermination`, but given the bounded waits I found no concrete leak that would affect other tests.
- The throttle failure that the rejected replay records is reset by `@AfterEach` (`throttle.recordSuccess()`).

### Report consistency

- `reviews/passkeys-server/tests.md` describes these two tests accurately.
- It reports `PasskeyTotpReenrollmentTests` 11/11 and the auth selection with 280 tests and 0 failures. I did not re-run the tests in this review (read-only inspection).

## Verification of the earlier finding (carried over, code unchanged)

- **CR-1 (resolved).** In `PasskeyService.verifyAssertion` (`server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/domain/PasskeyService.kt:242-247`), `users.findFirstForUpdate()` runs before `passkeys.findByCredentialId(...)`.
    - As a result, the account and the passkey are read only after the row lock is held. This is the same order as `LoginService.verify` and `TotpEnrollmentService`.
    - The two TW-1 tests (lines 218-281) cover the re-enrollment side, and the new tests cover the sign-counter side.
- **Nothing new introduced by the fix.**
    - Throttle admission, single-use ceremonies, parsing before lookup, the uniform `InvalidCredentials` result and the log reasons are unchanged.
    - Requests for an unknown passkey briefly hold the account lock. This matches the password login, and both are bounded by the shared `LoginThrottle`.

## Heap change (993b14dc)

- `server/build.gradle.kts` raises the test JVM `maxHeapSize` from 1g to 2g and explains why in a comment. The change is recorded as a task assumption.
- The `.taskcheck` duration above 2 minutes is assigned to `ci-split-check` and is not a defect of this task.

## Verified without findings (carried over, production code unchanged)

- Challenges, ceremonies and relying party:
    - Each ceremony gets 32 random bytes from `SecureRandom`, is single use and expires after 5 minutes.
    - Registration and authentication ceremonies are kept in separate bounded stores, and a registration ceremony is bound to its owner.
    - The RP ID and origin come from the stored ceremony, not from the finishing request.
- WebAuthn verification:
    - User verification and user presence are required, and attestation is `none`.
    - The sign counter is checked and persisted. The user handle must match the owner.
- Re-enrollment:
    - Registration is refused while TOTP re-enrollment is required.
    - A passkey web login gets a session that may only enroll TOTP again.
    - A passkey device token request gets 403 `TotpEnrollmentRequired`.
    - A restore wipes `auth_passkey`.
- Errors and logging: errors are Problem Details. No secrets, challenges, credentials, tokens or user handles are logged.
- Migration: `core/V202610221000__core_auth_passkey.sql` is new and follows the naming convention. No applied migration was edited.
- CSRF and permits: only the authentication-options, login and token endpoints are public. The web login still needs the CSRF header.
- Generated files: `api/openapi.yaml` and the generated web client match the controller and DTOs.

## Review conclusion

No material issues found.
