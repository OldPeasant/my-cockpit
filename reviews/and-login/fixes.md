# Fix Report

Date: 2026-10-20

Task: and-login - Android login and secure token storage

Scope: findings CR-1, CR-3, CR-4 from `reviews/and-login/review.md`. CR-2 was not in scope for the fixer. Changes stay inside `android/app/src/main/java/ch/sonensei/cockpit/auth` and `android/app/src/test/java/ch/sonensei/cockpit/auth`. Fix commit: 40335fd8f42209560b3f1203aec3269c1c535a02.

## Fixed findings

### CR-1 - Logout can be cancelled before the tokens are cleared (P2)

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/auth/AuthRepository.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/auth/SessionViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/SessionViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/AuthRepositoryTest.kt`

Problem:
`SessionViewModel.logout()` ran `repository.logout()` in `viewModelScope`. If the ViewModel was cleared during the revocation, which can take up to 5 s, `session.logout` and `clearCaches` never ran, so the tokens stayed stored. Each extra tap also started another parallel logout.

Fix:
`AuthRepository.logout()` now runs in `withContext(NonCancellable)`. The revocation is still limited by `REVOKE_TIMEOUT_MS`. The local part (`session.logout(USER)` and the cache clearing) sits in a `finally` block, so it runs even if the revocation throws. The revocation still happens before the tokens are cleared, which keeps the bearer token for the DELETE call and matches the recorded assumption. `SessionViewModel` keeps the running logout `Job` and ignores `logout()` while that job is active.

The existing regression test `a logout started from the app still forgets the tokens when its screen goes away during the revocation` now passes without changes. Two new tests were added: `taps while a logout runs start no second logout` and `a new logout can start once the previous one finished` (SessionViewModelTest).

The existing test `logout gives up on a server that does not answer the revocation and still logs out` was made deterministic. Its assertion that the request count is 2 raced with the virtual-time timeout: the timeout could cancel the OkHttp call before the call was sent. It also failed on the unmodified HEAD code when run on its own, so this is not a regression from the fix. The test now launches the logout and runs it up to the network call. It then blocks on `server.takeRequest` until the DELETE arrives and only then joins. All assertions are unchanged, and the test now also checks that the request is a DELETE.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*' --rerun` - Pass (93 tests, 0 failures)
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.AuthRepositoryTest.logout gives up*' --rerun` (run on its own) - Pass

### CR-3 - Keystore and disk work runs on the main thread (P3)

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/auth/AuthRepository.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/AuthRepositoryTest.kt`

Problem:
`AuthRepository.login` called `tokenStore.login` (Keystore encrypt) on the caller's dispatcher, which is Main when called from `viewModelScope`.

Fix:
`tokenStore.login(...)` now runs in `withContext(ioDispatcher)`. The dispatcher is a parameter of the new internal primary constructor. The Hilt `@Inject` secondary constructor passes `Dispatchers.IO`, the same pattern `PersistentTokenStore` already uses. New test: `the tokens of a login are stored off the calling thread`, which uses a counting dispatcher. The other part of the review's suggestion, warming the store's initial decrypt in `PersistentTokenStore` away from the main thread, is optional ("consider") and was not changed. It would require changes outside the auth folder (`CockpitApp`).

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'` - Pass

### CR-4 - A user logout clears the caches twice, concurrently (P3)

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/auth/AuthRepository.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/auth/SessionViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/AuthRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/SessionViewModelTest.kt`

Problem:
Two places cleared the caches: `AuthRepository.logout()` and an observer in `SessionViewModel`. The observer only covered logouts while that ViewModel was alive.

Fix:
`AuthRepository` (a singleton) is now the only owner. `logout()` clears the caches of a user logout itself. Every other logout, such as a refresh rejected by the network layer or a server change, is picked up from `AuthSession.loggedOut`, filtered to reasons other than `USER`. A collector in an app-lifetime scope handles it. It starts undispatched, so it subscribes before the constructor returns. The scope is injectable through the internal constructor; Hilt gets `CoroutineScope(SupervisorJob() + Dispatchers.Default)`. The ViewModel observer was removed, and `clearCaches()` is now private. The recorded assumption, "it runs on any logout, also a refresh rejection by the network layer", still holds.

The cache tests in SessionViewModelTest checked the removed observer through `repository.clearCaches()`. They were moved to the repository: `a logout by the network layer clears the caches once`, `a logout for a server change clears the caches once` and `a user logout clears the caches exactly once`. The routing assertions of the old SessionViewModelTest tests are kept.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'` - Pass

## Not fixed

### CR-2 - The real Keystore cipher's encrypt/decrypt round trip is never run by any test

Status:
Out of scope

Reason:
The parent worker excluded it. It needs an emulator or a device, which is not available here, and the parent will record it with `verify --skip`.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'` - Pass (93 tests, 0 failures; run several times with `--rerun`)

