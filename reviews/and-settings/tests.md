# Test Report

Date: 2026-11-01

Task: and-settings - Android: Settings screen (server, notifications, capture queue, app version)

Scope: task commits e9ec42ace (implementation), 2380357ab (earlier test-writer tests), d732b66fd (fixes and tests for review findings CR-1..CR-5) on immutable base e2395de9ee7581d82e3e80de3a79a87841439c98, measured revision d732b66fd68f122b69e302abda6d5201377a5146. The local clone has since been rebased (HEAD 9772a073624bae92256fb68f6719e11dd3a7a42a, same three commits); `git diff d732b66fd 9772a0736 -- android` is empty and both revisions have the identical `android/` tree c8b4cc5a7f5acd598fae5df3431b507600617b59. No uncommitted changes under `android/` (only `reviews/and-settings/review.md` is modified, not a test input).

Requirements:
- `tasks/and-settings.md` acceptance criteria 1-7 and its recorded ASSUMPTION notes (user name from GET /api/v1/auth/me, "last sync result" meaning, distributor picker behaviour, device sections only inside a session)
- `AGENTS.md` project notes (fictitious test data, lint as contract)
- `reviews/and-settings/review.md` (re-review on the final code: 0 P0-P2, 1 P3) and `reviews/and-settings/fixes.md`

## Test files changed

No test files were changed in this pass. The existing task tests (written in 2380357ab and d732b66fd) were inspected against the criteria and the final code and found sufficient; see "Coverage" for the one remaining gap and why no test was added.

