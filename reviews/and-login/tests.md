# Test Report

Date: 2026-10-20

Task: and-login - Android login and secure token storage

Scope: task commits `9deb2abcbc462523ca6f2b1252c8a1a52744c16d..7b6fd27af928aafb7aeb57a1f56b851a357c8345` on `main` (11 commits: 43cb7794, 833b3757, d6ddcb1a, 562fff8c, f5bb2d6e, 534a9130, fca3d3f4, 87dc794a, b90238d0, 4c6e1dda, 7b6fd27a), plus this session's uncommitted test change (listed below). This is the final pass on the code after the round-2 fixes (7b6fd27a: `AuthRepository.kt` clears caches once per logout and tolerates failing caches; `PersistentTokenStore.kt` commits token writes synchronously). Production paths: `android/app/src/main/java/ch/sonensei/cockpit/auth/*` and the hooks `MainActivity.kt`, `settings/SettingsScreen.kt`, `core/network/NetworkModule.kt`, `core/network/TokenStore.kt`.

Requirements:
- `tasks/and-login.md` acceptance criteria 1-3 and the recorded assumptions (Keystore AES-GCM, fail closed without a Keystore; logout revokes the device best effort; `SessionCache` multibinding plus cacheDir; a 429 without a numeric Retry-After waits 30 s)
- `AGENTS.md` (evidence rules, fictitious test data), `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`
- Review evidence: `reviews/and-login/review.md` (round 2), `reviews/and-login/fixes.md` (CR-1..3 of round 2)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/auth/AuthRepositoryTest.kt` (one test added)

## Tests added or updated

Count: 1

The round-2 fix commit already added tests for the three new behaviors. They were reviewed against the fixes and kept unchanged:
- `AuthRepositoryTest.a user logout of a device the server already revoked clears the caches exactly once`: the revocation runs through the real OkHttp client with `TokenAuthenticator`. The 401 triggers a refresh that is rejected, so the network layer logs out first; the caches are cleared exactly once and the tokens are gone.
- `AuthRepositoryTest.a failing cache neither stops the others nor the logout`: a throwing cache followed by a working one (insertion-ordered set). The working one is still cleared and the tokens are gone.
- `AuthRepositoryTest.a failing cache does not stop later logouts by the network layer from clearing the caches`: the app-scope collector survives a throwing cache across two network-layer logouts.
- `PersistentTokenStoreTest.logins and rotated tokens are written to disk synchronously`: a recording `SharedPreferences.Editor` shows `commit` (not `apply`) for a login and a rotation, and a new store reads the rotated tokens back.

Added in this session:
- `AuthRepositoryTest.a user logout whose revocation needed a token refresh still clears tokens and caches exactly once`: the revocation gets a 401, the refresh succeeds with rotated tokens and the retried revocation (with the new bearer token, same device path) succeeds. The tokens changed but were not cleared by the network layer, so the logout must forget them and clear the caches itself, exactly once. Gap that motivated it: the new `loggedOutMeanwhile` check had tests for "logged out by the network layer" and "nothing changed", but none for "tokens changed but still present", which a check like "tokens differ from before" would get wrong.

## Requirement coverage

Commands: (A) `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'` -> BUILD SUCCESSFUL, 116 tests, 0 failures, 0 errors, 0 skipped. (B) `cd android && ./gradlew testDebugUnitTest --rerun createDebugUnitTestCoverageReport` -> BUILD SUCCESSFUL, 231 tests, 0 failures, 0 errors, 0 skipped.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Login screen has server URL, username, password, TOTP / recovery code fields | `LoginScreenTest` (typing fills the form..., the toggle switches between..., the toggle switches back..., the password field is masked, done on the keyboard in the code field submits), `LoginViewModelTest` (submitting needs every field, blank fields..., the recovery code toggle..., the configured server prefills the form), `AuthRepositoryTest` (a recovery code is sent instead of a totp, the second factor is sent without surrounding spaces), `InAppLoginTest` (signing in with a totp...), `AppStartRoutingTest` (the login form in the app reports a server address the app refuses, with a recovery code) | A | Pass |
| AC1 Errors shown | `AuthRepositoryTest` (400/401/5xx/non-JSON/no body/unreadable date or id/network/invalid URL), `LoginViewModelTest` (each failure maps to its error, wrong credentials..., a new attempt clears the previous error..., an attempt after a failure that succeeds clears the error), `LoginScreenTest` (each error shows its own message, only a server address error marks the address field), `InAppLoginTest` (wrong credentials...), `AppStartRoutingTest` (server address refused) | A | Pass |
| AC1 429 backoff shown | `AuthRepositoryTest` (429 passes on the Retry-After seconds, 429 without a usable Retry-After waits the default, zero/negative/padded Retry-After), `LoginViewModelTest` (too many attempts blocks submitting and counts down the wait, another 429 after the wait..., too many attempts asks for a fresh code..., switching the second factor clears a form error but not the wait), `LoginScreenTest` (too many attempts shows the countdown on a disabled button, a one second wait reads in the singular), `InAppLoginTest` (a 429 from the server shows the error and the wait...) | A | Pass |
| AC2 Tokens stored encrypted (Keystore-backed) | `KeystoreTokenCipherTest` (AES-256 GCM key generated in the AndroidKeyStore once and reused, fresh IV, tamper/AAD/lost/replaced key rejection, store keeps a login across a restart and only writes ciphertext), `PersistentTokenStoreTest` (a login survives a restart, only ciphertext reaches the disk, logins and rotated tokens are written to disk synchronously, fail closed without a usable key, corrupted/undecryptable records deleted), `InAppLoginTest` (no plain token on disk) | A | Pass on the JVM. The device Keystore behavior cannot be verified without a device or emulator: on the JVM the Keystore is a stand-in JCA provider registered as "AndroidKeyStore" (Robolectric has none). The production cipher code runs unchanged against it, but hardware-backed key generation and OS key invalidation are not run here. |
| AC2 App start routes to login or home | `AppStartRoutingTest` (without tokens the app starts on the login screen, with tokens the app starts on the shell, storing a login while the login screen shows switches to the shell, the network stack uses the persistent store), `SessionViewModelTest`, `InAppLoginTest` (login through the UI opens the shell) | A | Pass |
| AC2 Logout clears tokens and caches | `AuthRepositoryTest` (logout revokes this device, forgets the tokens and clears the caches; offline/refused/timeout/malformed id/no id; cancellation during revocation; network-layer/server-change logout clears caches once; a user logout clears the caches exactly once; already revoked device (401 + rejected refresh) clears the caches exactly once; revocation needing a token refresh clears tokens and caches exactly once; a failing cache neither stops the others nor the logout; a failing cache does not stop later network-layer logouts; every contributed cache cleared), `AppStartRoutingTest` (logging out from settings returns to login and forgets tokens, clears the cached files; network-layer logout too), `SessionCacheTest`, `SessionViewModelTest`, `LogoutButtonTest` | A | Pass |
| AC3 `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'` passes, covering login success/failure, token persistence, logout, with Robolectric/Compose UI tests for screens | all of the above. Screens are covered by the Compose UI tests `LoginScreenTest`, `LogoutButtonTest`, `AppStartRoutingTest` and `InAppLoginTest` under Robolectric | A | Pass (116 tests) |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'`

