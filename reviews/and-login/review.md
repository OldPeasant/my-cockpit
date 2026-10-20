# Code Review

Date: 2026-10-20

Task: and-login - Android login and secure token storage

Scope: `git diff 6604ff34778f12ee5d7292feab42f1490c54c8aa..69121421df27b3b3c77876a114b7f3fc99336ab3`. This is a re-review of the final code after fixes CR-1, CR-3 and CR-4 and the added tests. Committed changes only; the working tree was clean. Reviewed revision: `69121421df27b3b3c77876a114b7f3fc99336ab3`.

Requirements:
- tasks/and-login.md: acceptance criteria 1-3 and the recorded assumptions (direct Keystore AES-GCM that fails closed; passkeys left to and-passkeys; best-effort device revocation with a 5 s timeout; SessionCache multibinding plus cacheDir; logout under Settings; 30 s default Retry-After)
- SPEC.md section 4.1 (Android: refresh token in Keystore-backed storage, devices revocable, login rate limiting) and section 9.2 item 1 (login with password+TOTP, server URL setting)
- docs/ARCHITECTURE.md (Android MVVM, one package per feature, lint, coverage) and AGENTS.md project notes (no secrets logged, stay inside `touches`, fictitious test data)
- Server contract: `server/.../core/auth/domain/DeviceTokenService.kt` and `infra/AuthDeviceRepository.kt` (refresh rotation, reuse of a retired token revokes the device)
- Earlier evidence: reviews/and-login/review.md (first review), fixes.md, tests.md

Git range:
- Branch: main
- HEAD: 69121421df27b3b3c77876a114b7f3fc99336ab3
- Base/start: 6604ff34778f12ee5d7292feab42f1490c54c8aa (`git merge-base HEAD origin/main`)
- Commits reviewed: 9 (43e71518, c906989b, e4a54e83, e53cb4b3, 56787a8d, a0173fad, 9c5a8905, f2003169, 69121421)

Summary: 0 P0, 0 P1, 0 P2, 3 P3

## Disposition of the earlier findings

- **CR-1 (P2, logout cancelled before the tokens are cleared): fixed.** `AuthRepository.logout()` (AuthRepository.kt:160-176) runs in `withContext(NonCancellable)`. The revocation is limited by `withTimeoutOrNull(REVOKE_TIMEOUT_MS)`. `session.logout(USER)` and the cache clearing run in `finally`. `SessionViewModel.logout()` (SessionViewModel.kt:30-33) ignores taps while a logout job is active. The regression test `AuthRepositoryTest` "a logout started from the app still forgets the tokens when its screen goes away during the revocation" cancels `viewModelScope` during a slow DELETE and checks that the tokens are cleared. `SessionViewModelTest` covers the ignored repeated taps.
- **CR-2 (P2, real Keystore round trip never run): partly addressed. Device-level evidence is still open.** `KeystoreTokenCipherTest` now runs the production `KeystoreTokenCipher` against a stand-in JCA provider registered as "AndroidKeyStore". The test covers the key spec, the IV/tag layout, the AAD, tamper, lost-key and replaced-key handling, and a store restart. The real hardware-backed Keystore still runs on no device or emulator, and tests.md says so. None of the three criteria has recorded evidence yet (`tasks.py verify and-login` shows all unticked). The parent should record criterion 2's device-level Keystore part as `--skip` with a reason, as the parent has already planned, and not claim it from the JVM suite. This is not repeated as a finding.
- **CR-3 (P3, Keystore work on the main thread): fixed for login, initial decrypt accepted.** `tokenStore.login` runs in `withContext(ioDispatcher)` (AuthRepository.kt:142-144), and a counting-dispatcher test covers it. The first decrypt in `PersistentTokenStore`'s initializer still runs on whichever thread injects the store first, normally Main at cold start. The finding offered that as an optional "consider", and the fixer left it unchanged because the fix would need changes outside the task's `touches`. That is acceptable.
- **CR-4 (P3, caches cleared twice): fixed for the normal paths.** `AuthRepository` is now the single owner. A user logout clears the caches inline. Other reasons arrive through the app-scope collector filtered to `!= USER` (AuthRepository.kt:100-105). The ViewModel observer was removed. One edge case still clears twice; see CR-1 below.