Full test suite:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` - BUILD SUCCESSFUL

Lint/type-check/build:
- ktlintCheck and lintDebug - Pass (included above)

`.taskcheck`:
- `sh scripts/check.sh` - Pass

Remaining unresolved findings:
1 (CR-2, out of the fixer's scope; to be recorded as skipped by the parent)

## Final status

All validated findings fixed and validation passed.

# Fix Report - round 2

Date: 2026-10-20

Task: and-login - Android login and secure token storage

Scope: the three P3 findings CR-1, CR-2 and CR-3 of the second review (`reviews/and-login/review.md`, reviewed revision 69121421). Changes stay inside `android/app/src/main/java/ch/sonensei/cockpit/auth` and `android/app/src/test/java/ch/sonensei/cockpit/auth`. Each new test was checked to fail against the code before the fix (the fixes were reverted temporarily: 4 of 54 tests in the two classes failed) and to pass with it.

## Fixed findings

### CR-1 - A user logout of an already revoked device clears the caches twice, concurrently (P3)

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/auth/AuthRepository.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/AuthRepositoryTest.kt`

Problem:
If the device was already revoked, the DELETE of a user logout got a 401, the `TokenAuthenticator` refresh was rejected, and `session.logoutIfCurrent(..., REFRESH_REJECTED)` made the app-scope collector clear the caches. The `finally` block of `logout()` then cleared them a second time, possibly at the same time.

Fix:
`logout()` notes whether tokens were stored when it started. In `finally` it clears the caches only if the tokens were not cleared by someone else during the revocation. If they were, that logout was already emitted on `AuthSession.loggedOut` with a reason other than `USER`, and the collector clears the caches. Each logout therefore clears them exactly once. The inline clearing of a normal user logout is unchanged, so it stays synchronous and the existing tests are unaffected.

New test: `a user logout of a device the server already revoked clears the caches exactly once`. It uses the real `TokenAuthenticator` with a refresher that answers `Rejected`, and the DELETE answers 401. The test checks that the tokens are gone and that the cache was cleared once.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.AuthRepositoryTest'` - Pass (fails with the old code: 2 clears instead of 1)

### CR-2 - A failing SessionCache crashes the app and stops later cache clears (P3)

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/auth/AuthRepository.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/AuthRepositoryTest.kt`

Problem:
`clearCaches()` let an exception from one `SessionCache.clear()` escape. The caches after it were skipped, `logout()` threw into `viewModelScope` (crash), and the app-scope collector ended, so later logouts cleared no caches.

Fix:
`clearCaches()` runs each cache in its own try/catch. It rethrows `CancellationException` and ignores other exceptions. They are not logged, because the exception may carry user data. The `SessionCache` KDoc now says that a failure is ignored and the other caches are still cleared.

New tests: `a failing cache neither stops the others nor the logout` (user logout with a throwing cache followed by a working one) and `a failing cache does not stop later logouts by the network layer from clearing the caches` (two network-layer logouts; the working cache is cleared both times, so the collector survived).

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.AuthRepositoryTest'` - Pass (both fail with the old code)

### CR-3 - Rotated tokens are persisted with an asynchronous apply() (P3)

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/auth/PersistentTokenStore.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/PersistentTokenStoreTest.kt`

Problem:
`set()` wrote with `apply()`. A process kill after `replace()` returned but before the queued write reached the disk would bring back the retired refresh token on the next start. The server revokes the device when it sees that token again.

Fix:
`set()` uses `prefs.edit(commit = encrypted != null)`. Every write that stores tokens (`login`, `save`, `replace` with new tokens) is now committed synchronously. Removals (logout, `clear()`, or a failed encryption) keep `apply()`. The only production callers that write tokens already run off the main thread: `AuthRepository.login` on `ioDispatcher`, and `TokenAuthenticator` calls `replace` on an OkHttp thread.

New test: `logins and rotated tokens are written to disk synchronously`. It wraps the preferences in a recording `SharedPreferences`/`Editor`, then checks that a login and a rotation each end in `commit()` and that a fresh store loads the rotated tokens.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.PersistentTokenStoreTest'` - Pass (fails with the old code)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*' --rerun` - Pass (115 tests, 0 failures)

Full test suite:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` - BUILD SUCCESSFUL

Lint/type-check/build:
- ktlintCheck and lintDebug - Pass (included above)

`.taskcheck`:
- `sh scripts/check.sh` - Pass ("all checks passed")

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.

# Fix Report - round 3

Date: 2026-10-20

Task: and-login - Android login and secure token storage

Scope: the two P3 findings CR-1 and CR-2 of the third review (`reviews/and-login/review.md`, reviewed revision a85a759f; fixer started from HEAD 31d3d616, base 9deb2abcbc462523ca6f2b1252c8a1a52744c16d). Changes are limited to `android/app/src/main/java/ch/sonensei/cockpit/auth/AuthRepository.kt` and `android/app/src/test/java/ch/sonensei/cockpit/auth/AuthRepositoryTest.kt`. `AuthSession.kt` was not changed. Each new test was checked against the code before the fix: with `AuthRepository.kt` temporarily restored to HEAD, 4 of 42 tests in `AuthRepositoryTest` failed, and those 4 were exactly the new tests. All of them pass with the fix.

## Fixed findings

### CR-1 - A cache that throws a CancellationException on its own skips the remaining caches and ends the app-scope collector (P3)

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/auth/AuthRepository.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/AuthRepositoryTest.kt`

