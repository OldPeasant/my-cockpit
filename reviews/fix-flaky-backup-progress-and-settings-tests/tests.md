# Test Report

Date: 2026-11-02

Task: fix-flaky-backup-progress-and-settings-tests - Flaky tests: BackupWebTests progress step and SettingsServerChangeTest leak

Scope: `git diff 5113da1d5429ddc30001f501c56652ad45dd562c..18235254954e360de668b09d3bbc1143196daab0` (branch main, HEAD 18235254954e360de668b09d3bbc1143196daab0, a single task commit; the working tree was clean apart from this report). The parent gave the base and task commit.

Requirements:
- tasks/fix-flaky-backup-progress-and-settings-tests.md (goal and the 4 acceptance criteria)
- AGENTS.md (project notes, Evidence), parent instructions
- Production semantics checked: server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupService.kt (progress `done++` from 0, final "done" step), BackupJobService.kt (`update` publishes exactly one UPDATED per progress call), android SettingsViewModel.kt (`saveServer` -> `loadUser` launches on `viewModelScope`)

## Test files changed

The task commit changed these files. The test-writer changed no test files.

- `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupWebTests.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/settings/SettingsServerChangeTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/AuthRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModelRoomCacheTest.kt`

## Tests added or updated

Count: 0 by the test-writer. The task commit updated 4 test files. I reviewed them and judged them correct and sufficient.

Review of the changed tests:

- BackupWebTests `awaitGatedStepEvent`: the condition `step == GATED_STEP && updates >= stepsDone + 1` matches production. `BackupService.export` calls `progress(BackupProgress(name, done++, total))`, so step n has `stepsDone = n`. `BackupJobService.update` publishes one UPDATED per call after the job map changes. That means the gated step's own event is the (n + 1)th UPDATED. Using `>=` instead of `==` is safe because the job is held at the gated step, so no later UPDATED can arrive before the gate opens. The wait has a 5 s deadline with a clear message. It mirrors `BackupEventsStreamTests` lines 91-95, as criterion 1 asks. The new constant `GATED_STEP` replaces the literal.
- SettingsServerChangeTest tearDown: `cancelAndJoin` on each view model's `viewModelScope` job waits until the in-flight `saveServer` coroutine has finished: the DataStore write on `storeScope`, then `loadUser`'s `viewModelScope.launch`. `storeScope.cancel()` and `resetMain` run only after that. All view models are created through `viewModel()`, so all are tracked (lines 96, 113, 127). The order (join, then cancel the store scope, then reset Main) is correct.
- AuthRepositoryTest (logout outliving the screen): it joins the already-cancelled `viewModelScope` job with a 5 s timeout before `resetMain` in `finally`. This covers the non-cancellable logout that resumes its caller on Main.
- CalendarViewModelRoomCacheTest: the same `cancelAndJoin` tearDown pattern. Both view model constructions now go through the tracking `viewModel()` helper.
- Criterion 3 sweep, done independently by the test-writer: only 2 Android test files both use `resetMain` and a real `PreferenceDataStoreFactory` / `Dispatchers.IO` / Room store, `AuthRepositoryTest` and `SettingsServerChangeTest`, and both are fixed. `CalendarViewModelRoomCacheTest` (Room cache resuming on Main) is also fixed. The other real-store tests (TokenAuthenticatorTest, ServerUrlTest, AuthRepositoryLogoutHookTest, PasskeyRepositoryTest, GtdCacheConsistencyTest, the capture/queue tests) do not call `setMain`/`resetMain`, so the pattern does not apply to them.

Minor observation, not a defect: the `runBlocking { ...cancelAndJoin() }` tearDowns in SettingsServerChangeTest and CalendarViewModelRoomCacheTest have no `withTimeout`. If a store write ever hung, tearDown would hang until the Gradle/.taskcheck timeout instead of failing fast. The work being joined is cancellable DataStore/Room I/O, so the risk is low. Adding `withTimeout(5_000)` as in AuthRepositoryTest would be optional hardening. No action required.

