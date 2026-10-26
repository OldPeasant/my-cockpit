# Test Report

Date: 2026-10-26

Task: unifiedpush-android-client - Android UnifiedPush client

Scope: `git diff origin/main...HEAD` at HEAD ff93b836a0664bb6e0189fd60279e8cdb40e2aa3 (branch main, clean working tree); merge base with origin/main d62f8910ea2429be59725a6848d86c828aff40f9 (task claim commit 588e3ad89 is an ancestor). Task ownership: the parent worker supplied the scope; `git log 588e3ad89..HEAD -- android` lists only this task's feat/fix/test/build commits (cad462455 .. ff93b836a). The only code change since the previously reported revision 8cb57beba (and the quality evidence at 83603e8c2) is ff93b836a (rebased form of ef625c0f6): `android/app/build.gradle.kts` sets `maxHeapSize = "2g"` for all Test tasks. `git diff --quiet 8cb57beba HEAD -- android/app/src` confirms that all production and test sources are byte-identical to 8cb57beba.

Requirements:
- tasks/unifiedpush-android-client.md (acceptance criteria 1-3 and the recorded assumptions)
- AGENTS.md project notes
- docs/ARCHITECTURE.md ("Coverage": informative, no threshold)
- reviews/unifiedpush-android-client/review.md and fixes.md

## Test files changed

No test files were changed in this pass. The task's test files (added by this task, committed at HEAD, unchanged since 8cb57beba):

- `android/app/src/test/java/ch/sonensei/cockpit/push/PushMessageHandlerTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/push/PushRegistrarTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/push/PushInAppTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/push/PushAndroidAdaptersTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/push/LibraryUnifiedPushConnectorTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/AuthRepositoryLogoutHookTest.kt`

## Tests added or updated

Count: 0 in this pass. Task total: 82 tests (PushRegistrarTest 28, PushInAppTest 15, PushMessageHandlerTest 12, PushAndroidAdaptersTest 11, AuthRepositoryLogoutHookTest 9, LibraryUnifiedPushConnectorTest 7).

The heap change is build configuration for the test JVM, not app behavior: it needs no new test. Its effect (the full Robolectric suite no longer runs out of memory) is shown by the full-suite results below. Re-reading the criteria against the existing tests found no gap, so no test was added.

## Requirement coverage