Task test files (all in the task diff):
- `android/app/src/test/java/ch/sonensei/cockpit/settings/SettingsViewModelTest.kt` (17 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/settings/SettingsScreenTest.kt` (21 tests, Robolectric Compose)
- `android/app/src/test/java/ch/sonensei/cockpit/settings/AndroidSettingsEnvironmentTest.kt` (11 tests, Robolectric, real UnifiedPush library)
- `android/app/src/test/java/ch/sonensei/cockpit/settings/SettingsServerChangeTest.kt` (3 tests, real ServerUrlRepository and AuthSession)
- `android/app/src/test/java/ch/sonensei/cockpit/settings/CaptureFailureTest.kt` (3 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/settings/SettingsStringsTest.kt` (2 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/auth/AppStartRoutingTest.kt`, `InAppPasskeyTest.kt` (only `performScrollTo()` added before existing assertions, because the new sections push passkeys/Log out below the fold; no assertion weakened)

## Tests added or updated

Count: 0 in this pass (57 settings tests exist from the earlier passes, all inspected).

## Requirement coverage

All results below come from the parent's fresh run on the measured revision: `android/gradlew -p android --max-workers=2 ktlintCheck lintDebug createDebugUnitTestCoverageReport` (includes testDebugUnitTest). JUnit XML in `android/app/build/test-results/testDebugUnitTest/` confirms 0 failures, 0 errors, 0 skipped for every settings class (timestamps 2026-11-01T16:42:36Z-16:42:40Z).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Server section shows the configured address | VM: `shows the server, the signed-in user, ...`; Screen: `shows server, user, permissions, push, queue and version`, `while the user loads it says so, without a server address it says none is set ...` | gradle command above | Pass |
| 1. Change it via `ServerUrlRepository.setServerUrl` | VM: `changing the server goes through setServerUrl, closes the form and reloads the user`, `an invalid address keeps the form open ...`, `a store that cannot be written ...`, `a second save while the first one runs is ignored`, `saving without an open form does nothing`; Screen: `the server dialog edits, saves and shows why an address is refused`, `the server dialog can be cancelled`, `while the server is saved the dialog buttons are disabled`, `a malformed address is explained ...`, `an address that could not be stored ...` | gradle command above | Pass |
| 1. Changing the origin logs out, as today | `SettingsServerChangeTest`: `changing to another server in settings logs out with SERVER_CHANGED ...`, `saving an address on the same origin keeps the session`, `an address the app refuses keeps the server and the session` (real repository, DataStore and AuthSession) | gradle command above | Pass |
| 1. Shows the signed-in user name | VM: `shows the server, the signed-in user ...`, `an unreachable server or an error answer leaves the user unknown, retry asks again`, `a successful answer without a user name leaves the user unknown`, `an older user request that fails last does not overwrite the newer answer`; Screen: `an unreachable server says so and Retry asks for the user again` | gradle command above | Pass |
| 2. Notifications allowed / exact alarms allowed, each with a button | Env: `permissions report the notifier and the alarm state ...`, `before Android 12 there is no exact alarm setting to open` (sdk 30); Screen: `shows server, user, permissions ...`, `no exact alarm button where Android has no such setting`, `every button calls its action` | gradle command above | Pass (button-to-callback level; the system Intent built by the route is not asserted, see Coverage gaps) |
| 2. UnifiedPush distributor state and picker | Env: `lists installed distributors by label ...`, `no distributor installed ...`, `installed distributors without a saved one ...`, `switching distributor drops the old registration first ...`, `a second switch waits until the first one has finished`, `choosing the distributor already in use only registers again ...`, `choosing a distributor when none was saved yet ...`; VM: `choosing a distributor switches to it ...`, `a failing distributor switch does not crash`, `opening the picker reads the installed distributors again ...`, `refresh reads permissions and distributors again`; Screen: `without a distributor the button leads to finding one`, `the distributor picker lists the installed ones ...`, `installed distributors without a chosen one offer the picker`, `the distributor picker marks the one in use ...` | gradle command above | Pass |
| 2. Existing app-side toggles shown, no new server settings | None exist (task ASSUMPTION); nothing to test beyond the absence of new settings, which the review confirmed | n/a | Not testable (no such toggles exist) |
| 3. Captures waiting, last sync result | VM: `shows ... queue ...`; Screen: `shows server, user, ...` (3 waiting, failed: Server error), `an empty queue reads as synced ...`, `one waiting capture without an error reads singular ...`, `an unknown capture failure is shown as stored`, `the last capture failure is in German ...`; `CaptureFailureTest` (all 3) | gradle command above | Pass |
| 3. "Sync now" enqueues the existing capture upload work | Env: `sync now enqueues the existing capture upload work` (verifies `CaptureSyncScheduler.schedule()`); VM: `sync now enqueues the upload work and confirms until the queue changes`, `a failure to enqueue is shown`, `sync now after a failed enqueue clears the failure and confirms`; Screen: `every button calls its action`, `a failed sync request is shown` | gradle command above | Pass |
| 4. App version name and code | Env: `the app version is read from the installed package`; VM and Screen: `Version 0.1.0 (42)` assertions | gradle command above | Pass |
| 4. Button opens {server}/download/android | VM: `the download link is below the server, also below a path prefix` (incl. null server); Screen: `every button calls its action` (callback receives `https://cockpit.example.org/download/android`), `... offers no download` without a server | gradle command above | Pass (URL and callback; the ACTION_VIEW Intent itself is not asserted, see Coverage gaps) |
| 5. Passkeys and logout stay; placeholder gone | Screen: `outside a session only the session-free parts show, the placeholder is gone`; `InAppPasskeyTest` and `AppStartRoutingTest` (passkey section and Log out reached through Settings in the full Hilt app) | gradle command above | Pass |
| 6. All new strings in English and German | `SettingsStringsTest` (same keys, non-blank, same placeholders, plural one/other with count); Screen: `the sections are in German on a German phone`, `the last capture failure is in German ...` | gradle command above | Pass |
| 7. Unit tests cover the criteria; `ktlintCheck testDebugUnitTest lintDebug` passes | All of the above | `android/gradlew -p android --max-workers=2 ktlintCheck lintDebug createDebugUnitTestCoverageReport` (run by the parent on d732b66fd) | Pass: BUILD SUCCESSFUL, testDebugUnitTest 2536 tests, 0 failures, 0 skipped |

## Focused test execution

Command:

`android/gradlew -p android --max-workers=2 ktlintCheck lintDebug createDebugUnitTestCoverageReport` (run by the parent; this sub-agent does not run Gradle in this environment). The settings classes are part of that run.

Result:

Pass. AndroidSettingsEnvironmentTest 11, CaptureFailureTest 3, SettingsScreenTest 21, SettingsServerChangeTest 3, SettingsStringsTest 2, SettingsViewModelTest 17: 57 tests, 0 failures, 0 errors, 0 skipped (verified in the JUnit XML results).

## Full test execution

Command:

Same command (testDebugUnitTest is the full Android unit suite, plus ktlintCheck and lintDebug).

Result:

Pass: 2536 tests, 0 failures, 0 skipped. The parent reports two earlier attempts died from machine overload (Gradle daemon disappeared / test executor could not connect) and one InAppLoginTest 429 Compose timeout appeared only in the overloaded run; the final run is clean.

## .taskcheck

Result:

Not run by this sub-agent (Gradle-based, run by the parent before `done`).

## Bugs found

None. No test fails on the final code.

## Coverage

Status: verified (fresh JaCoCo report from the parent's run on the measured revision).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. All four Kotlin files except `SettingsScreen.kt` are new in this task, so for them whole-file equals changed-line coverage; `SettingsScreen.kt` was mostly rewritten (451 changed lines).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-settings; base e2395de9ee7581d82e3e80de3a79a87841439c98; measured d732b66fd68f122b69e302abda6d5201377a5146 (identical `android/` tree as local HEAD 9772a073624bae92256fb68f6719e11dd3a7a42a); commits e9ec42ace, 2380357ab, d732b66fd supplied by the parent and confirmed by `git log e2395de9e..d732b66fd`; file scope from `git diff --name-only e2395de9e d732b66fd -- android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): git tree c8b4cc5a7f5acd598fae5df3431b507600617b59 for `android/`; no uncommitted or untracked files under `android/`.
- Exact included files; renamed/deleted/excluded files and reasons: included `settings/CaptureFailure.kt`, `settings/SettingsEnvironment.kt`, `settings/SettingsScreen.kt`, `settings/SettingsViewModel.kt` (package `ch.sonensei.cockpit.settings`). Excluded: `res/values/strings_settings.xml` and `res/values-de/strings_settings.xml` (resources, not instrumentable; covered by SettingsStringsTest). No renames or deletions. No scoped file missing from the report.
- Provider/version, command, working directory, and test selection: JaCoCo via the project's existing `createDebugUnitTestCoverageReport` task (Android Gradle plugin unit-test coverage); `android/gradlew -p android --max-workers=2 ktlintCheck lintDebug createDebugUnitTestCoverageReport`, run from the clone root by the parent; full debug unit suite.
- Run start/completion time and exit status: settings test suites ran 2026-11-01T16:42:36Z-16:42:40Z; report written 2026-11-01 17:42:51 +0100; exit BUILD SUCCESSFUL (per parent).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 9acd1cb4b678f6d5ddbd8d256783852528bd6c6febd9d7019908418dbd70988e. Parsed with `python3 -I .tmp/settings_cov.py` (provider counters only).
- Evidence outputs are fresh and inputs stayed unchanged: the report and JUnit results are newer than the last source/test write (all task files last written 2026-11-01 17:39:01 +0100 by the rebase that produced 9772a0736, content identical to d732b66fd); no input changed afterwards.
- Final run after latest test additions and production fixes: yes (d732b66fd contains the last fixes and tests; nothing was changed in this pass).

Values below use covered/total (percentage). "Statements" are JaCoCo instructions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/settings/SettingsViewModel.kt` | 963/989 (97.4%) | 34/40 (85.0%) | 41/41 (100.0%) | 114/120 (95.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/settings/SettingsEnvironment.kt` | 268/276 (97.1%) | 5/6 (83.3%) | 23/24 (95.8%) | 51/53 (96.2%) |
| `android/app/src/main/java/ch/sonensei/cockpit/settings/CaptureFailure.kt` | 214/283 (75.6%) | 24/29 (82.8%) | 10/10 (100.0%) | 30/31 (96.8%) |
| `android/app/src/main/java/ch/sonensei/cockpit/settings/SettingsScreen.kt` | 3794/3973 (95.5%) | 126/162 (77.8%) | 62/83 (74.7%) | 267/279 (95.7%) |
| Aggregate (summed counters, all 4 scoped Kotlin files; 2 string resource files excluded as non-instrumentable) | 5239/5521 (94.9%) | 189/237 (79.7%) | 136/158 (86.1%) | 462/483 (95.7%) |

No coverage threshold is defined for tasks in `docs/ARCHITECTURE.md` or `AGENTS.md` that these numbers fail; they are reported as measured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `SettingsScreen.kt:119-129, 133, 135` (route lambdas building the notification-settings, exact-alarm, distributor-website and download Intents) | Criteria 2 and 4: the buttons open the matching system settings page / the browser. Tests prove each button fires its callback with the right URL, but no test asserts the Intent action, extra (`EXTRA_APP_PACKAGE`), package Uri or that `startActivity` is called. A wrong action string would not be caught. | Gap left, not added in this pass: the code is a direct, reviewed mapping with low regression risk, the parent asked to avoid test changes that make the review stale, and this sub-agent cannot run Gradle to prove a new Robolectric route test (it would need the Hilt app harness plus `shadowOf(application).nextStartedActivity`, as in `contacts/ContactIntentsTest.kt`). Recommended follow-up if the parent wants it: one route-level test per button asserting the started Intent. |
| `SettingsScreen.kt:141-146` (`Context.open`, ActivityNotFoundException catch) | No app handles the Intent (no browser, settings page missing): must not crash. | Not covered, same reason as above; defensive and trivially correct by inspection. |
| `SettingsScreen.kt:375` (dialog dismiss is ignored while saving), `389` (IME Done saves) | Dialog cannot be dismissed by tapping outside while a save runs; keyboard Done saves. | Not covered; the visible Save/Cancel buttons and their disabled state while saving are tested. Low risk. |
| `SettingsScreen.kt:259` and branches at 102-134 (route with/without Hilt defaults), 254, 271, 291, 355, 441, 176 | Null-state branches (permissions/distributors/appVersion null, picker open without distributor state) and Compose-generated default-argument branches. | Non-material: the null states exist only before first emission; Compose/Kotlin synthetic branches. |
| `SettingsScreen.kt:150-162` (`SettingsActions` default no-op lambdas) | Defaults for previews/tests. | Non-material. |
| `SettingsViewModel.kt:154-155, 187-188, 203-204` (`catch (e: CancellationException) { throw e }`) | Cancellation is rethrown, not turned into an error state. | Non-material: standard coroutine idiom; the non-cancellation catch branches are tested. |
| `SettingsViewModel.kt:98-99` (one of four branches) | "Sync now" confirmation stays when the first queue emission arrives. | Covered in substance (initial emission and change both tested); remaining combination non-material. |
| `SettingsViewModel.kt:117, 141, 158, 167` (null-safe calls when form already closed / body null partially) | Form closed while a save completes. | Non-material; body-null and error responses are tested. |
| `SettingsEnvironment.kt:118` (versionName null), `125` (NameNotFoundException label fallback), `130` (Hilt module constructor) | Package without label falls back to its package name. | Low risk; Hilt module is generated glue. Not added. |
| `CaptureFailure.kt:65-72` (one branch each, line 72) | Composable mapping for Offline, NotLoggedIn, AttachmentMissing, Unexpected rendered in the UI; parser for all of them is tested in `CaptureFailureTest`, and ServerError/Rejected/Other are rendered in Screen tests in English and German. | Low risk: one `stringResource` call per case, the strings exist in both languages (SettingsStringsTest). Not added. |

## Unable to test

- Opening the real system settings pages, the browser and a real UnifiedPush distributor on a device: needs a device/emulator; not available here. Unit tests verify the callbacks and the environment's library calls instead. Follow-up only if a device check is wanted.
- Existing app-side toggles for reminders/new-mail notifications: none exist (task ASSUMPTION), so nothing to test.
- Gradle could not be run by this sub-agent; all execution results are the parent's fresh run, cross-checked against the JUnit XML and the JaCoCo report on disk.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. Every acceptance criterion has passing tests on the final revision (57 settings tests, full suite 2536/0 failures, ktlint and lint pass). One meaningful but low-risk gap remains, documented above: the route's system/browser Intents for the notification, exact-alarm, distributor-website and download buttons are not asserted at the Intent level (callbacks and URLs are). No test files were changed in this pass, so the existing review stays current.
