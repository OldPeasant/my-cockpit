# Test Report

Date: 2026-10-22

Task: and-passkeys - Android passkeys (passkey login via Credential Manager, registration from settings)

Scope: task-owned Android changes, originally `git diff 870413dd0..b1daad478 -- android`. During this run the parent rebased the task onto newer main: the task commits are now 44102c9ab (implementation), 7f4a25e8a (review report), ee040f481 (fixes CR-2..CR-5); b5c6af888 only adds `tasks/deploy-assetlinks.md`. Measured HEAD ee040f481310ade44832ee10e17988774ebffcf3, task base 769979261226c2bc9014c405f0d641e5ed5b3862 (parent of 44102c9ab). `git patch-id` of the production diff (auth + settings) is identical for 870413dd0..b1daad478 and 769979261..ee040f481 (d7551006015f), so the rebase did not change task code. The and-review commits that show up in `870413dd0..HEAD` belong to another task and are excluded.

Requirements:
- `tasks/and-passkeys.md` acceptance criteria, plus the recorded assumptions (settings only list and add passkeys; assetlinks moved to `deploy-assetlinks`)
- `reviews/and-passkeys/review.md` findings CR-2..CR-5 and `reviews/and-passkeys/fixes.md`
- SPEC.md §4.1 (Android login: password+TOTP or passkey via Credential Manager), `docs/ARCHITECTURE.md` "Coverage", AGENTS.md
- parent instructions: check coverage of Credential Manager result handling, Compose UI tests for the login passkey button and the settings passkey section, and the fixes (name cap 100, 400 -> PasskeyFailed, stale load cancellation)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/auth/PasskeyViewModelsTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/InAppPasskeyTest.kt`

The earlier test-writer additions (already committed in ee040f481: `PasskeyRepositoryTest`, `PasskeyScreensTest`, `PasskeyViewModelsTest`, `InAppPasskeyTest`) were reviewed and left as they are. No existing test was weakened, skipped or removed.

## Tests added or updated

Count: 2 added (1 existing test helper changed: `InAppPasskeyTest`'s fake server now serves a configurable passkey list, by default the same single passkey as before)

- `PasskeyViewModelsTest` `a passkey added after the list failed to load is listed, and the retry stays offered`: initial `list()` fails (null), then a registration succeeds. The new passkey is listed and confirmed, `loadFailed` stays true because older passkeys are still unknown, and no extra load starts when none was running (covers the `stale == false` path of the CR-4 fix).
- `InAppPasskeyTest` `with many passkeys the settings scroll, so log out stays reachable`: CR-5 regression test in the real app (Hilt graph, real `CockpitRoot`/`SettingsScreen`, Robolectric default screen). The fake server lists 30 passkeys. After navigating to Settings, "Log out" is initially not displayed, and `performScrollTo()` brings it on screen. Before the fix the column had no scroll parent, so this would fail. That claim is reasoned, not run: a mutation run would need a production edit, which is outside this helper's permissions.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Login with passkey: Credential Manager get request carries the server's options; passkey response -> success; non-passkey credential -> failure | `PasskeyCredentialsTest`: `authentication asks for a passkey with the server's options and returns its response`, `a credential other than a passkey is a failure` | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'` | Pass |
| Credential Manager exceptions on login (cancel, interrupted, DOM NotAllowed/other, NoCredential, provider configuration, unsupported, unknown) | `PasskeyCredentialsTest` `authentication exceptions map to outcomes` | same | Pass |
| Registration: create request carries the server's options; passkey response -> success; other response -> failure; every exception type (cancel, interrupted, DOM NotAllowed/InvalidState/other, no create option, provider configuration, unsupported, unknown) | `PasskeyCredentialsTest`: `registration creates a passkey ...`, `a response other than a passkey is a failed registration`, `registration exceptions map to outcomes` | same | Pass |
| Passkey login against the server: options passed through unchanged (unknown fields kept), token stored, every authenticator outcome, every token status (400 -> PasskeyFailed [CR-3], 401, 403, 429 with/without Retry-After, 500, unreadable), option failures, network loss, malformed bodies | `PasskeyRepositoryTest` (17 tests, e.g. `a passkey login passes the options unchanged and stores the tokens`, `token answers map to login results`, `authenticator outcomes other than success end the login without a token request`, `a 429 without a usable Retry-After waits the default time`, `losing the server after the passkey sheet is a network error and stores nothing`) | same | Pass |
| Registration against the server: code and name sent and trimmed, Bearer token, option errors (400/403 wrong code, 429, 500, 204, network), every authenticator outcome, finish errors (400, 409, 500, 204, network), list success/failure/malformed | `PasskeyRepositoryTest`: `registration sends the code, then the authenticator's response with the name`, `registration errors map to results`, `failing registration option requests never open the passkey sheet`, `an unusable authenticator answer fails the registration without a finish request`, `finishing a registration without a readable answer or connection reports it`, `malformed server answers are a failure, never a crash`, `the list is null ...` (2) | same | Pass |
| Login view model: passkey login needs only the server, every result maps to its error, cancel shows none, 429 countdown blocks both logins, double tap, error cleared on retry | `PasskeyViewModelsTest`: `a passkey login needs only the server`, `no passkey login without a server`, `passkey login results show their errors, a cancel shows none`, `too many passkey attempts block both logins until the wait is over`, `a second tap while a passkey login runs starts no second login`, `a new passkey attempt clears the previous error while it runs` | same | Pass |
| Settings view model: load, reload failure keeps the list, retry, add with name and code, every registration error, cancel keeps the code, double submit, guards, confirmation reset, code never printed | `PasskeyViewModelsTest` (11 tests incl. `adding a passkey registers it with name and code and lists it`, `a failed registration keeps the form open with an error and asks for a new code`) | same | Pass |
| CR-2: passkey name capped at 100 | `PasskeyViewModelsTest` `a passkey name is capped at the server's 100 characters` | same | Pass |
| CR-4: a stale list load never overwrites a newer state | `PasskeyViewModelsTest`: `a list that answers after a registration does not hide the new passkey`, `a new load replaces a running one, so an older answer never wins`, `a passkey added after the list failed to load is listed, and the retry stays offered` (new) | same | Pass |
| CR-5: settings scroll so that log out stays reachable | `InAppPasskeyTest` `with many passkeys the settings scroll, so log out stays reachable` (new) | same | Pass |
| Compose UI: login passkey button (needs only the server, disabled while submitting or during a wait, absent without action, every passkey error message) | `PasskeyScreensTest`: `the passkey button needs only the server and reports its errors`, `without a passkey action the login has no passkey button`, `the passkey button is disabled while a login runs or a wait is due`, `every passkey login error has its own message` | same | Pass |
| Compose UI: settings passkey section (list, last used, empty, loading, failed + retry, failed reload keeps list, add form, locked while creating, IME done, cancel, every registration error, confirmation, hidden outside a session) | `PasskeyScreensTest` (10 settings tests) | same | Pass |
| App wiring: settings of a session list passkeys from the server; login screen starts a passkey ceremony with only the server and waits for the system sheet | `InAppPasskeyTest`: `the settings of a session list the user's passkeys`, `the login screen starts a passkey login with only the server and waits for the passkey sheet` | same | Pass |
| Criterion 1 end to end on a device (real Credential Manager provider, real server with Digital Asset Links) | none | none | Not run: no device or emulator here, and Digital Asset Links is not served yet (CR-1, task `deploy-assetlinks`). Robolectric has no credential provider, so `InAppPasskeyTest` checks the flow only until the system sheet would open. |
| Lint | ktlint | `cd android && ./gradlew ktlintCheck ...` | Pass |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.InAppPasskeyTest' --tests 'ch.sonensei.cockpit.auth.PasskeyViewModelsTest'`

