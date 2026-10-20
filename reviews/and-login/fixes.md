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
