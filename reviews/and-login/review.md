# Code Review

Date: 2026-10-20

Task: and-login - Android login and secure token storage

Scope: `git diff 7982562bfa14d1979d9b8b2c884ce23f4df04b57..3334c04df40463f249c3fe4125101ef0ab49d562`, which covers all task commits. This round (round 4) focuses on the round-3 fix commit f91b5465 and on the tests added in 30022fa6 and 3334c04d:
- `AuthRepository.kt`: user logout always clears the caches inline; `clearCaches()` is serialized with a `Mutex`; a `CancellationException` thrown by a cache is ignored unless the caller is really cancelled (`ensureActive()`).

Only committed changes were reviewed; the working tree was clean. Reviewed revision: `3334c04df40463f249c3fe4125101ef0ab49d562`.

Requirements:
- tasks/and-login.md: acceptance criteria 1-3 and the recorded assumptions:
    - direct Keystore AES-GCM that fails closed;
    - passkeys left to and-passkeys;
    - best-effort device revocation with a 5 s timeout;
    - SessionCache multibinding plus cacheDir, cleared on any logout;
    - logout under Settings;
    - 30 s default Retry-After.
- SPEC.md section 4.1 and section 9.2 item 1
- docs/ARCHITECTURE.md and the AGENTS.md project notes
- Earlier evidence: reviews/and-login/review.md (rounds 1-3), fixes.md, tests.md (final pass at 3334c04d)

Git range:
- Branch: main
- HEAD: 3334c04df40463f249c3fe4125101ef0ab49d562
- Base/start: 7982562bfa14d1979d9b8b2c884ce23f4df04b57 (supplied by the parent)
- Commits reviewed: 16 (67ec5d03 through 3334c04d)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of the round-3 findings

- **CR-1 (P3): a cache that throws a CancellationException on its own skipped the remaining caches and ended the collector. Fixed.**
    - `clearCaches()` (AuthRepository.kt:195-209) now catches `CancellationException` and calls `currentCoroutineContext().ensureActive()`.
    - Under `logout()`'s `NonCancellable`, the job is always active, so a cancellation the cache throws itself is ignored and the next cache still runs. The caller's screen going away does not change that either.
    - In the app-scope collector, a real cancellation of `appScope` still propagates. A cancellation thrown by the cache itself is ignored, so the collector survives it.
    - The tests cover these cases:
        - `a cache that times out by itself neither stops the others nor the logout` (a real `withTimeout`);
        - `a cache that throws a cancellation by itself does not stop later logouts by the network layer from clearing the caches` (two collector passes);
        - `a user logout whose caller is cancelled during a cache clear still clears every cache`;
        - `cancelling the app scope stops its cache clear without blocking a later user logout from clearing every cache`. It shows that `withLock` releases the mutex when the collector is cancelled.
    - The KDoc of `SessionCache` now matches the behavior, and it states the new rule that `clear()` must be idempotent.
- **CR-2 (P3): the skipped inline clear depended on a logout event that the SharedFlow can drop. Fixed with option 1.**
    - `logout()` now always calls `clearCaches()` after `session.logout(USER)` (AuthRepository.kt:185-192). The `wasLoggedIn`/`loggedOutMeanwhile` skip is gone.
    - The `Mutex` keeps the collector's clear and the inline clear from running at the same time. `withLock` suspends rather than blocks, so a user logout called from `viewModelScope` (Main) does not block the thread while the collector clears.
    - No deadlock path exists. `clearCaches()` is only called from `logout()` and from the collector, and neither calls the other while holding the lock. `tryEmit` never suspends.
    - The DROP_OLDEST analysis holds:
        - A USER event that drops a pending non-USER event is followed by the inline clear.
        - A non-USER event that drops another non-USER event still causes a later collector clear.
        - The KDoc claim "never misses a clear" is therefore correct.
    - The tests cover these cases:
        - `a user logout clears the caches even when the network layer's logout event is dropped` reproduces the race with a `StandardTestDispatcher` that is advanced only after `logout()` returns, as round 3 suggested.
        - `cache clears of a user logout and of the network layer never run concurrently` (real `Dispatchers.Default`, `maxActive == 1`).
        - The earlier "exactly once" test was relaxed to "at least once". That matches the documented behavior and does not weaken a guarantee the code still makes.

No new problems were found in f91b5465, 30022fa6 or 3334c04d. The changes stay inside `auth/` and the auth tests. No logging was added, and the test data is fictitious.

Earlier findings that remain the parent's responsibility, not code defects:
- Round-1 CR-2: the device-level Keystore round trip (hardware key generation, OS key invalidation) was not run. `KeystoreTokenCipherTest` exercises the production cipher only against a JVM stand-in provider. `python tasks.py verify and-login` still lists all three criteria as unticked. When recording criterion 2, the device-level Keystore part must be marked with `--skip` and a reason (or verified on a device), not claimed from the JVM suite.

Test run by this review: `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*' ktlintCheck` -> BUILD SUCCESSFUL. The run finished at 2026-10-20T20:00Z and gave 122 tests in 10 classes, 0 failures, 0 errors, 0 skipped (counted from `app/build/test-results/testDebugUnitTest/TEST-ch.sonensei.cockpit.auth.*.xml`). ktlintCheck passed. This matches tests.md.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. Both round-3 findings are fixed correctly and covered by tests, and the fixes introduce no new problems. Before `done`, the parent must still record evidence for criteria 1-3 with `tasks.py verify`. For criterion 2, the device-level Keystore part must be recorded with `--skip` and a reason.