Result:

Pass (InAppPasskeyTest 3/3, PasskeyViewModelsTest 19/19)

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'` (2026-10-22 15:06:13 to 15:06:51 +02:00)

Result:

Pass: BUILD SUCCESSFUL, 185 auth tests, 0 failures, 0 errors, 0 skipped (from the JUnit XML results). Per class: AuthRepositoryTest 44, PasskeyViewModelsTest 19, PasskeyRepositoryTest 17, LoginViewModelTest 17, PersistentTokenStoreTest 17, PasskeyScreensTest 14, LoginScreenTest 13, AppStartRoutingTest 9, KeystoreTokenCipherTest 8, SessionViewModelTest 8, PasskeyCredentialsTest 6, InAppPasskeyTest 3, InAppLoginTest 3, LogoDrawableTest 3, LogoutButtonTest 2, SessionCacheTest 2.

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport` (runs the full `testDebugUnitTest`)

Result:

Pass: BUILD SUCCESSFUL, 1843 Android unit tests, 0 failures, 0 errors, 0 skipped.

## .taskcheck

Result:

Pass: `sh scripts/check.sh` (2026-10-22 15:09:32 to 15:11:19 +02:00) printed "all checks passed" for server, web and android.

## Bugs found

None.

## Coverage

Status: verified (fresh run on the snapshot described below).

Metric: whole-file coverage of task-changed production files (JaCoCo counters per source file, which includes code in these files that predates the task).
Changed-line coverage: measured separately. Method: added/modified lines from `git diff -U0 769979261..HEAD` for `auth/` and `settings/`, intersected with executable lines (`mi+ci > 0`) of the same JaCoCo `report.xml`. Result: 407/419 changed executable lines covered (97.1%), 247/262 branches on those lines (94.3%). Branch counts on changed lines are line-level JaCoCo branch counters, not a separate changed-branch analysis.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-passkeys. Original base 870413dd0d59738dece5252f2c47b2dca2d97018 and commits up to b1daad478 (from the parent). After the parent's rebase: base 769979261226c2bc9014c405f0d641e5ed5b3862, task commits 44102c9ab, 7f4a25e8a, ee040f481, measured HEAD ee040f481310ade44832ee10e17988774ebffcf3. The production patch-id is identical before and after the rebase. File scope comes from `git show --stat` of the task commits.
- Source/test snapshot identifier (including uncommitted inputs): HEAD ee040f481 plus two uncommitted test files, `InAppPasskeyTest.kt` sha256 fe1872938529a98b1a2c680bf1ab538e87a66d5d4f11f668113ff72c1f61f989 and `PasskeyViewModelsTest.kt` sha256 2ecfbff08b32be7ca9bb7ef1844dcd3852367fd210ff32b7e7f7bc1fdddb3e35. `reviews/and-passkeys/review.md` was also modified in the working tree by someone else; it is not a test input. No production file was modified.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/auth/{AuthRepository,CockpitRoot,LoginScreen,LoginViewModel,PasskeyCredentials,PasskeyRepository,PasskeySettings,PasskeySettingsViewModel}.kt`, `android/app/src/main/java/ch/sonensei/cockpit/settings/SettingsScreen.kt`. Excluded: `android/app/src/main/res/values/strings_passkeys.xml` (resource, not instrumented; the strings are asserted by the Compose tests), test files, `reviews/`, `tasks/`. No renamed or deleted files. All 9 scoped files are present in the report.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (Gradle 9.8.1, OpenJDK 21.0.12.1); `./gradlew createDebugUnitTestCoverageReport` in `android/`; full debug unit test suite.
- Run start/completion time and exit status: 2026-10-22 15:07:02 to 15:08:47 +02:00, exit 0, BUILD SUCCESSFUL (JaCoCo session `dell26-d75bf0c5`, start 1792674424097 ms = 15:07:04 +02:00).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 1101b4490185178f0be27434472c000bfe0aa7be1ccef331595a2285005356b4, session id `dell26-d75bf0c5`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report was written at 15:08 within the run, the JaCoCo session start falls inside the run window, and HEAD and the test file hashes were the same before and after.
- Final run after latest test additions and production fixes: yes. The run includes both new tests and the CR-2..CR-5 fixes (ee040f481).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Statements = JaCoCo INSTRUCTION counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `auth/AuthRepository.kt` | 587/613 (95.8%) | 44/44 (100.0%) | 21/21 (100.0%) | 81/84 (96.4%) |
| `auth/CockpitRoot.kt` | 256/275 (93.1%) | 11/20 (55.0%) | 10/10 (100.0%) | 14/14 (100.0%) |
| `auth/LoginScreen.kt` | 1624/1788 (90.8%) | 41/46 (89.1%) | 22/31 (71.0%) | 156/170 (91.8%) |
| `auth/LoginViewModel.kt` | 1016/1016 (100.0%) | 69/70 (98.6%) | 30/30 (100.0%) | 100/100 (100.0%) |
| `auth/PasskeyCredentials.kt` | 314/314 (100.0%) | 38/38 (100.0%) | 11/11 (100.0%) | 44/44 (100.0%) |
| `auth/PasskeyRepository.kt` | 753/765 (98.4%) | 67/67 (100.0%) | 21/23 (91.3%) | 106/108 (98.1%) |
| `auth/PasskeySettings.kt` | 1806/1957 (92.3%) | 44/49 (89.8%) | 30/39 (76.9%) | 140/149 (94.0%) |
| `auth/PasskeySettingsViewModel.kt` | 618/631 (97.9%) | 56/62 (90.3%) | 32/32 (100.0%) | 67/67 (100.0%) |
| `settings/SettingsScreen.kt` | 87/87 (100.0%) | 2/2 (100.0%) | 1/1 (100.0%) | 7/7 (100.0%) |
| **Aggregate (summed counters, all 9 files, none missing)** | 7061/7446 (94.8%) | 372/398 (93.5%) | 178/198 (89.9%) | 715/743 (96.2%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `auth/PasskeySettings.kt:253-262` | `@Preview` composable | Not material: preview only, not reachable in the app. |
| `auth/LoginScreen.kt:271-287` (only 285 is task-changed) | `@Preview` composable | Not material: preview only. |
| `auth/PasskeyRepository.kt:33-34` | `@Serializable` data class `PasskeyCeremony` header: generated serializer and data-class members (2 methods) | Not material: generated code. Deserialization is exercised by every login and registration test. |
| `auth/AuthRepository.kt:135,197,198` | Not changed by this task (password login code that predates it) | Out of task scope. All 6 changed lines are covered. |
| `auth/PasskeySettings.kt:67,73,76`, `auth/CockpitRoot.kt:22,25,35`, `auth/LoginScreen.kt:65,95` partial branches | Compose compiler-generated `$changed`/default-argument branches of composables | Not material: the behavior is covered both ways (section hidden or shown, passkey button present or absent, logout present or absent). |
| `auth/PasskeySettingsViewModel.kt:82` (5/6), `:97` (3/4), `:98` (1/2) | `closeForm` with no form; `loadJob` null on success. `loadJob` is assigned in `init`, so null is not reachable. | Not material: safe-call branches on a field that is never null after construction. Both the "load still running" and "load finished" paths of `:97` are covered (`a list that answers after a registration ...` and the new test). |
| `auth/PasskeySettingsViewModel.kt:121` (1/4) | `error()` arms for `Failed`/`Cancelled`/`Success`: `Cancelled` and `Success` never reach `error()` | Not material: these are exhaustive-`when` arms that can't be reached. `Failed` is covered. |
| `auth/LoginViewModel.kt:151` (3/4) | Countdown loop branch that predates the task | Out of task scope. |

## Unable to test

- Real passkey login and registration on a device (Credential Manager with a real provider, Google Password Manager or similar) against a real server. There is no emulator or device here, and Digital Asset Links (`/.well-known/assetlinks.json`) is not served yet (CR-1, follow-up task `deploy-assetlinks`), so the ceremony would fail on a real install anyway. Follow-up: verify on a device once `deploy-assetlinks` is done. Criterion 1 should be recorded as skipped or unit-level only, not as end-to-end verified.
- CR-5 regression test is not confirmed by a mutation run: proving that it fails without `verticalScroll` would need a temporary production change, which this helper may not make. The test asserts "Log out" is not displayed before scrolling and displayed after `performScrollTo()`, which can't pass without a scroll container.

## Summary

Tests added and all validation passed. The existing passkey tests cover every Credential Manager result and exception type, the server status mapping (including CR-3's 400 -> PasskeyFailed), the view models (including CR-2's name cap and CR-4's stale-load cancellation), and the Compose UI of the login passkey button and the settings passkey section. Two tests were added: the CR-4 path where registration succeeds after a failed load, and an in-app CR-5 scroll regression test. No production defects found. On a device the passkey flow remains unverified (see "Unable to test").
