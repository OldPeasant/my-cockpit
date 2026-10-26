# Test Report

Date: 2026-10-26

Task: unifiedpush-android-client - Android UnifiedPush client

Scope: commits 4acbc4be335ca1cfe8ee476142d291f3f912aa73..8cb57beba1635d80d50f267c9ec8432d1b6e725d (HEAD, branch main, clean working tree). Task ownership: the parent worker supplied the base and the production/test file list; `git log` over the range shows only this task's feat/fix/test/review commits plus board commits (claim, heartbeat, release) for this task.

Requirements:
- tasks/unifiedpush-android-client.md (acceptance criteria 1-3 and the recorded assumptions)
- AGENTS.md project notes
- docs/ARCHITECTURE.md ("Coverage": informative, no threshold)
- reviews/unifiedpush-android-client/review.md (round 3, 0 findings) and fixes.md (rounds 1-2)

## Test files changed

No test files were changed in this final pass. The task's test files (all added by this task, committed by HEAD):

- `android/app/src/test/java/ch/sonensei/cockpit/push/PushMessageHandlerTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/push/PushRegistrarTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/push/PushInAppTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/push/PushAndroidAdaptersTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/push/LibraryUnifiedPushConnectorTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/AuthRepositoryLogoutHookTest.kt`

## Tests added or updated

Count: 0 in this final pass. Task total: 82 tests (PushRegistrarTest 28, PushInAppTest 15, PushMessageHandlerTest 12, PushAndroidAdaptersTest 11, AuthRepositoryLogoutHookTest 9, LibraryUnifiedPushConnectorTest 7). The previous test-writer pass added the last two PushInAppTest tests (`a push that arrives after a user logout is ignored`, `a user logout without a push registration only revokes the device`), committed in 8cb57beba.

## Requirement coverage

Command F (focused): `android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*' --tests 'ch.sonensei.cockpit.auth.AuthRepositoryLogoutHookTest'` (run by the parent worker; the test-writer sub-agent cannot run Gradle here).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 connector library in libs.versions.toml and ARCHITECTURE.md stack table | Not a runtime behavior; checked by inspection (connector 3.3.5 in `android/gradle/libs.versions.toml`, stack table row in `docs/ARCHITECTURE.md`); the build compiling against it is shown by Command F | F | Pass (build) / inspected |
| AC1 registration on login (app start while logged in, and after each login) | PushRegistrarTest: `registers with the distributor at app start when logged in`, `registers again after a new login`, `an app start within the same login registers the stored instance again, not a new one`, `starting twice registers only once per login`, `the same login seen again does not register again`, `registering while logged out does nothing`; PushInAppTest: `the registrar of the app graph registers at start while logged in`, `a login through the login screen's repository registers with the distributor` | F | Pass |
| AC1 endpoint sent to `PUT /api/v1/push/unifiedpush/{instanceId}` | PushRegistrarTest: `a new endpoint is sent to the server with its WebPush keys`, `an endpoint without keys is sent without keys`, `an endpoint with only half of the WebPush keys is sent without keys`, `a server error on the endpoint is swallowed`, `an endpoint of another registration or after the logout is not sent`; PushInAppTest: `the push service sends a new endpoint with its keys to the server`, `the endpoint is sent to the server with the login's access token` | F | Pass |
| AC1 unregistered and deleted on logout | PushRegistrarTest: `logout deletes the endpoint on the server, then unregisters at the distributor`, `logout without a registration does nothing`, `an unreachable server does not stop the logout`, `the distributor dropping the registration deletes it on the server`, `the next login registers a new instance`, `a failing distributor unregistration still forgets the instance`, `a checked exception from the distributor unregistration still forgets the instance`, `cancelling the endpoint delete is not swallowed`, the four PUT/DELETE ordering tests and `endpoints waiting or arriving after the logout's delete timed out are dropped`; PushInAppTest: `a user logout deletes the endpoint on the server before the device is revoked, then unregisters`, `a user logout goes on when the server rejects the endpoint delete`, `a logout by the network layer unregisters locally without a server request`, `the push service deletes the endpoint when the distributor unregisters`, `a user logout without a push registration only revokes the device`; AuthRepositoryLogoutHookTest (all 9: hook order before revocation, once per logout, throwing/hanging/self-cancelling hooks do not stop the logout, non-user logouts skip hooks) | F | Pass |
| AC1 no distributor installed means periodic sync only | PushRegistrarTest: `no distributor installed means no registration, only the periodic sync`, `a failing library does not break the app start`, `a checked exception from the library does not escape the registration`; LibraryUnifiedPushConnectorTest: `without a distributor app nothing is selected, so the app does not register` plus the 5 distributor selection tests; PushInAppTest: `a failed registration changes nothing` | F | Pass |
| AC2 reminder push runs the reminder sync | PushMessageHandlerTest: `a reminder push runs the reminder sync`; PushAndroidAdaptersTest: `a reminder push enqueues one reminder sync that waits for the network`, `a second reminder push replaces the pending sync`; PushInAppTest: `the push service passes a decrypted reminder push to the reminder sync` | F | Pass |
| AC2 new-mail push shows a new-mail notification | PushMessageHandlerTest: `a new-mail push shows the new-mail notification`; PushAndroidAdaptersTest: `a new-mail notification is posted on channel mail, without mail contents, and opens the app`, `every new-mail push updates the one notification instead of adding another`, `without the notification permission no notification is shown`, `before Android 13 the notification needs no runtime permission`, the two dismiss tests; PushInAppTest: `the push service passes a decrypted new-mail push to the notification` | F | Pass |
| AC2 unknown or garbled messages are ignored | PushMessageHandlerTest: `unknown or garbled messages are ignored`, `fields the app does not know are ignored`, `a deeply nested message within the size limit is ignored without crashing the push service`, `bytes that are not UTF-8 are ignored`, `an oversized message is ignored`, `a message of exactly the maximum size is still read, one byte more is ignored`, `a message without a registration is ignored`, `a message the library could not decrypt is ignored`, `a message for another registration is ignored`, `a message after the logout is ignored`; PushInAppTest: `the push service ignores a garbled push`, `the push service ignores a push that was not decrypted`, `a push that arrives after a user logout is ignored` | F | Pass |
| AC3 `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*'` passes, covering message handling | all push.* tests (73) | F (a superset of the AC3 selection) | Pass |

