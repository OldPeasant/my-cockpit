# Test Report

Date: 2026-10-20

Task: and-login - Android login and secure token storage

Scope: task commits `7982562bfa14d1979d9b8b2c884ce23f4df04b57..30022fa6482dd2ab73ab8d13f4c8f9e7ea53999b` on `main` (15 commits, all prefixed `and-login:`, from 67ec5d03 to 30022fa6), plus this session's uncommitted test change (listed below). This is the final pass on the code after the round-3 fixes (f91b5465: `AuthRepository.logout()` always clears the caches, clears are serialized by a `Mutex`, and a `CancellationException` thrown by a cache itself counts as a failing cache; 30022fa6: test that a cancelled app-scope clear releases the lock). Production paths: `android/app/src/main/java/ch/sonensei/cockpit/auth/*` and the hooks `MainActivity.kt`, `settings/SettingsScreen.kt`, `core/network/NetworkModule.kt`, `core/network/TokenStore.kt`.

Requirements:
- `tasks/and-login.md` acceptance criteria 1-3 and the recorded assumptions (Keystore AES-GCM, fail closed without a Keystore; logout revokes the device best effort; `SessionCache` multibinding plus cacheDir; a 429 without a numeric Retry-After waits 30 s)
- `AGENTS.md` (evidence rules, fictitious test data), `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`
- Review evidence: `reviews/and-login/review.md` (round 3), `reviews/and-login/fixes.md` (round-3 CR-1, CR-2)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/auth/AuthRepositoryTest.kt` (one test added, one import)

## Tests added or updated

Count: 1

The round-3 commits already added tests for the new behavior. They were checked against the fixes and kept unchanged:
- `a user logout of a device the server already revoked clears the caches at least once` (renamed from "exactly once": a double, serialized clear is now accepted by design)
- `a user logout clears the caches even when the network layer's logout event is dropped` (regression test for round-3 CR-1: the REFRESH_REJECTED event is replaced in the one-slot buffer by USER, and the logout still clears)
- `cache clears of a user logout and of the network layer never run concurrently` (the `Mutex`)
- `a cache that times out by itself neither stops the others nor the logout` (round-3 CR-2, `withTimeout` inside a cache)
- `a cache that throws a cancellation by itself does not stop later logouts by the network layer from clearing the caches`
- `cancelling the app scope stops its cache clear without blocking a later user logout from clearing every cache` (a real cancellation of the caller stops the clearing and releases the lock)

Added in this session:
- `AuthRepositoryTest.a user logout whose caller is cancelled during a cache clear still clears every cache`: the logout's caller (a job on an IO scope) is cancelled while the first cache is clearing; that cache then throws a `CancellationException`. Because the logout runs in `NonCancellable`, `ensureActive()` must not treat this as a cancellation of the caller, so the second cache is still cleared and the tokens are gone. Gap that motivated it: the existing tests covered a cache cancelling itself with an active caller and a cancelled app scope, but not the combination "user logout + caller cancelled during clearing", which is exactly what happens when the settings screen goes away during the logout. A version of `clearCaches` that checked the caller's `Job` instead of the current (non-cancellable) context would skip the remaining caches; the pre-fix code (`throw e`) would make this test fail too.

## Requirement coverage

