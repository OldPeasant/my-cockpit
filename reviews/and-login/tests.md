# Test Report

Date: 2026-10-20

Task: and-login - Android login and secure token storage

Scope: task commits `44631d6fbcf1f00539ab523011c7b4639a6a6b2c..cff74469c234b1ae3151949e9ef4d579015b4338` on `main` (8 commits: 2b0a153a, 98234e65, 8f36f92d, 4284442a, c9f6d052, bb502220, 2c907007, cff74469), plus the uncommitted test changes of this session (listed below). Production paths: `android/app/src/main/java/ch/sonensei/cockpit/auth/*` and the hooks `MainActivity.kt`, `settings/SettingsScreen.kt`, `core/network/NetworkModule.kt`, `core/network/TokenStore.kt`.

Requirements:
- `tasks/and-login.md` acceptance criteria 1-3 and the recorded assumptions (Keystore AES-GCM, fail closed without a Keystore; logout revokes the device best effort; `SessionCache` multibinding plus cacheDir; a 429 without a numeric Retry-After waits 30 s)
- `AGENTS.md` (evidence rules, fictitious test data), `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`
- Earlier review evidence: `reviews/and-login/review.md`, `reviews/and-login/fixes.md` (CR-1, CR-3, CR-4)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/auth/InAppLoginTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/auth/LoginScreenTest.kt` (one test added)

Tests from earlier test-writer sessions (AppStartRoutingTest, AuthRepositoryTest, KeystoreTokenCipherTest, LoginScreenTest, LoginViewModelTest, LogoutButtonTest, PersistentTokenStoreTest, SessionCacheTest, SessionViewModelTest) were reviewed against the criteria and left unchanged.

## Tests added or updated

Count: 4

- `InAppLoginTest.signing in with a totp opens the shell and writes no plain token to disk`: the real activity, Hilt graph, OkHttp/Retrofit client and a MockWebServer. Filling in the form and tapping sign-in sends `POST /api/v1/auth/token` with the TOTP, stores the session including the device id, and routes to the shell. No plain token reaches SharedPreferences.
- `InAppLoginTest.a 429 from the server shows the error and the wait on a disabled button`: a 429 with `Retry-After: 42` shows the "Too many failed attempts" message and a disabled "Try again in ..." button in the running app. Nothing is stored.
- `InAppLoginTest.wrong credentials keep the login screen and store nothing`: a 401 shows an error, stays on the login screen and stores nothing.
- `LoginScreenTest.the toggle switches back from recovery code to authenticator code`: covers the recovery-code-to-TOTP direction of the toggle, which no test exercised before.

Gap that motivated `InAppLoginTest`: the earlier suites checked each layer on its own. The only in-app login test (`AppStartRoutingTest`) stopped at client-side URL validation, so the full chain from the login form through the Hilt `LoginViewModel` and real HTTP to the stored session and the shell was never exercised end to end.

## Requirement coverage

Commands: (A) `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'` -> 111 tests, 0 failures, 0 errors, 0 skipped. (B) `cd android && ./gradlew testDebugUnitTest --rerun createDebugUnitTestCoverageReport` -> 226 tests, 0 failures.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Login screen has server URL, username, password, TOTP / recovery code fields | `LoginScreenTest` (typing fills the form..., the toggle switches between..., the toggle switches back..., the password field is masked, done on the keyboard in the code field submits), `LoginViewModelTest` (submitting needs every field, blank fields..., the recovery code toggle..., the configured server prefills the form), `AuthRepositoryTest` (a recovery code is sent instead of a totp, the second factor is sent without surrounding spaces), `InAppLoginTest` (signing in with a totp...), `AppStartRoutingTest` (the login form in the app reports a server address the app refuses, with a recovery code) | A | Pass |
| AC1 Errors shown | `AuthRepositoryTest` (400/401/5xx/non-JSON/no body/unreadable date or id/network/invalid URL), `LoginViewModelTest` (each failure maps to its error, wrong credentials..., a new attempt clears the previous error..., an attempt after a failure that succeeds clears the error), `LoginScreenTest` (each error shows its own message, only a server address error marks the address field), `InAppLoginTest` (wrong credentials...), `AppStartRoutingTest` (server address refused) | A | Pass |
| AC1 429 backoff shown | `AuthRepositoryTest` (429 passes on the Retry-After seconds, 429 without a usable Retry-After waits the default, zero/negative/padded Retry-After), `LoginViewModelTest` (too many attempts blocks submitting and counts down the wait, another 429 after the wait..., too many attempts asks for a fresh code..., switching the second factor clears a form error but not the wait), `LoginScreenTest` (too many attempts shows the countdown on a disabled button, a one second wait reads in the singular), `InAppLoginTest` (a 429 from the server shows the error and the wait...) | A | Pass |
| AC2 Tokens stored encrypted (Keystore-backed) | `KeystoreTokenCipherTest` (AES-256 GCM key generated in the AndroidKeyStore once and reused, fresh IV, tamper/AAD/lost/replaced key rejection, store keeps a login across a restart and only writes ciphertext), `PersistentTokenStoreTest` (a login survives a restart, only ciphertext reaches the disk, fail closed without a usable key, corrupted/undecryptable records deleted), `InAppLoginTest` (no plain token on disk) | A | Pass. On the JVM the Keystore is a stand-in JCA provider registered as "AndroidKeyStore" (Robolectric has none). The cipher code itself runs unchanged, but real hardware-backed Keystore behavior is not run here. |
| AC2 App start routes to login or home | `AppStartRoutingTest` (without tokens the app starts on the login screen, with tokens the app starts on the shell, storing a login while the login screen shows switches to the shell, the network stack uses the persistent store), `SessionViewModelTest`, `InAppLoginTest` (login through the UI opens the shell) | A | Pass |
| AC2 Logout clears tokens and caches | `AuthRepositoryTest` (logout revokes this device, forgets the tokens and clears the caches; offline/refused/timeout/malformed id/no id; cancellation during revocation (CR-1); network-layer/server-change logout clears caches once; every contributed cache cleared), `AppStartRoutingTest` (logging out from settings returns to login and forgets tokens, clears the cached files; network-layer logout too), `SessionCacheTest`, `SessionViewModelTest` (taps while a logout runs...), `LogoutButtonTest` | A | Pass |
| AC3 `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'` passes, covering login success/failure, token persistence, logout, with Robolectric/Compose UI tests for screens | all of the above. Screens are covered by the Compose UI tests `LoginScreenTest`, `LogoutButtonTest`, `AppStartRoutingTest` and `InAppLoginTest` under Robolectric | A | Pass (111 tests) |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'` and `cd android && ./gradlew ktlintCheck` (ran `runKtlintCheckOverTestSourceSet` on the changed test sources)

