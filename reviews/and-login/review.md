# Code Review

Date: 2026-10-20

Task: and-login - Android login and secure token storage

Scope: `git diff 4b1b038..HEAD` (committed changes only; uncommitted test-writer work ignored)

Requirements:
- tasks/and-login.md acceptance criteria and recorded assumptions
- SPEC.md section 3 (Android auth: refresh token in Keystore-backed storage, 15 min access token, device revocation, login rate limiting) and section 9.2 item 1
- docs/ARCHITECTURE.md and AGENTS.md project notes (no secret logging, feature folders, lint)
- Server contract: server/.../core/auth/api/DeviceTokenController.kt (POST /api/v1/auth/token, DELETE /api/v1/auth/devices/{id}, 400/401/429 + Retry-After seconds)

Git range:
- Branch: main
- HEAD: 61ecf6294fb6258502684fc570ad2017d05513a8
- Base/start: 4b1b038ccff2a595b236aee124d17b88a4f059d9
- Commits reviewed: 3 (a1de662, 860926e, 61ecf62)

Summary: 0 P0, 0 P1, 2 P2, 2 P3

Verified as correct: the request matches the server contract (exactly one of totp/recoveryCode, deviceName within 100 chars, 400/401/429 mapped, numeric Retry-After honoured with a 30 s fallback). The 429 countdown blocks submitting and is shown on the button. A double submit sends one request. Password and code are dropped from `LoginUiState.toString` and `AuthTokens.toString`. The new code adds no logging. AES-256-GCM uses a non-exportable Keystore key and a random IV, with AAD. Decrypt failures and malformed records count as logged out and the record is deleted. If encryption fails, nothing is written in plain text. Refresh rotation keeps the device id. Start-up routing follows `tokenStore.session`. The network layer's `TokenStore` binding now uses the persistent store.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Logout can be cancelled before the tokens are cleared, and gives no feedback during the up-to-5 s revoke**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/auth/AuthRepository.kt:124`, `android/app/src/main/java/ch/sonensei/cockpit/auth/SessionViewModel.kt:35`
    - Category: Correctness
    - Problem: `SessionViewModel.logout()` runs `repository.logout()` in `viewModelScope`. `logout()` first waits for `authApi.revokeDevice` (up to `REVOKE_TIMEOUT_MS` = 5 s on a slow or offline server) and only then calls `session.logout(LogoutReason.USER)` and `clearCaches()`. If the ViewModel is cleared during that wait, `CancellationException` is rethrown on purpose (line 129), so `session.logout` never runs. That happens when the user taps Log out and then leaves the app with Back, or the activity finishes. The tokens stay stored and the next start opens the shell, logged in. During the wait the shell stays fully usable with no progress indicator. Each extra tap starts another parallel logout and another revoke request.
    - Why it matters: Criterion 2 says "logout clears tokens and caches". A logout the user started can silently not happen, and the device stays logged in on a phone the user believes is logged out.
    - Suggested fix: Make the local part of the logout impossible to skip. Run the logout in an application-scoped or `NonCancellable` context, or capture the device id and clear the tokens and caches first, then revoke best-effort with the captured credentials. Also ignore repeated taps, or show a "logging out" state, while a logout runs. Add a test that cancels the scope during the revoke and checks that the tokens are cleared.

- [ ] **CR-2 | The real Keystore cipher's encrypt/decrypt round trip is never run by any test**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/auth/TokenCipher.kt:27`
    - Category: Tests
    - Problem: Robolectric has no `AndroidKeyStore` provider, so `KeystoreTokenCipher` always takes the fail-closed path in unit tests (`PersistentTokenStoreTest` "fails closed where there is no Android Keystore"; `AuthRepositoryTest:56` and the Hilt routing tests therefore keep tokens in memory only). The persistence tests use a fake cipher. Nothing runs the Keystore code that the app depends on: key generation, the GCM IV/tag layout (`[ivLen][iv][ct+tag]`), the AAD, and decrypt with `GCMParameterSpec(TAG_BITS, encrypted, 1, ivLength)`. There is no `androidTest` source set.
    - Why it matters: Criterion 2 ("Tokens stored encrypted (Keystore-backed)") has no evidence for the production cipher. A mistake in it would not crash. It would quietly fall back to keeping tokens in memory only (the login lost on every restart), and the tests would still pass.
    - Suggested fix: Verify the round trip on an emulator or device, for example with a small instrumented test or the SPEC section 11 manual step "log in, restart, still logged in", and record that as evidence. If that is not possible here, record criterion 2's Keystore part with `verify --skip` and the reason, rather than citing the Robolectric suite.

## P3 Low

- [ ] **CR-3 | Keystore and disk work runs on the main thread**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/auth/PersistentTokenStore.kt:42`, `android/app/src/main/java/ch/sonensei/cockpit/auth/AuthRepository.kt:110`
    - Category: Quality
    - Problem: `PersistentTokenStore` decrypts the stored record in its initializer (`MutableStateFlow(load())`), which runs when `hiltViewModel()` creates `SessionViewModel` during the first composition on the main thread. `AuthRepository.login` is called from `viewModelScope` (Main) and calls `tokenStore.login`, so `KeystoreTokenCipher.encrypt` (Keystore binder IPC) also runs on Main.
    - Why it matters: Keystore calls can take tens to hundreds of milliseconds on some devices, which adds jank at cold start and on sign-in.
    - Suggested fix: Wrap the `tokenStore.login(...)` call in `withContext(Dispatchers.IO)`. Consider warming the store off the main thread, for example in `Application.onCreate` on a background dispatcher, or accept the cost and document it.

- [ ] **CR-4 | A user logout clears the caches twice, concurrently**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/auth/AuthRepository.kt:135`, `android/app/src/main/java/ch/sonensei/cockpit/auth/SessionViewModel.kt:31`
    - Category: Quality
    - Problem: `AuthRepository.logout()` calls `clearCaches()` after `session.logout`. `SessionViewModel` also collects `loggedIn` and calls `repository.clearCaches()` on every switch to logged out. A user logout therefore runs every `SessionCache` twice, possibly at the same time: two `deleteRecursively` passes over `cacheDir` on IO. Cache clearing also has two owners. The ViewModel observer covers only logouts while that ViewModel is alive, despite the assumption "It runs on any logout".
    - Why it matters: Today this is harmless for the cache directory. Feature caches added later, such as Room clears, will be invoked twice and concurrently, and the split ownership makes the guarantee hard to reason about.
    - Suggested fix: Choose one owner. For example, make a singleton react to `tokenStore.session` becoming null, and drop the explicit call in `AuthRepository.logout`, or the reverse.

## Review conclusion

Material issues found; fix before completing the task. CR-1 and CR-2 should be addressed: fix CR-1, and verify the Keystore path in CR-2 on a device or record it as skipped.
