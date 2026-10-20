# Code Review

Date: 2026-10-20

Task: and-login - Android login and secure token storage

Scope: `git diff 9deb2abcbc462523ca6f2b1252c8a1a52744c16d..a85a759f2b85c4d7368747b80b714996ff818abf`, all task commits, with the focus on the round-2 fix commit 7b6fd27a (`AuthRepository.kt`: caches cleared once per logout, each cache in its own try/catch; `PersistentTokenStore.kt`: `commit` for token writes) and the test added in a85a759f. Committed changes only; the working tree was clean. This is the final review round. Reviewed revision: `a85a759f2b85c4d7368747b80b714996ff818abf`.

Requirements:
- tasks/and-login.md: acceptance criteria 1-3 and the recorded assumptions:
    - direct Keystore AES-GCM that fails closed;
    - passkeys left to and-passkeys;
    - best-effort device revocation with a 5 s timeout;
    - SessionCache multibinding plus cacheDir, cleared on any logout;
    - logout under Settings;
    - 30 s default Retry-After.
- SPEC.md section 4.1 (Android refresh token in Keystore-backed storage, revocable devices, login rate limiting) and section 9.2 item 1 (login with password + TOTP, server URL)
- docs/ARCHITECTURE.md (Android MVVM, one package per feature, lint, coverage) and the AGENTS.md project notes (no secrets logged, stay inside `touches`, fictitious test data)
- Server contract: refresh rotation; reusing a retired refresh token revokes the device
- Earlier evidence: reviews/and-login/review.md (rounds 1 and 2), fixes.md (rounds 1 and 2), tests.md (final pass)

Git range:
- Branch: main
- HEAD: a85a759f2b85c4d7368747b80b714996ff818abf
- Base/start: 9deb2abcbc462523ca6f2b1252c8a1a52744c16d (supplied by the parent)
- Commits reviewed: 12 (43cb7794, 833b3757, d6ddcb1a, 562fff8c, f5bb2d6e, 534a9130, fca3d3f4, 87dc794a, b90238d0, 4c6e1dda, 7b6fd27a, a85a759f)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Status of the earlier findings

Round 1:
- **CR-1 (P2, logout cancelled before the tokens are cleared): fixed.** It was verified in round 2 and is unchanged. `logout()` runs in `withContext(NonCancellable)`, and `session.logout(USER)` runs in `finally` (AuthRepository.kt:163-184).
- **CR-2 (P2, real Keystore round trip never run): partly addressed. Device-level evidence is still open.** `KeystoreTokenCipherTest` runs the production cipher against a stand-in "AndroidKeyStore" JCA provider. tests.md states that hardware-backed key generation and OS key invalidation were not run. `tasks.py verify and-login` still shows all three criteria unticked. The parent must record the device-level Keystore part of criterion 2 with `--skip` and a reason, and must not claim it from the JVM suite.
- **CR-3 (P3, Keystore work on the main thread): fixed for login.** The initial decrypt in the store's initializer was accepted as is.
- **CR-4 (P3, caches cleared twice): fixed.** `AuthRepository` is the single owner of cache clearing.

Round 2:
- **CR-1 (P3, a user logout of an already revoked device clears the caches twice): fixed.** It is covered by `a user logout of a device the server already revoked clears the caches exactly once`. The case "tokens rotated but still present" is covered by the new test in a85a759f. The fix depends on the app-scope collector receiving the network layer's logout event. That event can be dropped in a race; see CR-2 below.
- **CR-2 (P3, a failing SessionCache crashes the app and stops later cache clears): fixed for ordinary exceptions.** Each cache runs in its own try/catch (AuthRepository.kt:186-196), and two tests cover it. One gap remains for a cache that throws a `CancellationException` on its own, for example from `withTimeout`; see CR-1 below.
- **CR-3 (P3, rotated tokens persisted with `apply()`): fixed.** `prefs.edit(commit = encrypted != null)` (PersistentTokenStore.kt:79-84) commits logins and rotations synchronously. Removals keep `apply()`. The only callers that write tokens run off the main thread: `AuthRepository.login` on `ioDispatcher`, and `TokenAuthenticator.replace` on an OkHttp thread (checked with grep). A recording-editor test covers it.

Also verified in the final code:
- The login request and the mapping of 400, 401 and 429 are correct. The countdown is correct.
- Secrets are cleared from the form after use, and `toString` redacts them.
- The auth package has no logging.
- Undecryptable stored records are deleted and the app fails closed.
- Start-up routing follows `tokenStore.session`.
- `TokenStore` is bound to `PersistentTokenStore`.

Changes outside `touches` are small hooks that the recorded assumptions name: `MainActivity`, `SettingsScreen`, the `NetworkModule` binding and a KDoc line.