Problem:
`clearCaches()` rethrew every `CancellationException`, including a `TimeoutCancellationException` that a cache's own `withTimeout` throws while no one has cancelled the caller. Two things followed. A user logout skipped the remaining caches and threw. The app-scope collector stopped silently, so no later network-layer or server-change logout cleared any cache.

Fix:
The `CancellationException` catch now calls `currentCoroutineContext().ensureActive()`. That call rethrows only when the calling coroutine itself is cancelled, i.e. when `appScope` is cancelled for the collector. It never rethrows under the `NonCancellable` of `logout()`. Otherwise the exception is treated like any other cache failure: the code ignores it and does not log it, because the exception may carry user data. That matches how other failures are handled, since none of them is logged. The `SessionCache` KDoc now states this behavior.

Tests added:
- `a cache that times out by itself neither stops the others nor the logout`: the first cache runs `withTimeout(1) { awaitCancellation() }` and a working cache follows it. The second cache still runs and the user logout completes with the tokens cleared. On the old code it fails with `TimeoutCancellationException`.
- `a cache that throws a cancellation by itself does not stop later logouts by the network layer from clearing the caches`: the first cache throws `CancellationException`. Two successive `REFRESH_REJECTED` logouts each still clear the working cache (2). On the old code the result was 0.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'` - Pass

### CR-2 - The skipped inline cache clear depends on a logout event that the SharedFlow can drop (P3)

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/auth/AuthRepository.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/AuthRepositoryTest.kt`

Problem:
`logout()` skipped its own `clearCaches()` when the tokens disappeared during the revocation, and relied on the collector seeing `REFRESH_REJECTED`. `AuthSession.loggedOut` has a one-slot `DROP_OLDEST` buffer. If the collector had not yet taken that event, the `USER` event that `logout()` emits replaced it, and no cache was cleared.

Fix:
This follows the review's option 1. `logout()` now always clears the caches itself after `session.logout(USER)`, and the `wasLoggedIn`/`loggedOutMeanwhile` skip is removed. `clearCaches()` runs under a `Mutex` (`cacheClearing.withLock`), so clears from the collector and from `logout()` never overlap. A user logout of an already revoked device can therefore clear the caches twice, one clear after the other. This is accepted: caches must be idempotent, and the `SessionCache` and `AuthRepository` KDocs now say so. `AuthSession.kt` is unchanged.

Tests changed:
- `a user logout of a device the server already revoked clears the caches exactly once` is renamed to `... clears the caches at least once`. It now asserts `cleared >= 1`. With the Unconfined collector the count is now deterministically 2.
- `a user logout clears the caches exactly once` and `a user logout whose revocation needed a token refresh still clears tokens and caches exactly once` stay as they are. In both cases no non-USER event reaches the collector, so exactly one clear is still correct.

Tests added:
- `a user logout clears the caches even when the network layer's logout event is dropped`: the app scope runs on a `StandardTestDispatcher` that is advanced only after `logout()` returns. The revocation gets a 401 and the refresh is rejected. The test asserts that the logout itself cleared the caches (1). After `advanceUntilIdle()` the count is still 1, which shows that the collector only saw `USER` and that `REFRESH_REJECTED` was dropped. On the old code the result was 0.
- `cache clears of a user logout and of the network layer never run concurrently`: the collector runs on `Dispatchers.Default` and uses a slow cache (`delay(200)`). The test waits until the collector is inside its clear, then runs a user logout. It asserts 2 completed clears with at most 1 active at any time. On the old code the maximum was 2 at once.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'` - Pass
- `cd android && ./gradlew testDebugUnitTest --rerun --tests 'ch.sonensei.cockpit.auth.AuthRepositoryTest'`, run 3 more times to check the timing-based tests for flakiness - Pass each time

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'` - Pass (120 tests in the auth package, 0 failures, 0 errors, 0 skipped, counted from `app/build/test-results/testDebugUnitTest/TEST-ch.sonensei.cockpit.auth.*.xml`)

Full test suite:
- Part of `.taskcheck` below (android: ktlint + unit tests + lint) - Pass

Lint/type-check/build:
- ktlintCheck - Pass (focused run). lintDebug - Pass (in `.taskcheck`).

`.taskcheck`:
- `sh scripts/check.sh` - Pass ("all checks passed"). The web bundle budget warning it prints was already there and is unrelated to this change.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