Result:

Pass (BUILD SUCCESSFUL; 116 tests in the auth package, 0 failures, 0 errors, 0 skipped, counted from `app/build/test-results/testDebugUnitTest/TEST-ch.sonensei.cockpit.auth.*.xml`; ktlintCheck passed on the changed test source)

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest --rerun createDebugUnitTestCoverageReport`

Result:

Pass (231 Android unit tests, 0 failures, 0 errors, 0 skipped)

## .taskcheck

Result:

Pass. `sh scripts/check.sh` printed "== all checks passed" and exited 0 on the final snapshot, 2026-10-20T21:19:29+02:00 to 21:21:12+02:00 (about 1 min 43 s, below the 2 min warning).

## Bugs found

None. No test exposed a production defect.

Observation for the parent (not a confirmed defect, no failing test added because the requirement is ambiguous): `AuthRepository.clearCaches()` (`AuthRepository.kt:190-191`) rethrows any `CancellationException` from a cache. Inside the `NonCancellable` logout the caller is not cancelled, so the only source is the cache itself, for example a `TimeoutCancellationException` from a `withTimeout` inside a future `SessionCache.clear()`. That would skip the remaining caches and make `logout()` throw after the tokens were already forgotten. The KDoc says a failing cache must not keep the others from being cleared. If a cache timing out counts as failing, rethrowing only when the current coroutine is cancelled (`currentCoroutineContext().ensureActive()`) would cover it. Severity if accepted: Low (today's only cache, cacheDir clearing, does not use timeouts). The parent decides whether this is in scope.

## Coverage

Status: verified (fresh run, exit 0, on the final test snapshot)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (the Android JaCoCo setup produces no changed-line report).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-login`; base `9deb2abcbc462523ca6f2b1252c8a1a52744c16d` (supplied by the parent); measured HEAD `7b6fd27af928aafb7aeb57a1f56b851a357c8345`. Ownership comes from the parent-supplied commit range `9deb2abc..HEAD`; all 11 commits are prefixed `and-login:`, and the diff (`git diff --name-only 9deb2abc..HEAD`) matches the task's `touches` plus the named hooks.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `7b6fd27a` plus uncommitted `android/app/src/test/java/ch/sonensei/cockpit/auth/AuthRepositoryTest.kt` (modified, sha256 `323237fa904e9ffdc0e15c6212b7f2438c11c7294ab2c82b83ef9a9e475c5738`). No uncommitted production changes.
- Exact included files: `auth/AuthModule.kt`, `auth/AuthRepository.kt`, `auth/CockpitRoot.kt`, `auth/LoginScreen.kt`, `auth/LoginViewModel.kt`, `auth/PersistentTokenStore.kt`, `auth/SessionViewModel.kt`, `auth/TokenCipher.kt` (the auth package, 8 files), plus the hooks `MainActivity.kt`, `settings/SettingsScreen.kt`, `core/network/NetworkModule.kt`, `core/network/TokenStore.kt` (all under `android/app/src/main/java/ch/sonensei/cockpit/`). Excluded: `android/app/src/main/res/values/strings_auth.xml` (resources, not executable code; its texts are asserted by the Compose tests) and the Hilt/Dagger-generated `*_Factory.java` / `*_HiltModules*.java` files that JaCoCo reports in the auth package (generated code). No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`. The toolVersion is not pinned in the build files, so the AGP default applies; the exact version was not recorded. Command `./gradlew testDebugUnitTest --rerun createDebugUnitTestCoverageReport`, working directory `android/`, selection: the full Android debug unit test suite. `--rerun` forces the test task to execute.
- Run start/completion time and exit status: 2026-10-20T21:18:05+02:00 to 21:18:53+02:00, BUILD SUCCESSFUL.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (mtime 2026-10-20 21:18:53.72 +0200, sha256 `9dcadd398d84762ccedc9656cfcc07917b9ea57efea3be538d0309adfda814e7`); HTML `android/app/build/reports/coverage/test/debug/index.html`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report mtime equals the run's completion time; the test file hash and `git status` were identical before and after the run.
- Final run after latest test additions and production fixes: yes. It ran after the last test edit, on code that includes the round-2 fixes (7b6fd27a); no production code changed afterwards.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. "Statements" are JaCoCo INSTRUCTION counters.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `auth/AuthModule.kt` | 33/39 (84.6%) | 1/2 (50.0%) | 3/4 (75.0%) | 3/4 (75.0%) |
| `auth/AuthRepository.kt` | 593/641 (92.5%) | 51/58 (87.9%) | 21/21 (100.0%) | 78/83 (94.0%) |
| `auth/CockpitRoot.kt` | 219/232 (94.4%) | 9/14 (64.3%) | 9/9 (100.0%) | 12/12 (100.0%) |
| `auth/LoginScreen.kt` | 1426/1573 (90.7%) | 32/37 (86.5%) | 20/28 (71.4%) | 133/146 (91.1%) |
| `auth/LoginViewModel.kt` | 776/776 (100.0%) | 45/46 (97.8%) | 27/27 (100.0%) | 78/78 (100.0%) |
| `auth/PersistentTokenStore.kt` | 277/291 (95.2%) | 22/22 (100.0%) | 17/18 (94.4%) | 44/47 (93.6%) |
| `auth/SessionViewModel.kt` | 74/74 (100.0%) | 8/8 (100.0%) | 4/4 (100.0%) | 10/10 (100.0%) |
| `auth/TokenCipher.kt` | 201/206 (97.6%) | 12/12 (100.0%) | 8/9 (88.9%) | 42/45 (93.3%) |
| **auth package (8 files, summed)** | **3599/3832 (93.9%)** | **180/199 (90.5%)** | **109/120 (90.8%)** | **400/425 (94.1%)** |
| `MainActivity.kt` | 43/43 (100.0%) | N/A (0 branches) | 5/5 (100.0%) | 5/5 (100.0%) |
| `settings/SettingsScreen.kt` | 65/65 (100.0%) | 2/2 (100.0%) | 1/1 (100.0%) | 6/6 (100.0%) |
| `core/network/NetworkModule.kt` | 171/174 (98.3%) | 4/4 (100.0%) | 15/16 (93.8%) | 28/29 (96.6%) |
| `core/network/TokenStore.kt` | 43/43 (100.0%) | 2/2 (100.0%) | 9/9 (100.0%) | 14/14 (100.0%) |
| **All 12 scoped files (summed)** | **3921/4157 (94.3%)** | **188/207 (90.8%)** | **139/151 (92.1%)** | **453/479 (94.6%)** |

The aggregates sum the provider counters. Excluded from them: `strings_auth.xml` (resource) and the generated Hilt/Dagger Java classes. No scoped file was left unmeasured. `NetworkModule.kt` and `TokenStore.kt` are whole-file numbers: they include pre-existing code from `and-api-client`, while the task changed only a binding comment line and a KDoc line in them.

Compared with the previous report (measured before the round-2 fixes), `AuthRepository.kt` went from 43/44 to 51/58 branches. The total grew because the compiler duplicates the new `finally` logic for the exceptional path (see the gap table); every branch of the normal path is covered. `PersistentTokenStore.kt` went from 20/20 to 22/22 branches: both sides of `commit = encrypted != null` run (commit for a login/rotation, apply for a removal).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `auth/AuthRepository.kt:180-183` (4/8 branches at 180, 2/4 at 182, plus instructions at 181/183) | The `finally` block's copy for an exception thrown by the `try` body. The body catches everything except `CancellationException`, which cannot occur under `NonCancellable` (`withTimeoutOrNull` absorbs its own timeout) | Justified gap: compiler-generated exceptional copy, unreachable in practice. The normal-path copy is fully covered, including both outcomes of `loggedOutMeanwhile` (`a user logout of a device the server already revoked...`, `a user logout whose revocation needed a token refresh...`, `a user logout clears the caches exactly once`, `logging out while logged out is harmless`) |
| `auth/AuthRepository.kt:190-191` (`catch (e: CancellationException) { throw e }` in `clearCaches`) | A cache throwing `CancellationException` | Not tested: the desired behavior is ambiguous; see the observation under "Bugs found". The ordinary failing-cache path (lines 192-195) is covered |
| `auth/AuthRepository.kt:170-171` (`catch (e: CancellationException) { throw e }` around the revocation) | Rethrowing cancellation inside `NonCancellable` | Justified gap: defensive code, unreachable in practice. Timeout and screen-cancellation paths are tested (`logout gives up on a server that does not answer...`, `a logout started from the app still forgets the tokens when its screen goes away...`) |
| `auth/AuthRepository.kt:107` | Closing line of the app-scope `loggedOut` collector, which never completes while the app lives | Justified gap: not reachable in normal operation. The collector's behavior is tested |
| `auth/AuthRepository.kt:200` (1/8) | `Build.MANUFACTURER`/`MODEL` being null in `deviceName()` | Justified gap: blank, `unknown`, partial and too-long names are tested. Robolectric's `ShadowBuild` cannot set null |
| `auth/LoginScreen.kt:231-247` (`LoginScreenPreview`) | none (IDE preview only) | Justified gap: preview code is not app behavior |
| `auth/LoginScreen.kt:55` (4/8), `:79` (1/2); `auth/CockpitRoot.kt:21` (4/8), `:32` (1/2) | Compose compiler-generated default-argument/skip branches of `LoginRoute`, `LoginScreen`, `CockpitRoot`, `LogoutButton` | Justified gap: generated recomposition branches. The composables run in `AppStartRoutingTest`, `InAppLoginTest`, `LoginScreenTest` and `LogoutButtonTest` |
| `auth/LoginViewModel.kt:123` (1/4) | At the end of the countdown, an error other than TOO_MANY_ATTEMPTS is kept | Justified gap: while the wait runs, submitting is blocked and edits keep TOO_MANY_ATTEMPTS, so no other error can be present when it reaches 0 |
| `auth/PersistentTokenStore.kt:96-97` (`catch SerializationException`) | Undecodable record. `SerializationException` extends `IllegalArgumentException` and is already caught at line 93 | Justified gap: unreachable duplicate catch. The behavior is tested (`a corrupted record counts as logged out`, `a decrypted record that is not a token record counts as logged out and is deleted`) |
| `auth/PersistentTokenStore.kt:107-111` | kotlinx.serialization-generated code of the private `Record` class | Justified gap: generated |
| `auth/TokenCipher.kt:80-82` (`catch IOException`), `:92` | `KeyStore.load` failing with an I/O error, which must return null instead of crashing | Justified gap: the stand-in provider cannot raise it without new test infrastructure. The same fail-closed contract is tested for `GeneralSecurityException`/`ProviderException` paths |
| `auth/AuthModule.kt:16` (abstract `AuthBindings` constructor), `:39-40` (1/2, `cacheDir.listFiles()` returning null) | Hilt module class is never instantiated; cache dir missing | Justified gap: a generated/abstract constructor, plus a null-safe call. Empty and non-empty cache dirs are tested in `SessionCacheTest` |
| `core/network/NetworkModule.kt:81` | Pre-existing `serverConfigDataStore` declaration line (not task code) | Out of task scope |

## Unable to test

- Criterion 2's device Keystore behavior (key generation inside the TEE/StrongBox, key invalidation by the OS) cannot be verified without a device or emulator. `KeystoreTokenCipherTest` runs the production cipher against an in-memory JCA provider registered as "AndroidKeyStore". Follow-up if the parent wants device-level evidence: an instrumented test on a device or emulator. Criterion 3 names Robolectric/Compose unit tests, which pass.
- In the Hilt-graph tests (`AppStartRoutingTest`, `InAppLoginTest`) the store fails closed to memory-only because Robolectric has no Keystore. Those tests check routing and "no plaintext on disk", not on-disk persistence. Persistence across a restart with the Keystore cipher is covered by `KeystoreTokenCipherTest` and `PersistentTokenStoreTest`.
- That a synchronous `commit` survives a process kill (the motivation for round-2 CR-3) cannot be shown on the JVM. The test asserts that `commit`, not `apply`, is used for token writes.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The round-2 fixes were already covered by tests (revoke 401 + rejected refresh clears caches once, a throwing cache followed by a working one, synchronous commits); one test was added for a revocation whose 401 is resolved by a successful refresh. The focused auth suite (116) with ktlintCheck, the full Android unit suite (231) and `.taskcheck` all pass. One Low, ambiguous observation about `CancellationException` from a cache is left for the parent to decide.