## Focused test execution

Command:

`android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*' --tests 'ch.sonensei.cockpit.auth.AuthRepositoryLogoutHookTest'` (run by the parent worker at HEAD 8cb57beba)

Result:

Pass - BUILD SUCCESSFUL; AuthRepositoryLogoutHookTest 9, PushAndroidAdaptersTest 11, LibraryUnifiedPushConnectorTest 7, PushInAppTest 15, PushMessageHandlerTest 12, PushRegistrarTest 28 = 82 tests, 0 failures, 0 errors, 0 skipped.

## Full test execution

Command:

`android/gradlew -p android ktlintCheck createDebugUnitTestCoverageReport` (whole Android unit suite with coverage instrumentation)

Result:

Fail (environment) - java.lang.OutOfMemoryError: Java heap space in the Robolectric test JVM under JaCoCo instrumentation of the whole suite. No test assertion failure was reported; no output of this run is used below. The scoped run below (push.* and all auth.* tests: 267 tests) passed. A full uninstrumented Android suite is part of `.taskcheck`, which the parent worker runs.

Scoped broader run: `android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*' --tests 'ch.sonensei.cockpit.auth.*' createDebugUnitTestCoverageReport` -> Pass; test result XMLs (written 2026-10-26 21:00:57 +0100) show 22 suites, 267 tests (push 73, auth 194), 0 failures, 0 errors, 0 skipped.

## .taskcheck

Result:

Not run by the test-writer (sub-agents cannot run Gradle in this environment); left to the parent worker before `done`.

## Bugs found

