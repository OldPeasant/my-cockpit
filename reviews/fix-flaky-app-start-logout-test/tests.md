# Test Report

Date: 2026-11-09

Task: fix-flaky-app-start-logout-test - Stabilize AppStartRoutingTest 'logging out from settings clears the cached files' (times out at AppStartRoutingTest.kt:167 under load, blocks .taskcheck)

Scope: task commit `a60ec23da7d56b3ead7bf55b39a85e8bc7d6491a` on top of base `efa76d6fb3ed2599a2db987214cad3713cd301c3` (`git merge-base HEAD origin/main`; the base is the task's own `assume` commit, preceded by the `claim` commit `f10cc6343`). HEAD = `a60ec23da7d56b3ead7bf55b39a85e8bc7d6491a`, branch `main`, working tree clean at the start. `git diff efa76d6fb..HEAD` changes exactly one file: `android/app/src/test/java/ch/sonensei/cockpit/auth/AppStartRoutingTest.kt` (+33/-2). No production file is changed.

Requirements:
- `tasks/fix-flaky-app-start-logout-test.md` acceptance criteria (added by the parent with a recorded assumption; the task had none before)
- `AGENTS.md` (Evidence rules, Android check command)
- Production code read to validate the helper: `auth/AuthRepository.kt`, `auth/SessionViewModel.kt`, `auth/AuthModule.kt`, `push/CockpitPushService.kt`, `push/PushRegistrar.kt`, `gtd/data/GtdDataModule.kt`, `gtd/data/GtdRefresh.kt`, `gtd/data/GtdSync.kt`, `reminders/RemindersModule.kt`, `reminders/ReminderSync.kt` and the other `SessionCache` providers

## Test files changed

- None by the test-writer. The task's own change (`android/app/src/test/java/ch/sonensei/cockpit/auth/AppStartRoutingTest.kt`, sha256 `55cd58d7b6b520c8c5cda5b8da4e10253a82bfd780703ccc7d48521f4f73c4ab`) was reviewed.

## Tests added or updated

Count: 0

Why no further test changes: the task is a test stabilization. Both cache-clearing waits in the file now use `awaitDeleted`, and review of the logout path found no other step in the two cache tests that the helper fails to drive (see the analysis below). The other waits in the file are outside the task's goal; see "Observations".

### Review of `awaitDeleted`

- Threading model: `SessionViewModel.logout()` launches `repository.logout()` in `viewModelScope` (Dispatchers.Main). `AuthRepository.logout()` runs the logout hooks, then `session.logout(USER)` and `clearCaches()`. Every cache that does I/O switches with `withContext(Dispatchers.IO)` (cache dir in `AuthModule`, Room in `GtdDataModule`, push in `CockpitPushService`) and must resume on Main. Robolectric's default PAUSED looper runs on the test thread, so a Main resumption only runs when the test thread runs the main looper. `shadowOf(Looper.getMainLooper()).idle()` called from the test thread is the right API for that. The `Thread.sleep(POLL_MS)` between iterations lets the real IO threads finish and post back. Correct.
- Network logout test: the `AuthSession.loggedOut` collector runs in `appScope` on `Dispatchers.Default`, not Main, so pumping the looper is not needed there. It does no harm, and it keeps both tests on one helper. Correct.
- Loop logic: it checks `file.exists()` before the deadline, so a file deleted during the last sleep is never reported as a timeout. The deadline uses the monotonic `System.nanoTime()`, so Robolectric's fake `SystemClock` does not affect it. Correct.
- `idle()` does not move Robolectric's clock forward, so delayed Main messages do not fire inside the helper. In this logout path the only delayed Main work is the `withTimeoutOrNull` of the logout hooks and the revocation. That only matters if a hook hangs. In these tests the only hook (`PushRegistrar.deleteOnServer`) returns at once because no push instance is stored, and `revokeDevice` is skipped because the tokens are stored with a null device id. Nothing in these tests depends on clock time, so no change is needed.
- Thread dump on timeout: `appThreads()` prints every live thread whose stack contains a `ch.sonensei.cockpit` frame, up to 40 frames each, and includes it in the `AssertionError`. This meets the criterion. Limitation (not a defect): a coroutine that is suspended, not running, has no thread stack. The dump will show the test thread's own polling frames and any IO thread that is busy in app code, but not a logout suspended on a lock or on a Main resumption. Showing those would need `kotlinx-coroutines-debug` `DebugProbes`, which is a new dependency and out of scope here.
- Lint/style: the constants are named, there are no magic numbers, and the KDoc explains the reason. `ktlintCheck` was not run by the test-writer (see below).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. The cache-clearing waits run Robolectric's main looper explicitly and dump the app threads on a timeout | `logging out from settings clears the cached files`, `a logout by the network layer also clears the cached files` (helper `awaitDeleted` / `appThreads`) | Code review of `AppStartRoutingTest.kt:169,183,193-211` against the production logout path | Pass (by inspection; the timeout/dump branch is not exercised by a passing run) |
| 2. AppStartRoutingTest passes repeatedly while every CPU core is busy | all 9 tests in `AppStartRoutingTest` | Parent's `.tmp/load.sh` loop (16 busy loops + repeated `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.AppStartRoutingTest' --rerun`) | Not run by the test-writer: the permission system denied every Gradle invocation in this session. The parent owns this evidence. |
| 3. `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes | whole Android unit suite + lint | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` | Not run: Gradle invocation denied by the permission system here, and the parent asked not to run heavy suites during its load loop |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.AppStartRoutingTest'` (also tried as `android/gradlew -p android ...`)

Result:

Not run: the tool permission system denied the command. For reference only, not as evidence from this helper: the most recent result file `android/app/build/test-results/testDebugUnitTest/TEST-ch.sonensei.cockpit.auth.AppStartRoutingTest.xml` (timestamp 2026-11-09T03:55:05Z, written by the parent's run) shows 9 tests, 0 failures, 0 errors, with `logging out from settings clears the cached files` at 0.285 s. Its timestamp is about one minute before commit a60ec23da (04:56:07+01:00). This helper cannot confirm it was produced from the committed content.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run (Gradle denied by the permission system. The parent also asked to avoid heavy suites during its CPU-load loop.)

## .taskcheck

Result:

Not run (`sh scripts/check.sh` runs Gradle, which is denied here. The parent runs it before `done`.)

## Bugs found

None. No production defect was found. The change is test-only.

## Coverage

Status: not applicable. The task changes no production code (`git diff efa76d6fb..HEAD` touches only `AppStartRoutingTest.kt` and the task file), so the task coverage scope is empty. This is not reported as 100%.

Metric: whole-file coverage of task-changed production files: N/A (no files in scope).
Changed-line coverage: not measured (no production lines changed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `fix-flaky-app-start-logout-test`; base `efa76d6fb3ed2599a2db987214cad3713cd301c3`; HEAD `a60ec23da7d56b3ead7bf55b39a85e8bc7d6491a`; task commits `f10cc6343` (claim), `efa76d6fb` (assume), `a60ec23da` (implementation), given by the parent and confirmed with `git diff --stat c1417063d..HEAD`
- Source/test snapshot identifier (including uncommitted inputs): HEAD `a60ec23da`, clean working tree; `AppStartRoutingTest.kt` sha256 `55cd58d7b6b520c8c5cda5b8da4e10253a82bfd780703ccc7d48521f4f73c4ab`
- Exact included files; renamed/deleted/excluded files and reasons: included production files: none. Excluded: `android/app/src/test/java/ch/sonensei/cockpit/auth/AppStartRoutingTest.kt` (test file), `tasks/fix-flaky-app-start-logout-test.md` (task metadata)
- Provider/version, command, working directory, and test selection: no coverage run (empty scope)
- Run start/completion time and exit status: N/A
- Generated report paths and report hashes/run identifier: N/A
- Evidence outputs are fresh and inputs stayed unchanged: N/A (no measurement)
- Final run after latest test additions and production fixes: N/A (no test additions, no production fixes)

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (no production files changed) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `AppStartRoutingTest.kt:196-198,204-211` (timeout branch and `appThreads`) | Criterion 1: thread dump on timeout | Test code, not production. It only runs on a real timeout. Checked by inspection. Forcing it would need a stuck logout, which needs production or test-graph changes outside this task. |

## Unable to test

- Criterion 2 (passing repeatedly under full CPU load) and criterion 3 (the Android check): the permission system denied every Gradle command for this helper. Follow-up: the parent records these from its own `.tmp/load.sh` loop and its own run of `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`.
- The thread-dump output on a real timeout: it only appears when the logout hangs. Checked by inspection only.

## Observations (not defects, no change made)

- `the login form in the app reports a server address the app refuses` (`AppStartRoutingTest.kt:149`) still uses `composeRule.waitUntil(WAIT_MS = 5 s)` on a coroutine started in `viewModelScope`. Under heavy load it could, in principle, hit the same frame-driven looper stall. It was not reported as flaky, and `AuthRepository.login` returns `InvalidServerUrl` before any IO hop. It was left unchanged because it is outside the task goal. If it ever flakes, the same explicit looper-pumping wait is the fix.
- The thread dump cannot show suspended coroutines (see the review above). It is acceptable for the criterion as written.

## Summary

No additional tests were necessary for the reviewed scope. The `awaitDeleted` helper is correct for the logout threading model and meets criterion 1 by inspection. No production defects were found. The test-writer could not execute any Gradle command (permission denied), so the load and check criteria (2 and 3) still need the parent's own run evidence.
