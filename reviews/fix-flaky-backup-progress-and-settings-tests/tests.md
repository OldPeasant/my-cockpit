# Test Report

Date: 2026-11-02

Task: fix-flaky-backup-progress-and-settings-tests - Flaky tests: BackupWebTests progress step and SettingsServerChangeTest leak

Scope: `git diff 5113da1d5429ddc30001f501c56652ad45dd562c..82b7afd09996b5f8fa8422a88645199c15329568` (branch main, HEAD 82b7afd09996b5f8fa8422a88645199c15329568). Task commits: 182352549 (the original fix) and 82b7afd09 (review CR-1 and the tearDown timeout hardening, see `reviews/fix-flaky-backup-progress-and-settings-tests/fixes.md`). The parent gave the base and the commits. The working tree was clean apart from this report. This report replaces the earlier one written for revision 182352549.

Requirements:
- tasks/fix-flaky-backup-progress-and-settings-tests.md (goal and the 4 acceptance criteria)
- AGENTS.md (project notes, Evidence), parent instructions
- Production semantics checked (unchanged by the task): server `backup/domain/BackupService.kt` (progress `done++` from 0), `BackupJobService.kt` (one UPDATED per progress call), android `SettingsViewModel.kt` (`saveServer` -> `loadUser` launches on `viewModelScope`)

## Test files changed

The task commits changed these files. The test-writer changed no test files in this run.

- `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupWebTests.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/settings/SettingsServerChangeTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/AuthRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModelRoomCacheTest.kt`

## Tests added or updated

Count: 0 by the test-writer. The task commits updated 4 test files. I reviewed the final versions and judged them correct and sufficient.

Review of the final test code:

- BackupWebTests `awaitGatedStepEvent` (unchanged since 182352549): it loops with a 5 s deadline until `step == GATED_STEP` and the UPDATED count is at least `stepsDone + 1`. That matches production: step n carries `stepsDone = n` and is announced by the (n + 1)th UPDATED event. `>=` is safe because the job is held at the gated step until the test opens the gate. This mirrors `BackupEventsStreamTests` as criterion 1 asks.
- SettingsServerChangeTest tearDown: it runs `cancelAndJoin` on every tracked view model job, now inside `runBlocking { withTimeout(5_000) { ... } }`. `storeScope.cancel()` and `Dispatchers.resetMain()` are in `finally`, so they run after the join and also after a join timeout. All view models are created through `viewModel()` (line 90), so all are tracked. The `cancel` import is still used (`storeScope.cancel()`, `collector.cancel()`). No unused import.
- AuthRepositoryTest (logout outliving the screen), CR-1: the view model is stored in a nullable `var` declared before `try`. `finally` runs `runCatching { runBlocking { withTimeout(5_000) { job.cancelAndJoin() } } }` before `resetMain`, so the join now also happens when an assertion fails. `cancelAndJoin` avoids a 5 s wait if the test fails before its own `viewModelScope.cancel()`. `runCatching` keeps a join timeout from hiding the original assertion error. The `job` and `cancelAndJoin` imports are present and used.
- CalendarViewModelRoomCacheTest: the same pattern. The join has a 5 s timeout, and `resetMain` and `database.close()` are in `finally`. Both view model constructions use the tracking `viewModel()` helper (line 72).
- All changed lines stay within the 140-character ktlint limit (checked with awk). Ktlint itself still has to run in Gradle, which the parent does.
- Criterion 3 sweep, repeated on the final code: in `android/app/src/test`, only `AuthRepositoryTest` and `SettingsServerChangeTest` combine `resetMain` with a real `PreferenceDataStoreFactory` or `Dispatchers.IO` store. Both are fixed. `CalendarViewModelRoomCacheTest` (a Room cache resuming on Main) is fixed too.

The optional hardening I suggested earlier (a timeout on the tearDown joins) has been applied. I have no further observations.