No new regression test was added. The defects were timing races in tests, not production behavior. A deterministic reproduction would need hooks in production code, which this task does not allow, so the repeated runs under load named in the criteria are the right evidence.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. BackupWebTests progress test waits (with deadline) for the gated step, 20x in a row, also under load | `BackupWebTests > progress is announced as BACKUP change events while the export runs` | Parent runs `cd server && ./gradlew test --tests '*BackupWebTests*'` 20x under CPU load | Code review: Pass (logic matches production event semantics). Execution: Not run by the test-writer (Gradle is denied for sub-agents); the parent runs it and records the result and load |
| 2. SettingsServerChangeTest leaves no coroutine touching Main after resetMain; with SettingsViewModelTest 20x, 0 failures, also under load | `SettingsServerChangeTest` (3 tests), `SettingsViewModelTest` | Parent runs `cd android && ./gradlew testDebugUnitTest --tests '*SettingsServerChangeTest' --tests '*SettingsViewModelTest' --rerun-tasks` in a loop under load | Code review: Pass. Execution: Not run by the test-writer (Gradle denied); the parent runs it |
| 3. Other Android tests with a real store + setMain/resetMain checked and fixed if local | AuthRepositoryTest logout test, CalendarViewModelRoomCacheTest | grep sweep over android/app/src/test (see above) | Pass (sweep confirms no other affected test). Execution of the fixed tests: Not run by the test-writer (Gradle denied); the parent runs them |
| 4. `sh scripts/check.sh` passes | whole check | `sh scripts/check.sh` | Not run by the test-writer (Gradle denied); the parent runs it |

## Focused test execution

Command:

None by the test-writer. Gradle is denied for sub-agents in this environment. The parent runs the focused loops (BackupWebTests 20x under CPU load, the Android Settings/Auth/Calendar loops) and records their results with `tasks.py verify`.

Result:

Not run (by the test-writer). The parent will run it.

## Full test execution

Command:

`sh scripts/check.sh` (the parent runs it)

Result:

Not run (by the test-writer). The parent will run it.

## .taskcheck

Result:

Not run (by the test-writer; Gradle denied). The parent runs it.

## Bugs found

None. No production defect was found. The races were in test teardown and test waiting logic. The app behavior (`saveServer` reloading the user after a valid save; logout completing non-cancellably) is intended.

## Coverage

Status: not applicable. The task changes no production code. `git diff 5113da1d5..182352549 --stat` lists only 4 files under `src/test/` (server and android), so the task coverage scope (task-changed production files) is empty. No coverage run was made. Coverage would be N/A, not 100%.

Metric: whole-file coverage of task-changed production files - N/A, the scope is empty.
Changed-line coverage: not measured (no production lines changed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: fix-flaky-backup-progress-and-settings-tests; base 5113da1d5429ddc30001f501c56652ad45dd562c; HEAD 18235254954e360de668b09d3bbc1143196daab0; the single task commit 182352549, given by the parent and confirmed by `git diff --stat`
- Source/test snapshot identifier (including uncommitted inputs): HEAD 18235254954e360de668b09d3bbc1143196daab0, clean tree; the test-writer added no tests
- Exact included files; renamed/deleted/excluded files and reasons: no production files are included. The 4 test files are excluded as tests. There are no renames or deletions.
- Provider/version, command, working directory, and test selection: none (no production scope; Gradle is also denied for sub-agents)
- Run start/completion time and exit status: no run
- Generated report paths and report hashes/run identifier: none
- Evidence outputs are fresh and inputs stayed unchanged: not applicable, no coverage output
- Final run after latest test additions and production fixes: not applicable, no production code in scope

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (no production files changed) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| none | not applicable | no production code in scope |

## Unable to test

- I could not run any of the test executions the criteria name (BackupWebTests 20x under load, the Android loops, `sh scripts/check.sh`): Gradle is denied for sub-agents here. The parent runs them and records the commands, load and results as evidence. Follow-up: the parent records the results with `tasks.py verify`.
- A deterministic regression test for the two races is not possible without production hooks, which the task excludes. The repeated runs under load are the evidence. No follow-up needed.

## Summary

No additional tests were necessary for the reviewed scope. The 4 changed tests are correct against the production event/coroutine semantics, the criterion 3 sweep found no other affected test, and no production defects were found. The parent runs the test executions (BackupWebTests 20x under CPU load, the Android loops, `sh scripts/check.sh`) and records their results.