Result:

Pass (111 tests, 0 failures, 0 errors, 0 skipped; ktlintCheck BUILD SUCCESSFUL)

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest --rerun createDebugUnitTestCoverageReport`

Result:

Pass (226 Android unit tests, 0 failures, 0 errors, 0 skipped)

## .taskcheck

Result:

Pass. `sh scripts/check.sh` exited 0 ("== all checks passed") on the final snapshot, 2026-10-20T20:51:53+02:00 to 20:56:05+02:00. That is about 4 min 12 s: above the 2 min warning, below the 15 min timeout. Most of the time goes to the web build and the Android lint/unit tests; the 4 added tests add only a few seconds. The web build printed a pre-existing initial bundle budget warning (513.90 kB vs 500 kB), unrelated to this task.

## Bugs found

None. No test exposed a production defect.

## Coverage

Status: verified (fresh run, exit 0, on the final test snapshot)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (the Android JaCoCo setup produces no changed-line report).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-login`; base `44631d6fbcf1f00539ab523011c7b4639a6a6b2c` (supplied by the parent); measured HEAD `cff74469c234b1ae3151949e9ef4d579015b4338`. Ownership comes from the parent-supplied commit range `44631d6f..HEAD`; all 8 commits are prefixed `and-login:`, and the diff matches the task's `touches` plus the named hooks.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `cff74469` plus uncommitted `android/app/src/test/java/ch/sonensei/cockpit/auth/InAppLoginTest.kt` (sha256 `db276ed6eb32cd74692e170df850725a5b6c75ef44ba6a9115dd12d9fbcc11ff`, untracked) and `android/app/src/test/java/ch/sonensei/cockpit/auth/LoginScreenTest.kt` (sha256 `1fad733f0e62c5c7732d58bd1555864e8b136b834cc96d37e485fcc9d24667e1`, modified). There are no uncommitted production changes.
- Exact included files: `auth/AuthModule.kt`, `auth/AuthRepository.kt`, `auth/CockpitRoot.kt`, `auth/LoginScreen.kt`, `auth/LoginViewModel.kt`, `auth/PersistentTokenStore.kt`, `auth/SessionViewModel.kt`, `auth/TokenCipher.kt` (the auth package, 8 files), plus the hooks `MainActivity.kt`, `settings/SettingsScreen.kt`, `core/network/NetworkModule.kt`, `core/network/TokenStore.kt` (all under `android/app/src/main/java/ch/sonensei/cockpit/`). Excluded: `android/app/src/main/res/values/strings_auth.xml` (resources, not executable code; exercised by the Compose tests that assert the texts) and the Hilt/Dagger-generated `*_Factory.java` / `*_HiltModules*.java` files that JaCoCo reports in the auth package (generated code). No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` and the Gradle `jacoco` plugin. The toolVersion is not pinned in the build files, so the AGP/Gradle default applies and the exact version was not recorded. Command `./gradlew testDebugUnitTest --rerun createDebugUnitTestCoverageReport`, working directory `android/`, selection: the full Android debug unit test suite. `--rerun` forces the test task to execute rather than reuse up-to-date results.
- Run start/completion time and exit status: 2026-10-20T20:50:50+02:00 to 20:51:42+02:00, exit 0, BUILD SUCCESSFUL.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (mtime 2026-10-20 20:51:42.73 +0200, sha256 `6f6356d709aebd562e6b11950204cb0464da7591398fd6d7efa88c36bad33b48`); HTML `android/app/build/reports/coverage/test/debug/index.html`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report mtime equals the run's completion time. The test file hashes and `git status` were identical before and after the run.
- Final run after latest test additions and production fixes: yes. It ran after the last test edit and on code that already includes fixes CR-1/3/4 (c9f6d052); no production code changed afterwards.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. "Statements" are JaCoCo INSTRUCTION counters.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `auth/AuthModule.kt` | 33/39 (84.6%) | 1/2 (50.0%) | 3/4 (75.0%) | 3/4 (75.0%) |
| `auth/AuthRepository.kt` | 558/584 (95.5%) | 43/44 (97.7%) | 21/21 (100.0%) | 72/75 (96.0%) |
| `auth/CockpitRoot.kt` | 219/232 (94.4%) | 9/14 (64.3%) | 9/9 (100.0%) | 12/12 (100.0%) |
| `auth/LoginScreen.kt` | 1426/1573 (90.7%) | 32/37 (86.5%) | 20/28 (71.4%) | 133/146 (91.1%) |
| `auth/LoginViewModel.kt` | 776/776 (100.0%) | 45/46 (97.8%) | 27/27 (100.0%) | 78/78 (100.0%) |
| `auth/PersistentTokenStore.kt` | 269/283 (95.1%) | 20/20 (100.0%) | 17/18 (94.4%) | 44/47 (93.6%) |
| `auth/SessionViewModel.kt` | 74/74 (100.0%) | 8/8 (100.0%) | 4/4 (100.0%) | 10/10 (100.0%) |
| `auth/TokenCipher.kt` | 201/206 (97.6%) | 12/12 (100.0%) | 8/9 (88.9%) | 42/45 (93.3%) |
| **auth package (8 files, summed)** | **3556/3767 (94.4%)** | **170/183 (92.9%)** | **109/120 (90.8%)** | **394/417 (94.5%)** |
| `MainActivity.kt` | 43/43 (100.0%) | N/A (0 branches) | 5/5 (100.0%) | 5/5 (100.0%) |
| `settings/SettingsScreen.kt` | 65/65 (100.0%) | 2/2 (100.0%) | 1/1 (100.0%) | 6/6 (100.0%) |
| `core/network/NetworkModule.kt` | 171/174 (98.3%) | 4/4 (100.0%) | 15/16 (93.8%) | 28/29 (96.6%) |
| `core/network/TokenStore.kt` | 43/43 (100.0%) | 2/2 (100.0%) | 9/9 (100.0%) | 14/14 (100.0%) |
| **All 12 scoped files (summed)** | **3878/4092 (94.8%)** | **178/191 (93.2%)** | **139/151 (92.1%)** | **447/471 (94.9%)** |

The aggregates sum the provider counters. Excluded from them: `strings_auth.xml` (resource) and the generated Hilt/Dagger Java classes. No scoped file was left unmeasured. `NetworkModule.kt` and `TokenStore.kt` are whole-file numbers: they include pre-existing code from `and-api-client`, while the task changed only a binding comment line and a KDoc line in them.

Before this session's additions, an equivalent fresh run (20:45:07 to 20:45:49, report sha256 `67ec32384d98efd468c4fd876b51bdb419b4fa0372bc12ea7a862e77a83e8579`) measured the auth package at 168/183 branches. `LoginScreen.kt` went from 31/37 to 32/37 branches with the new toggle test. `AuthModule.kt` statements read 35/39 in that run and 33/39 in the final one. Its cache-clearing lambda runs on `Dispatchers.IO` from the network-layer logout path, so how much of it gets recorded varies with timing between runs. Behavior is asserted either way, by `SessionCacheTest` and the `AppStartRoutingTest` cache tests.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `auth/LoginScreen.kt:228-247` (`LoginScreenPreview`) | none (IDE preview only) | Justified gap: preview code is not app behavior |
| `auth/LoginScreen.kt:55` (4/8), `:79` (1/2); `auth/CockpitRoot.kt:21` (4/8), `:32` (1/2) | Compose compiler-generated default-argument/skip branches of `LoginRoute`, `LoginScreen`, `CockpitRoot`, `LogoutButton` | Justified gap: generated recomposition branches. The composables run in `AppStartRoutingTest`, `InAppLoginTest`, `LoginScreenTest` and `LogoutButtonTest` |
| `auth/LoginScreen.kt:156` (toggle back to TOTP) | AC1 TOTP / recovery code switching in both directions | Covered now by `LoginScreenTest.the toggle switches back from recovery code to authenticator code` |
| `auth/AuthRepository.kt:104` | Closing line of the app-scope `loggedOut` collector, which never completes while the app lives | Justified gap: not reachable in normal operation. The collector's behavior is tested (network-layer/server-change logout clears caches once) |
| `auth/AuthRepository.kt:166-167` (`catch (e: CancellationException) { throw e }`) | Rethrowing cancellation inside `NonCancellable`. `withTimeoutOrNull` absorbs its own timeout, so this catch cannot be reached in practice | Justified gap: defensive code. Timeout and screen-cancellation paths are tested (`logout gives up on a server that does not answer...`, `a logout started from the app still forgets the tokens when its screen goes away...`) |
| `auth/AuthRepository.kt:182` (1/8) | `Build.MANUFACTURER`/`MODEL` being null in `deviceName()` | Justified gap: blank, `unknown`, partial and too-long names are tested. Robolectric's `ShadowBuild` cannot set null |
| `auth/LoginViewModel.kt:123` (1/4) | At the end of the countdown, an error other than TOO_MANY_ATTEMPTS is kept | Justified gap: while the wait runs, submitting is blocked and edits keep TOO_MANY_ATTEMPTS, so no other error can be present when it reaches 0 |
| `auth/PersistentTokenStore.kt:94-95` (`catch SerializationException`) | Undecodable record. `SerializationException` extends `IllegalArgumentException` and is already caught at line 91 | Justified gap: unreachable duplicate catch. The behavior is tested (`a corrupted record counts as logged out`, `a decrypted record that is not a token record counts as logged out and is deleted`) |
| `auth/PersistentTokenStore.kt:109` | kotlinx.serialization-generated code of the private `Record` class | Justified gap: generated |
| `auth/TokenCipher.kt:80-82` (`catch IOException`) | `KeyStore.load` failing with an I/O error, which must return null instead of crashing | Justified gap: the stand-in provider cannot raise it without new test infrastructure. The same fail-closed contract is tested for `GeneralSecurityException`/`ProviderException` paths (`the Keystore cipher fails closed where there is no Android Keystore`, lost/replaced key, tampering) |
| `auth/AuthModule.kt:16` (abstract `AuthBindings` constructor), `:39` (1/2, `cacheDir.listFiles()` returning null) | Hilt module class is never instantiated; cache dir missing | Justified gap: a generated/abstract constructor, plus a null-safe call. Empty and non-empty cache dirs are tested in `SessionCacheTest` |
| `core/network/NetworkModule.kt:81` | Pre-existing `serverConfigDataStore` declaration line (not task code) | Out of task scope (the task changed only lines 84-85) |

## Unable to test

- The real hardware-backed Android Keystore (key generation inside the TEE/StrongBox, key invalidation by the OS) cannot run on the JVM. `KeystoreTokenCipherTest` runs the production cipher against an in-memory JCA provider registered as "AndroidKeyStore". An instrumented test on a device or emulator would be the follow-up if the parent wants device-level evidence. This is an optional follow-up; the criteria name Robolectric/Compose unit tests.
- In the Hilt-graph tests (`AppStartRoutingTest`, `InAppLoginTest`) the store fails closed to memory-only because Robolectric has no Keystore. Those tests therefore check routing and "no plaintext on disk", not on-disk persistence. Persistence across a restart with the Keystore cipher is covered separately by `KeystoreTokenCipherTest.the token store with the Keystore cipher keeps a login across a restart and only writes ciphertext` and `PersistentTokenStoreTest`.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. Four tests were added: an end-to-end in-app login against a fake server (success opens the shell, 429 backoff shown, 401 stays on the login screen) and the recovery-to-TOTP toggle direction. The focused auth suite (111), the full Android unit suite (226), ktlintCheck and `.taskcheck` all pass.