I added no new regression test. The defects are timing races in tests, not in production behavior. A deterministic reproduction would need hooks in production code, which the task excludes. The repeated runs under load named in the criteria are the right evidence.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. BackupWebTests progress test waits (with deadline) for the gated step, 20x in a row, also under load | `BackupWebTests > progress is announced as BACKUP change events while the export runs` | `cd server && ./gradlew test --tests '*BackupWebTests*'` 20x under load, run by the parent | Code review: Pass. Execution: Not run by the test-writer, because Gradle is denied for sub-agents. The parent runs it and records the result and the load |
| 2. No coroutine touches Main after resetMain; SettingsServerChangeTest + SettingsViewModelTest 20x, 0 failures, also under load | `SettingsServerChangeTest`, `SettingsViewModelTest` | Android loop of SettingsServerChangeTest + SettingsViewModelTest + AuthRepositoryTest + CalendarViewModelRoomCacheTest (`--rerun-tasks`) 20x under load, run by the parent | Code review: Pass. Execution: Not run by the test-writer (Gradle denied). The parent runs it |
| 3. Other Android tests with a real store + setMain/resetMain checked and fixed if local | AuthRepositoryTest logout test, CalendarViewModelRoomCacheTest | grep sweep over `android/app/src/test` for `resetMain` plus `PreferenceDataStoreFactory` / Room / `Dispatchers.IO` | Pass: no other affected test. The fixed tests run in the parent's Android loop |
| 4. `sh scripts/check.sh` passes | whole check | `sh scripts/check.sh`, run by the parent | Not run by the test-writer (Gradle denied). The parent runs it |

## Focused test execution

Command:

None by the test-writer. Gradle is denied for sub-agents in this environment. The parent runs BackupWebTests 20x under load, and the Android loop (SettingsServerChangeTest + SettingsViewModelTest + AuthRepositoryTest + CalendarViewModelRoomCacheTest) 20x under load. The parent records the results with `tasks.py verify`.

Result:

Not run by the test-writer. The parent runs it.

## Full test execution

Command:

`sh scripts/check.sh` (the parent runs it)

Result:

Not run by the test-writer. The parent runs it.

## .taskcheck

Result:

Not run by the test-writer (Gradle denied). The parent runs `sh scripts/check.sh`.

## Bugs found

None. No production defect was found. The races were in test teardown and test waiting logic. The app behavior is intended: `saveServer` reloads the user after a valid save, and logout completes non-cancellably.

## Coverage

Status: not applicable. The task changes no production code. `git diff --name-only 5113da1d5..82b7afd09` lists only 4 files under `src/test/` plus review reports under `reviews/`. The task coverage scope (task-changed production files) is therefore empty. No coverage run was made. The result is N/A, not 100%.

Metric: whole-file coverage of task-changed production files: N/A, because the scope is empty.
Changed-line coverage: not measured, because no production lines changed.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: fix-flaky-backup-progress-and-settings-tests; base 5113da1d5429ddc30001f501c56652ad45dd562c; HEAD 82b7afd09996b5f8fa8422a88645199c15329568; task commits 182352549 and 82b7afd09, given by the parent and confirmed with `git log` and `git diff --stat`
- Source/test snapshot identifier (including uncommitted inputs): HEAD 82b7afd09996b5f8fa8422a88645199c15329568 with a clean tree. The test-writer added no tests.
- Exact included files; renamed/deleted/excluded files and reasons: no production files are included. The 4 test files and the `reviews/` reports are excluded because they are not production code. There are no renames or deletions.
- Provider/version, command, working directory, and test selection: none (no production scope, and Gradle is denied for sub-agents)
- Run start/completion time and exit status: no run
- Generated report paths and report hashes/run identifier: none
- Evidence outputs are fresh and inputs stayed unchanged: not applicable, because there is no coverage output
- Final run after latest test additions and production fixes: not applicable, because no production code is in scope

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (no production files changed) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| none | not applicable | no production code in scope |

## Unable to test

- I could not run any of the executions the criteria name (BackupWebTests 20x under load, the Android loops, `sh scripts/check.sh`) because Gradle is denied for sub-agents here. The parent runs them and records the commands, the load and the results with `tasks.py verify`. That is the follow-up.
- A deterministic regression test for the two races is not possible without hooks in production code, which the task excludes. The repeated runs under load are the evidence. No follow-up needed.

## Summary

No additional tests were necessary for the reviewed scope. The final versions of the 4 changed tests (revision 82b7afd09, including CR-1 and the timeout hardening) are correct against the production event and coroutine behavior. The criterion 3 sweep found no other affected test, and no production defects were found. The parent runs the test executions (BackupWebTests 20x under load, the Android loops, `sh scripts/check.sh`) and records their results.