Command F (focused, the parent worker's full-suite run at the final build configuration, see "Full test execution"): `cd android && ./gradlew testDebugUnitTest`, which includes the AC3 selection `--tests 'ch.sonensei.cockpit.push.*'` and `AuthRepositoryLogoutHookTest`. The test-writer sub-agent could not run Gradle itself (permission denied in this environment); results were read from the run's JUnit XML files.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 connector library in libs.versions.toml and ARCHITECTURE.md stack table | Not a runtime behavior; checked by inspection (connector 3.3.5 in `android/gradle/libs.versions.toml`, stack table row in `docs/ARCHITECTURE.md`); the build compiling against it is shown by Command F | F | Pass (build) / inspected |
| AC1 registration on login (app start while logged in, and after each login) | PushRegistrarTest: `registers with the distributor at app start when logged in`, `registers again after a new login`, `an app start within the same login registers the stored instance again, not a new one`, `starting twice registers only once per login`, `the same login seen again does not register again`, `registering while logged out does nothing`; PushInAppTest: `the registrar of the app graph registers at start while logged in`, `a login through the login screen's repository registers with the distributor` | F | Pass |
| AC1 endpoint sent to `PUT /api/v1/push/unifiedpush/{instanceId}` | PushRegistrarTest: `a new endpoint is sent to the server with its WebPush keys`, `an endpoint without keys is sent without keys`, `an endpoint with only half of the WebPush keys is sent without keys`, `a server error on the endpoint is swallowed`, `an endpoint of another registration or after the logout is not sent`; PushInAppTest: `the push service sends a new endpoint with its keys to the server`, `the endpoint is sent to the server with the login's access token` | F | Pass |
| AC1 unregistered and deleted on logout | PushRegistrarTest: `logout deletes the endpoint on the server, then unregisters at the distributor`, `logout without a registration does nothing`, `an unreachable server does not stop the logout`, `the distributor dropping the registration deletes it on the server`, `the next login registers a new instance`, `a failing distributor unregistration still forgets the instance`, `a checked exception from the distributor unregistration still forgets the instance`, `cancelling the endpoint delete is not swallowed`, the four PUT/DELETE ordering tests and `endpoints waiting or arriving after the logout's delete timed out are dropped`; PushInAppTest: `a user logout deletes the endpoint on the server before the device is revoked, then unregisters`, `a user logout goes on when the server rejects the endpoint delete`, `a logout by the network layer unregisters locally without a server request`, `the push service deletes the endpoint when the distributor unregisters`, `a user logout without a push registration only revokes the device`; AuthRepositoryLogoutHookTest (all 9) | F | Pass |
| AC1 no distributor installed means periodic sync only | PushRegistrarTest: `no distributor installed means no registration, only the periodic sync`, `a failing library does not break the app start`, `a checked exception from the library does not escape the registration`; LibraryUnifiedPushConnectorTest: `without a distributor app nothing is selected, so the app does not register` plus the 5 distributor selection tests; PushInAppTest: `a failed registration changes nothing` | F | Pass |
| AC2 reminder push runs the reminder sync | PushMessageHandlerTest: `a reminder push runs the reminder sync`; PushAndroidAdaptersTest: `a reminder push enqueues one reminder sync that waits for the network`, `a second reminder push replaces the pending sync`; PushInAppTest: `the push service passes a decrypted reminder push to the reminder sync` | F | Pass |
| AC2 new-mail push shows a new-mail notification | PushMessageHandlerTest: `a new-mail push shows the new-mail notification`; PushAndroidAdaptersTest: `a new-mail notification is posted on channel mail, without mail contents, and opens the app`, `every new-mail push updates the one notification instead of adding another`, `without the notification permission no notification is shown`, `before Android 13 the notification needs no runtime permission`, the two dismiss tests; PushInAppTest: `the push service passes a decrypted new-mail push to the notification` | F | Pass |
| AC2 unknown or garbled messages are ignored | PushMessageHandlerTest: `unknown or garbled messages are ignored`, `fields the app does not know are ignored`, `a deeply nested message within the size limit is ignored without crashing the push service`, `bytes that are not UTF-8 are ignored`, `an oversized message is ignored`, `a message of exactly the maximum size is still read, one byte more is ignored`, `a message without a registration is ignored`, `a message the library could not decrypt is ignored`, `a message for another registration is ignored`, `a message after the logout is ignored`; PushInAppTest: `the push service ignores a garbled push`, `the push service ignores a push that was not decrypted`, `a push that arrives after a user logout is ignored` | F | Pass |
| AC3 `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*'` passes, covering message handling | all push.* tests (73) | F (a superset of the AC3 selection). The exact AC3 command was not run by the test-writer (Gradle denied); the parent worker should run it once at HEAD when recording `verify 3` | Pass in F |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*'` - Not run by the test-writer: the sandbox denied every Gradle invocation (`./gradlew` and `android/gradlew -p android`). The push suites ran inside the parent worker's full-suite run below.

Result:

Pass (from the full-suite run's JUnit XML, `android/app/build/test-results/testDebugUnitTest/`): LibraryUnifiedPushConnectorTest 7, PushAndroidAdaptersTest 11, PushInAppTest 15, PushMessageHandlerTest 12, PushRegistrarTest 28 (73 push tests), AuthRepositoryLogoutHookTest 9; 0 failures, 0 errors, 0 skipped. Suite timestamps 2026-10-26T21:46:52Z to 21:48:35Z.

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest` (whole Android unit suite, with the debug build's JaCoCo instrumentation), run by the parent worker with `maxHeapSize = "2g"` before committing ff93b836a (22:49:08 +0100).

Result:

Pass - 203 result files, 2123 tests, 0 failures, 0 errors, 0 skipped, written 2026-10-26 22:48:49 +0100; this includes `ch.sonensei.cockpit.ui.theme.CockpitThemeTest`, which hit `OutOfMemoryError: Java heap space` with the 512 MB default in the earlier `.taskcheck`. A fresh JaCoCo execution file of this run exists (`android/app/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec`, 22:48:49 +0100), so the whole instrumented suite no longer runs out of memory either.

## .taskcheck

Result:

Not run by the test-writer (Gradle invocations are denied for this sub-agent); the parent worker runs `sh scripts/check.sh` before `done`.

## Bugs found

None. Production bugs found by earlier passes (checked library exceptions escaping register/unregister; no distributor selected when several are installed without a link activity; a hook's own CancellationException aborting the logout; a timed-out logout DELETE not dropping queued PUTs) were fixed in this task's fix commits, and their regression tests pass in Command F.

## Coverage

Status: partial - the numbers below were measured at 8cb57beba, whose production and test sources are byte-identical to HEAD ff93b836a; no coverage report was generated at HEAD itself (the test-writer cannot run Gradle here).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured line by line against the diff. By inspection, every uncovered line listed below is outside the task's added lines or is generated/defensive code.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: unifiedpush-android-client; merge base d62f8910e (origin/main), task commits cad462455..ff93b836a; file scope from `git diff --stat origin/main...HEAD`. Coverage measured at 8cb57beba (pre-rebase revision of the same code).
- Source/test snapshot identifier (including uncommitted inputs): HEAD ff93b836a, clean working tree. `git diff --quiet 8cb57beba HEAD -- android/app/src` succeeds (identical sources). Test file SHA-256 at HEAD equal those of the measured run: PushRegistrarTest.kt 68feea47..., PushInAppTest.kt a98328d8..., PushMessageHandlerTest.kt d8233279..., PushAndroidAdaptersTest.kt 32fc44dd..., LibraryUnifiedPushConnectorTest.kt b1b2543f..., AuthRepositoryLogoutHookTest.kt 97c332b6....
- Exact included files: `push/PushRegistrar.kt`, `push/PushMessageHandler.kt`, `push/NewMailNotifier.kt`, `push/UnifiedPushConnector.kt`, `push/CockpitPushService.kt`, `auth/AuthRepository.kt`, `auth/AuthModule.kt`, `CockpitApp.kt` (under `android/app/src/main/java/ch/sonensei/cockpit/`). Excluded: `AndroidManifest.xml`, `res/values*/strings_push.xml`, `res/drawable/ic_notification_mail.xml`, `build.gradle.kts` (including the new `maxHeapSize` line), `libs.versions.toml` (resources/configuration, no JaCoCo counters). No renamed or deleted files. Hilt/Dagger generated `*_Factory.java` files are not counted.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*' --tests 'ch.sonensei.cockpit.auth.*' createDebugUnitTestCoverageReport` from the repository root (run by the parent worker at 8cb57beba).
- Run start/completion time and exit status: completed 2026-10-26 21:00:59 +0100, exit status success (as recorded in the previous report).
- Generated report paths and report hashes/run identifier: previous run `android/app/build/reports/coverage/test/debug/report.xml`, SHA-256 c6f073f4201980981a2c563c0af5ac2b5162506dd20cdbd20e8d04ba0f105f5b (in the measuring worker's clone). The `report.xml` in this clone (20:02:10 +0100, SHA-256 592ae74c...) predates later fixes and is not used.
- Evidence outputs are fresh and inputs stayed unchanged: the sources are unchanged since the measured revision, and the heap size does not change instrumentation or which code runs. A report at HEAD itself was not regenerated.
- Final run after latest test additions and production fixes: yes for the code (no production or test change after 8cb57beba); no fresh report for the heap-only build change. To confirm at HEAD: `cd android && ./gradlew createDebugUnitTestCoverageReport` reuses the fresh full-suite `.exec` from 22:48:49.

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

The aggregate covers exactly the 8 files above; no scoped Kotlin file is missing. JaCoCo has no separate statement counter; instructions are shown instead.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `push/PushMessageHandler.kt:26-27` | `@Serializable private data class PushPayload`: generated members the parser never calls | Justified gap: generated code; payload parsing is fully covered (all 20 branches) |
| `push/UnifiedPushConnector.kt:47-48` (`LibraryUnifiedPushConnector.register`) | One-line pass-through to `UnifiedPush.register`, which sends a broadcast to a distributor app | Justified gap: needs a real distributor app; registration logic is covered with a fake connector in PushRegistrarTest, selection/unregister paths in LibraryUnifiedPushConnectorTest |
| `push/NewMailNotifier.kt:66` (`catch SecurityException`) | Permission revoked between the check and `notify` | Justified gap: a race that cannot be made deterministic under Robolectric; the no-permission path is covered |
| `push/CockpitPushService.kt:59` | Constructor of the abstract Hilt `PushBindings` module class | Justified gap: never instantiated |
| `auth/AuthModule.kt:16`, branch at line 42 | Abstract module constructor; `listFiles()` returning null in pre-existing cache-dir code | Justified gap: generated/pre-existing code; the task only added the `logoutHooks()` multibinding |
| `auth/AuthRepository.kt:155, 226-227` | Pre-existing init subscription line and the revocation's `CancellationException` rethrow | Justified gap: pre-existing lines; the task's LogoutHook loop is fully covered, including its catch |
| `CockpitApp.kt` (instructions/methods) | Hilt-generated members | Justified gap: all 7 lines covered, including `pushRegistrar.start()` |

## Unable to test

- Registration against a real ntfy distributor and a real server push over the network: needs a device/emulator with a distributor app; not possible in unit tests. The library boundary is covered with fakes and Robolectric. Follow-up only if a later task requires on-device end-to-end tests.
- Running Gradle (the AC3 command, coverage report, `.taskcheck`) from this sub-agent: denied by the sandbox. Results above come from the parent worker's runs; the parent should run the exact AC3 command at HEAD when recording `verify 3`.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. No tests were needed for the heap-only change in ff93b836a: all production and test sources are identical to the measured revision 8cb57beba, the 82 task tests (73 in push.*) pass, and the full Android unit suite (2123 tests) passes with `maxHeapSize = "2g"`, where it previously failed with OutOfMemoryError.