Test run by this review: `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'` -> BUILD SUCCESSFUL. A fresh run at 21:24 gave 116 tests in 10 classes, 0 failures, 0 errors and 0 skipped (counted from `app/build/test-results/testDebugUnitTest/TEST-ch.sonensei.cockpit.auth.*.xml`). This matches tests.md.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A cache that throws a CancellationException on its own skips the remaining caches and ends the app-scope collector**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/auth/AuthRepository.kt:190-191` (also the KDoc at `:69-72` and the collector at `:105-107`)
    - Category: Correctness
    - Problem: `clearCaches()` rethrows every `CancellationException`, including one that the cache itself throws while nobody has cancelled the caller. The common case is a `TimeoutCancellationException` from `withTimeout` inside `clear()`, a well-known kotlinx pitfall. I checked the test-writer's observation, and it holds.
        - In `logout()` (under `NonCancellable`), the remaining caches are skipped and `logout()` throws. The tokens are already cleared at that point (`session.logout(USER)` runs first). `viewModelScope` treats the exception as a cancellation, so there is no crash.
        - In the app-scope collector, the exception ends the `collect` coroutine without any error. From then on, no network-layer or server-change logout clears any cache until the process restarts. Round-2 CR-2 set out to prevent exactly that failure mode.
        - The `SessionCache` KDoc promises that "if [clear] throws, the failure is ignored and the other caches are still cleared", and this case breaks that promise.
    - Why it matters: today's only cache is the cacheDir deletion, which does not time out, so there is no impact yet. But `SessionCache` is the extension point that feature tasks will use (for example Room clears), and a timeout there would silently turn off cache clearing on logout.
    - Is it worth fixing now: yes. The fix is a one-line change in code this task owns, plus one test. Otherwise the documented contract is wrong for the first contributor that uses a timeout.
    - Suggested fix: rethrow only when the caller is really cancelled. Use `catch (e: CancellationException) { currentCoroutineContext().ensureActive() }` and let the generic `catch` ignore the rest. Under `NonCancellable`, `ensureActive()` never throws. In the collector it still propagates cancellation of `appScope`. Add a test with a cache that throws `TimeoutCancellationException` (or a plain `CancellationException`) followed by a working cache. Run it once for a user logout and once for two network-layer logouts.

- [ ] **CR-2 | The skipped inline cache clear depends on a logout event that the SharedFlow can drop**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/auth/AuthRepository.kt:180-182` (and `:105-107`). The buffer is configured at `android/app/src/main/java/ch/sonensei/cockpit/core/network/AuthSession.kt:30`, which predates this task.
    - Category: Correctness
    - Problem: the round-2 CR-1 fix skips the inline `clearCaches()` when the tokens disappeared during the revocation, and assumes that the collector clears the caches for the `REFRESH_REJECTED` event instead. `AuthSession.loggedOut` is a `MutableSharedFlow` with `extraBufferCapacity = 1` and `DROP_OLDEST`, and both events use `tryEmit`. The race runs as follows:
        - In production the collector runs on `Dispatchers.Default`.
        - `logoutIfCurrent(..., REFRESH_REJECTED)` is emitted on the OkHttp thread. It only queues the collector's resumption.
        - The collector has not yet taken the value when `logout()`'s `finally` on Main calls `session.logout(USER)`.
        - That `tryEmit(USER)` finds the one-slot buffer full and drops `REFRESH_REJECTED`.
        - The collector then receives only `USER`, filters it out, and no cache is cleared.

      The tests do not show this because they run the app scope on `Dispatchers.Unconfined`, which consumes each event synchronously inside `tryEmit` (AuthRepositoryTest.kt:485, :672). Before round 2 the inline clear always ran, so a dropped event was harmless.
    - Why it matters: in this window, logging out a device that the server has already revoked leaves user caches in place, which goes against criterion 2 ("logout clears tokens and caches") and the recorded assumption that caches are cleared on any logout. The window is narrow. The Default thread usually wins against the Main-thread hop, and Main is also busy recomposing to the login screen at that moment. That is why this is P3.
    - Suggested fix (inside `auth/`): prefer a possible double clear over a missed clear.
        - Option 1: always clear inline in `logout()` and serialize `clearCaches()` with a `Mutex`. Repeated clears of idempotent caches are then harmless and never concurrent. This addresses the "concurrently" concern of round-2 CR-1.
        - Option 2: keep the skip, but only when the collector has actually started a clear since the logout began, for example a counter that the collector increments.
      Add a test whose app scope uses a `StandardTestDispatcher` that is advanced only after `logout()` returns, and assert that the caches were cleared at least once.

## Review conclusion

No material issues found. All earlier P2 and P3 findings are fixed, apart from the device-level Keystore evidence, which must be recorded with `verify --skip` and a reason. The two remaining findings are P3 hardening of the `SessionCache` extension point and do not block completion. CR-1 costs one line and one test, so it is worth taking now. CR-2 is a narrow race with no data impact beyond leftover cache files today. The parent decides whether to fix it now or record it.