Also verified as correct in the final code:
- The request matches the server contract.
- 400, 401 and 429 are mapped. A numeric `Retry-After` is honoured with at least 1 s, otherwise the default is 30 s.
- The countdown blocks submit and appears on the disabled button.
- Password and code are cleared after success; the code is also cleared after 401 and 429.
- `LoginUiState.toString` and `AuthTokens.toString` redact secrets. The auth package has no logging.
- `allowBackup="false"`.
- Undecryptable or malformed stored records are deleted and count as logged out. A failed encrypt never writes plain text.
- Refresh rotation keeps the device id.
- Start-up routing follows `tokenStore.session`.
- `TokenStore` is bound to `PersistentTokenStore`.
- The removed shell-navigation assertion in `MainActivityLaunchTest` is still covered by `ui/CockpitShellNavigationTest` and `AppStartRoutingTest`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A user logout of an already revoked device clears the caches twice, concurrently**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/auth/AuthRepository.kt:103`, `android/app/src/main/java/ch/sonensei/cockpit/auth/AuthRepository.kt:165`, `android/app/src/main/java/ch/sonensei/cockpit/auth/AuthRepository.kt:173-174`
    - Category: Correctness
    - Problem: `authApi.revokeDevice` goes through the main client, which has the `TokenAuthenticator` (NetworkModule.kt `authApi(retrofit)`). Suppose the device was already revoked, for example from the web device list (SPEC 4.1), or its refresh token has expired. The DELETE then gets a 401, and the authenticator refreshes. The refresh is rejected, so `session.logoutIfCurrent(..., REFRESH_REJECTED)` runs. The app-scope collector on `Dispatchers.Default` reacts with `clearCaches()`. The DELETE then returns its 401, and the `finally` block runs `session.logout(USER)` and `clearCaches()` again, possibly while the collector's pass is still running. That is the situation the CR-4 fix set out to remove ("a user logout clears the caches exactly once"), and no test covers this path.
    - Why it matters: Today the only cache is the cacheDir deletion, where this does no harm. Feature caches contributed later, such as Room clears, would run twice and at the same time.
    - Suggested fix: In `logout()`, skip the inline `clearCaches()` when the collector has already handled a logout for this session, or let only the collector clear the caches by removing the `USER` filter and the inline call. A user logout would then clear asynchronously, though, so check the existing "user logout clears the caches" tests. Add a test in which the DELETE returns 401 and the refresh is rejected, and assert the caches are cleared exactly once.

- [ ] **CR-2 | A failing SessionCache crashes the app and stops later cache clears**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/auth/AuthRepository.kt:102-104`, `android/app/src/main/java/ch/sonensei/cockpit/auth/AuthRepository.kt:178`, `android/app/src/main/java/ch/sonensei/cockpit/auth/AuthRepository.kt:70-72`
    - Category: Quality
    - Problem: `clearCaches()` calls each `SessionCache.clear()` in turn without handling exceptions, and the `SessionCache` contract does not say that `clear()` must not throw. If one cache throws:
        - the caches after it are not cleared;
        - in `logout()` the exception leaves the `finally` block and reaches `viewModelScope.launch` with no handler, so the app crashes;
        - in the app-scope collector (`SupervisorJob()` without a `CoroutineExceptionHandler`) the collector coroutine ends, so no later network-layer or server-change logout clears any cache until the process restarts, and the uncaught exception crashes the app.
    - Why it matters: This task created `SessionCache` as the extension point that feature tasks contribute to, for example Room `clearAllTables()`, which can throw. The tokens are cleared before the caches, so no session is lost. But one buggy contributor would make logout crash the app and silently disable cache clearing for the rest of the session.
    - Suggested fix: In `clearCaches()`, run each cache in its own try/catch (rethrow `CancellationException`), so every cache gets its turn and the collector survives. Alternatively, state in the `SessionCache` KDoc that `clear()` must not throw. Add a test with one throwing cache followed by a working one.

- [ ] **CR-3 | Rotated tokens are persisted with an asynchronous apply()**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/auth/PersistentTokenStore.kt:79-81`
    - Category: Correctness
    - Problem: `set()` writes with `prefs.edit { ... }`, which uses `apply()`, so the disk write happens asynchronously. `replace()` stores the refresh token the server has just rotated. The server keeps the previous token as retired, and when a retired token is presented again it deletes the device (`DeviceTokenService.kt:57-61`). Suppose the process is killed after `replace()` returns but before the queued write reaches the disk, for example by a low-memory kill while the app is in the background. On the next start, the store loads the old refresh token, the first refresh presents a retired token, and the server revokes the device. The user is then logged out and the device disappears from the device list.
    - Why it matters: The window is short, but the result is a forced re-login with TOTP. SPEC 4.1 expects the long-lived refresh token to keep the device logged in.
    - Suggested fix: Use `edit(commit = true)` for the writes that store tokens (`login`, `replace`). `login` already runs on `ioDispatcher`, and `replace` runs on an OkHttp thread, so neither runs on Main. `clear()` can stay on `apply()`.

## Review conclusion

No material issues found. The earlier P2 finding CR-1 and the P3 findings CR-3 and CR-4 are fixed. The remaining findings are P3 and do not block completion. Criterion 2's device-level Keystore evidence (earlier CR-2) must be recorded as skipped with a reason, not claimed. This review did not run Gradle; test results are taken from tests.md and fixes.md.