Commands: (A) `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'` -> BUILD SUCCESSFUL, 122 tests in the auth package, 0 failures, 0 errors, 0 skipped. (B) `cd android && ./gradlew testDebugUnitTest --rerun createDebugUnitTestCoverageReport` -> BUILD SUCCESSFUL, 237 tests, 0 failures, 0 errors, 0 skipped.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Login screen has server URL, username, password, TOTP / recovery code fields | `LoginScreenTest` (typing fills the form..., the toggle switches between..., the toggle switches back..., the password field is masked, done on the keyboard in the code field submits), `LoginViewModelTest` (submitting needs every field, blank fields..., the recovery code toggle..., the configured server prefills the form), `AuthRepositoryTest` (a recovery code is sent instead of a totp, the second factor is sent without surrounding spaces), `InAppLoginTest` (signing in with a totp...), `AppStartRoutingTest` (the login form in the app reports a server address the app refuses, with a recovery code) | A | Pass |
| AC1 Errors shown | `AuthRepositoryTest` (400/401/5xx/non-JSON/no body/unreadable date or id/network/invalid URL), `LoginViewModelTest` (each failure maps to its error, wrong credentials..., a new attempt clears the previous error..., an attempt after a failure that succeeds clears the error), `LoginScreenTest` (each error shows its own message, only a server address error marks the address field), `InAppLoginTest` (wrong credentials...), `AppStartRoutingTest` (server address refused) | A | Pass |
| AC1 429 backoff shown | `AuthRepositoryTest` (429 passes on the Retry-After seconds, 429 without a usable Retry-After waits the default, zero/negative/padded Retry-After), `LoginViewModelTest` (too many attempts blocks submitting and counts down the wait, another 429 after the wait..., too many attempts asks for a fresh code..., switching the second factor clears a form error but not the wait), `LoginScreenTest` (too many attempts shows the countdown on a disabled button, a one second wait reads in the singular), `InAppLoginTest` (a 429 from the server shows the error and the wait...) | A | Pass |
| AC2 Tokens stored encrypted (Keystore-backed) | `KeystoreTokenCipherTest` (AES-256 GCM key generated in the AndroidKeyStore once and reused, fresh IV, tamper/AAD/lost/replaced key rejection, store keeps a login across a restart and only writes ciphertext), `PersistentTokenStoreTest` (a login survives a restart, only ciphertext reaches the disk, logins and rotated tokens are written to disk synchronously, fail closed without a usable key, corrupted/undecryptable records deleted), `InAppLoginTest` (no plain token on disk) | A | Pass on the JVM. The device Keystore cannot be verified without a device or emulator: on the JVM the Keystore is a stand-in JCA provider registered as "AndroidKeyStore" (Robolectric has none). The production cipher code runs unchanged against it, but hardware-backed key generation and OS key invalidation are not run here. |
| AC2 App start routes to login or home | `AppStartRoutingTest` (without tokens the app starts on the login screen, with tokens the app starts on the shell, storing a login while the login screen shows switches to the shell, the network stack uses the persistent store), `SessionViewModelTest`, `InAppLoginTest` (login through the UI opens the shell) | A | Pass |
| AC2 Logout clears tokens and caches | `AuthRepositoryTest` (logout revokes this device, forgets the tokens and clears the caches; offline/refused/timeout/malformed id/no id; cancellation during revocation; network-layer/server-change logout clears caches once; a user logout clears the caches exactly once; already revoked device clears the caches at least once; network layer's logout event dropped; revocation needing a token refresh; clears never run concurrently; failing / self-timing-out / self-cancelling caches; cancelled app scope releases the lock; caller cancelled during a cache clear still clears every cache; every contributed cache cleared), `AppStartRoutingTest` (logging out from settings returns to login and forgets tokens, clears the cached files; network-layer logout too), `SessionCacheTest`, `SessionViewModelTest`, `LogoutButtonTest` | A | Pass |
| AC3 `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'` passes, covering login success/failure, token persistence, logout, with Robolectric/Compose UI tests for screens | all of the above. Screens are covered by the Compose UI tests `LoginScreenTest`, `LogoutButtonTest`, `AppStartRoutingTest` and `InAppLoginTest` under Robolectric | A | Pass (122 tests) |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'`

Result:

Pass (BUILD SUCCESSFUL, 2026-10-20T21:47:57+02:00 to 21:49:03+02:00; 122 tests in the auth package, 0 failures, 0 errors, 0 skipped, counted from `app/build/test-results/testDebugUnitTest/TEST-ch.sonensei.cockpit.auth.*.xml`; ktlintCheck passed including the changed test source)

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest --rerun createDebugUnitTestCoverageReport`

Result:

Pass (237 Android unit tests, 0 failures, 0 errors, 0 skipped)

## .taskcheck

Result:

Pass. `sh scripts/check.sh` printed "== all checks passed" on the final snapshot, 2026-10-20T21:51:04+02:00 to 21:58:16+02:00 (about 7 min 12 s). This is above the 2 min warning of `tasks.py done` but below its 15 min timeout. The previous pass took 1 min 43 s on similar code; the difference was not investigated (the Gradle daemons and caches had just been used by the coverage run, and other agents share the machine). The parent should watch the duration when `done` runs it.

## Bugs found

None. No test exposed a production defect. The Low observation of the previous report (a `CancellationException` thrown by a cache itself stopped the clearing) was fixed in f91b5465 and is now covered by tests.

## Coverage

Status: verified (fresh run, exit 0, on the final test snapshot)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (the Android JaCoCo setup produces no changed-line report).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-login`; base `7982562bfa14d1979d9b8b2c884ce23f4df04b57` (supplied by the parent); measured HEAD `30022fa6482dd2ab73ab8d13f4c8f9e7ea53999b`. Ownership comes from the parent-supplied commit range `7982562b..HEAD`; all 15 commits are prefixed `and-login:`, and `git diff --name-only 7982562b..HEAD -- android/app/src/main` matches the task's `touches` plus the named hooks.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `30022fa6` plus uncommitted `android/app/src/test/java/ch/sonensei/cockpit/auth/AuthRepositoryTest.kt` (modified, sha256 `decd4aa02f3e5cb4581016b494398558c37def0a651edb204116ac1ef3699fa4`). No uncommitted production changes.
- Exact included files: `auth/AuthModule.kt`, `auth/AuthRepository.kt`, `auth/CockpitRoot.kt`, `auth/LoginScreen.kt`, `auth/LoginViewModel.kt`, `auth/PersistentTokenStore.kt`, `auth/SessionViewModel.kt`, `auth/TokenCipher.kt` (the auth package, 8 files), plus the hooks `MainActivity.kt`, `settings/SettingsScreen.kt`, `core/network/NetworkModule.kt`, `core/network/TokenStore.kt` (all under `android/app/src/main/java/ch/sonensei/cockpit/`). Excluded: `android/app/src/main/res/values/strings_auth.xml` (resources, not executable code; its texts are asserted by the Compose tests) and the Hilt/Dagger-generated `*_Factory.java` / `*_HiltModules*.java` files in the auth package (generated code). No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`. The toolVersion is not pinned in the build files, so the AGP default applies; the exact version was not recorded. Command `./gradlew testDebugUnitTest --rerun createDebugUnitTestCoverageReport`, working directory `android/`, selection: the full Android debug unit test suite. `--rerun` forces the test task to execute.
- Run start/completion time and exit status: 2026-10-20T21:49:22+02:00 to 21:50:38+02:00, BUILD SUCCESSFUL.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (mtime 2026-10-20 21:50:35.03 +0200, sha256 `6dd4954c090b53fd8bd098674bfc4b93131d80d2d7440f5f26bcca2f905c7ecc`); HTML `android/app/build/reports/coverage/test/debug/index.html`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report mtime falls inside the run; the test file hash, HEAD and `git status` were identical before and after the run.
- Final run after latest test additions and production fixes: yes. It ran after the last test edit, on code that includes the round-3 fixes (f91b5465); no production code changed afterwards.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. "Statements" are JaCoCo INSTRUCTION counters.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `auth/AuthModule.kt` | 33/39 (84.6%) | 1/2 (50.0%) | 3/4 (75.0%) | 3/4 (75.0%) |
| `auth/AuthRepository.kt` | 589/615 (95.8%) | 43/44 (97.7%) | 21/21 (100.0%) | 81/84 (96.4%) |
| `auth/CockpitRoot.kt` | 219/232 (94.4%) | 9/14 (64.3%) | 9/9 (100.0%) | 12/12 (100.0%) |
| `auth/LoginScreen.kt` | 1426/1573 (90.7%) | 32/37 (86.5%) | 20/28 (71.4%) | 133/146 (91.1%) |
| `auth/LoginViewModel.kt` | 776/776 (100.0%) | 45/46 (97.8%) | 27/27 (100.0%) | 78/78 (100.0%) |
| `auth/PersistentTokenStore.kt` | 277/291 (95.2%) | 22/22 (100.0%) | 17/18 (94.4%) | 44/47 (93.6%) |
| `auth/SessionViewModel.kt` | 74/74 (100.0%) | 8/8 (100.0%) | 4/4 (100.0%) | 10/10 (100.0%) |
| `auth/TokenCipher.kt` | 201/206 (97.6%) | 12/12 (100.0%) | 8/9 (88.9%) | 42/45 (93.3%) |
| **auth package (8 files, summed)** | **3595/3806 (94.5%)** | **172/185 (93.0%)** | **109/120 (90.8%)** | **403/426 (94.6%)** |
| `MainActivity.kt` | 43/43 (100.0%) | N/A (0 branches) | 5/5 (100.0%) | 5/5 (100.0%) |
| `settings/SettingsScreen.kt` | 65/65 (100.0%) | 2/2 (100.0%) | 1/1 (100.0%) | 6/6 (100.0%) |
| `core/network/NetworkModule.kt` | 171/174 (98.3%) | 4/4 (100.0%) | 15/16 (93.8%) | 28/29 (96.6%) |
| `core/network/TokenStore.kt` | 43/43 (100.0%) | 2/2 (100.0%) | 9/9 (100.0%) | 14/14 (100.0%) |
| **All 12 scoped files (summed)** | **3917/4131 (94.8%)** | **180/193 (93.3%)** | **139/151 (92.1%)** | **456/480 (95.0%)** |

The aggregates sum the provider counters. Excluded from them: `strings_auth.xml` (resource) and the generated Hilt/Dagger Java classes. No scoped file was left unmeasured. `NetworkModule.kt` and `TokenStore.kt` are whole-file numbers: they include pre-existing code from `and-api-client`, while the task changed only a binding comment line and a KDoc line in them.

Compared with the round-2 report, `AuthRepository.kt` went from 51/58 to 43/44 branches: the `loggedOutMeanwhile` condition was removed, so the compiler no longer duplicates branches into the `finally` block's exceptional copy. The new `clearCaches` lines (196-208: lock, cancellation handling with `ensureActive()`, ordinary failure) are fully covered.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `auth/AuthRepository.kt:190-192` (instructions only) | The `finally` block's copy for an exception thrown by the `try` body. The body catches everything except `CancellationException`, which cannot occur under `NonCancellable` (`withTimeoutOrNull` absorbs its own timeout) | Justified gap: compiler-generated exceptional copy, unreachable in practice. The normal-path copy (`session.logout(USER)` + `clearCaches()`) is fully covered |
| `auth/AuthRepository.kt:179-180` (`catch (e: CancellationException) { throw e }` around the revocation) | Rethrowing cancellation inside `NonCancellable` | Justified gap: defensive code, unreachable in practice. Timeout and screen-cancellation paths are tested (`logout gives up on a server that does not answer...`, `a logout started from the app still forgets the tokens when its screen goes away...`) |
| `auth/AuthRepository.kt:117` | Closing line of the app-scope `loggedOut` collector, which never completes while the app lives | Justified gap: not reachable in normal operation. The collector's behavior is tested, including its cancellation |
| `auth/AuthRepository.kt:213` (1/8) | `Build.MANUFACTURER`/`MODEL` being null in `deviceName()` | Justified gap: blank, `unknown`, partial and too-long names are tested. Robolectric's `ShadowBuild` cannot set null |
| `auth/LoginScreen.kt:231-247` (`LoginScreenPreview`) | none (IDE preview only) | Justified gap: preview code is not app behavior |
| `auth/LoginScreen.kt:55` (4/8), `:79` (1/2); `auth/CockpitRoot.kt:21` (4/8), `:32` (1/2) | Compose compiler-generated default-argument/skip branches of `LoginRoute`, `LoginScreen`, `CockpitRoot`, `LogoutButton` | Justified gap: generated recomposition branches. The composables run in `AppStartRoutingTest`, `InAppLoginTest`, `LoginScreenTest` and `LogoutButtonTest` |
| `auth/LoginViewModel.kt:123` (1/4) | At the end of the countdown, an error other than TOO_MANY_ATTEMPTS is kept | Justified gap: while the wait runs, submitting is blocked and edits keep TOO_MANY_ATTEMPTS, so no other error can be present when it reaches 0 |
| `auth/PersistentTokenStore.kt:96-97` (`catch SerializationException`) | Undecodable record. `SerializationException` extends `IllegalArgumentException` and is already caught at line 93 | Justified gap: unreachable duplicate catch. The behavior is tested (`a corrupted record counts as logged out`, `a decrypted record that is not a token record counts as logged out and is deleted`) |
| `auth/PersistentTokenStore.kt:107-111` | kotlinx.serialization-generated code of the private `Record` class | Justified gap: generated |
| `auth/TokenCipher.kt:80-82` (`catch IOException`), `:92` | `KeyStore.load` failing with an I/O error, which must return null instead of crashing | Justified gap: the stand-in provider cannot raise it without new test infrastructure. The same fail-closed contract is tested for `GeneralSecurityException`/`ProviderException` paths |
| `auth/AuthModule.kt:16` (abstract `AuthBindings` constructor), `:39-40` (1/2, `cacheDir.listFiles()` returning null) | Hilt module class is never instantiated; cache dir missing | Justified gap: a generated/abstract constructor, plus a null-safe call. Empty and non-empty cache dirs are tested in `SessionCacheTest` |
| `core/network/NetworkModule.kt:81` | Pre-existing `serverConfigDataStore` declaration line (not task code) | Out of task scope |

Line numbers outside `AuthRepository.kt` are from the round-2 report; those files did not change in round 3 and their counters are identical.

## Unable to test

- Criterion 2's device Keystore behavior (key generation inside the TEE/StrongBox, key invalidation by the OS) cannot be verified without a device or emulator. `KeystoreTokenCipherTest` runs the production cipher against an in-memory JCA provider registered as "AndroidKeyStore". Follow-up if the parent wants device-level evidence: an instrumented test on a device or emulator. Criterion 3 names Robolectric/Compose unit tests, which pass.
- In the Hilt-graph tests (`AppStartRoutingTest`, `InAppLoginTest`) the store fails closed to memory-only because Robolectric has no Keystore. Those tests check routing and "no plaintext on disk", not on-disk persistence. Persistence across a restart with the Keystore cipher is covered by `KeystoreTokenCipherTest` and `PersistentTokenStoreTest`.
- That a synchronous `commit` survives a process kill cannot be shown on the JVM. The test asserts that `commit`, not `apply`, is used for token writes.
- The dropped-event race (round-3 CR-1) is reproduced deterministically with a `StandardTestDispatcher` for the app scope; the real multi-threaded timing on a device is not exercised.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The round-3 fixes (always clear on a user logout, serialized clears, self-cancelling caches tolerated) were already covered by the committed tests; one test was added for a user logout whose caller is cancelled during a cache clear. The focused auth suite (122) with ktlintCheck, the full Android unit suite (237) and `.taskcheck` all pass; `.taskcheck` took about 7 min this time, above the 2 min warning.