None in this pass. The production bugs found by earlier passes (checked library exceptions escaping register/unregister; no distributor selected when several are installed without a link activity; a hook's own CancellationException aborting the logout) were fixed in 74727cdb4 and 809de6670, and their regression tests pass in Command F.

## Coverage

Status: verified (scoped run; the whole-suite instrumented run failed with OutOfMemoryError and is not used).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured line by line against the diff. By inspection, every uncovered line listed below is either outside the task's added lines or generated/defensive code.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: unifiedpush-android-client; base 4acbc4be335ca1cfe8ee476142d291f3f912aa73; HEAD 8cb57beba1635d80d50f267c9ec8432d1b6e725d; file scope supplied by the parent worker and matched against `git diff --stat` of the range.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 8cb57beba, working tree clean (`git status --short` empty). Test file SHA-256: PushRegistrarTest.kt 68feea47..., PushInAppTest.kt a98328d8..., PushMessageHandlerTest.kt d8233279..., PushAndroidAdaptersTest.kt 32fc44dd..., LibraryUnifiedPushConnectorTest.kt b1b2543f..., AuthRepositoryLogoutHookTest.kt 97c332b6....
- Exact included files: `push/PushRegistrar.kt`, `push/PushMessageHandler.kt`, `push/NewMailNotifier.kt`, `push/UnifiedPushConnector.kt`, `push/CockpitPushService.kt`, `auth/AuthRepository.kt`, `auth/AuthModule.kt`, `CockpitApp.kt` (all under `android/app/src/main/java/ch/sonensei/cockpit/`). Excluded: `AndroidManifest.xml`, `res/values*/strings_push.xml`, `res/drawable/ic_notification_mail.xml`, `build.gradle.kts`, `libs.versions.toml` (resources/configuration, no JaCoCo counters; the manifest service entry is exercised indirectly by the Robolectric tests that start CockpitPushService). No renamed or deleted files. Hilt/Dagger generated `*_Factory.java` files are not counted.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*' --tests 'ch.sonensei.cockpit.auth.*' createDebugUnitTestCoverageReport` from the repository root (run by the parent worker).
- Run start/completion time and exit status: completed 2026-10-26 21:00:59 +0100 (report mtime; test results 21:00:57), exit status success.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, SHA-256 c6f073f4201980981a2c563c0af5ac2b5162506dd20cdbd20e8d04ba0f105f5b.
- Evidence outputs are fresh and inputs stayed unchanged: the report was written after the HEAD commit (8cb57beba, 20:57:08 +0100), and the working tree is still clean.
- Final run after latest test additions and production fixes: yes (last production fix 54d45a508 and last test commit 8cb57beba both precede the run).

Values below use covered/total (percentage) from JaCoCo counters.

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `push/PushRegistrar.kt` | 442/442 (100%) | 34/34 (100%) | 16/16 (100%) | 60/60 (100%) |
| `push/PushMessageHandler.kt` | 159/174 (91.4%) | 20/20 (100%) | 9/12 (75.0%) | 33/35 (94.3%) |
| `push/NewMailNotifier.kt` | 149/150 (99.3%) | 6/6 (100%) | 6/6 (100%) | 40/41 (97.6%) |
| `push/UnifiedPushConnector.kt` | 114/126 (90.5%) | 10/10 (100%) | 7/8 (87.5%) | 19/20 (95.0%) |
| `push/CockpitPushService.kt` | 126/129 (97.7%) | 4/4 (100%) | 14/15 (93.3%) | 14/15 (93.3%) |
| `auth/AuthRepository.kt` | 696/731 (95.2%) | 44/44 (100%) | 24/24 (100%) | 99/102 (97.1%) |
| `auth/AuthModule.kt` | 33/39 (84.6%) | 1/2 (50.0%) | 3/4 (75.0%) | 3/4 (75.0%) |
| `CockpitApp.kt` | 20/28 (71.4%) | N/A (0/0) | 4/6 (66.7%) | 7/7 (100%) |
| Aggregate (summed counters, 8 files) | 1739/1819 (95.6%) | 119/120 (99.2%) | 83/91 (91.2%) | 275/284 (96.8%) |

The aggregate covers exactly the 8 files above; no scoped Kotlin file is missing from the report. JaCoCo has no separate statement counter; instructions are shown instead.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `push/PushMessageHandler.kt:26-27` | `@Serializable private data class PushPayload`: generated members (copy/toString/equals-style methods) that the parser never calls | Justified gap: generated code; parsing of the payload is fully covered (all 20 branches) |
| `push/UnifiedPushConnector.kt:47-48` (`LibraryUnifiedPushConnector.register`) | One-line pass-through to `UnifiedPush.register`, which sends a broadcast to a distributor app | Justified gap: needs a real distributor app; the registration logic around it is covered with a fake connector in PushRegistrarTest, and the library adapter's selection/unregister paths are covered in LibraryUnifiedPushConnectorTest |
| `push/NewMailNotifier.kt:66` (`catch SecurityException`) | Permission revoked between the check and `notify` | Justified gap: a race that cannot be made deterministic under Robolectric; the no-permission path is covered |
| `push/CockpitPushService.kt:59` | Constructor of the abstract Hilt `PushBindings` module class | Justified gap: never instantiated (Dagger uses the bindings statically) |
| `auth/AuthModule.kt:16`, branch at line 42 | Abstract module constructor; `listFiles()` returning null in the pre-existing cache-dir cache | Justified gap: generated/pre-existing code, not changed by this task (the task only added the `logoutHooks()` multibinding) |
| `auth/AuthRepository.kt:155, 226-227` | Pre-existing init subscription line and the revocation's `CancellationException` rethrow | Justified gap: pre-existing lines, not added by this task; the task's LogoutHook loop (lines 214-222) is fully covered, including its catch |
| `CockpitApp.kt` (instructions/methods) | Hilt-generated members | Justified gap: all 7 lines covered, including `pushRegistrar.start()` |

## Unable to test

- Registration against a real ntfy distributor and a real server push over the network: needs a device/emulator with a distributor app; not possible in unit tests. The library boundary is covered with fakes and Robolectric. Follow-up only if a later task requires on-device end-to-end tests.
- A full Android unit suite with coverage instrumentation: fails with OutOfMemoryError in the test JVM (environment/heap limit, not a test failure); the scoped coverage run above replaces it. The uninstrumented full suite runs in `.taskcheck`.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. No tests were added in this final pass: all three acceptance criteria are covered by passing tests (82 task tests in the focused run, 267 in the scoped push.* and auth.* run), and every remaining uncovered line is generated, defensive, pre-existing or a library pass-through.
