# Test Report

Date: 2026-11-02

Task: fix-flaky-backup-progress-and-settings-tests - Flaky tests: BackupWebTests progress step and SettingsServerChangeTest leak

Scope: final revision e94a8284c5b4dc99210cafe18f3db9dac7ecb67e on branch main (HEAD read from `.git/refs/heads/main`). The parent named the task code commits: f1da8ec9e, 539b921d7 and e94a8284c. The base is `git merge-base HEAD origin/main`. The test-writer could not compute that hash itself because Bash/git is denied for this sub-agent. The local `origin/main` ref was 9e0bb0de7b474934e04c17d6ca4b95fbffaf37cd. The working tree was reported clean apart from this report. This report replaces the earlier one, which covered the same test code before the formatting-only commit e94a8284c.

Requirements:
- tasks/fix-flaky-backup-progress-and-settings-tests.md (goal and the 4 acceptance criteria)
- AGENTS.md (project notes, Evidence), parent instructions
- Production semantics checked (unchanged by the task): server `backup/domain/BackupService.kt` and `BackupJobService.kt` (one UPDATED per progress step), android `SettingsViewModel.kt` (`saveServer` then `loadUser` on `viewModelScope`)

## Test files changed

The task commits changed these files. The test-writer changed no test files in this run.

- `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupWebTests.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/settings/SettingsServerChangeTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/AuthRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModelRoomCacheTest.kt`

## Tests added or updated

Count: 0 by the test-writer. The task commits updated 4 test files. I re-read their final versions at e94a8284c and judged them correct and sufficient.

Re-assessment of the final code:

- e94a8284c (formatting only): in `SettingsServerChangeTest.tearDown` (lines 72-84) and `CalendarViewModelRoomCacheTest.tearDown` (lines 78-90), the call `it.viewModelScope.coroutineContext.job.cancelAndJoin()` is now split across two lines (ktlint chain-method-continuation). The behavior is the same: every tracked view model job is cancelled and joined inside `runBlocking { withTimeout(5_000) { ... } }`, and the cleanup (`storeScope.cancel()` / `Dispatchers.resetMain()`, or `resetMain()` / `database.close()`) is in `finally`. No import changed. `cancelAndJoin`, `job`, `withTimeout`, `runBlocking` and `cancel` are all still imported and used.
- BackupWebTests `awaitGatedStepEvent` (lines 198-207): unchanged. It loops with a 5 s deadline until `step == GATED_STEP` and the UPDATED count for the job is at least `stepsDone + 1`. This mirrors `BackupEventsStreamTests` as criterion 1 asks. `>=` is safe because the job is held at the gated step until the test opens the gate.
- SettingsServerChangeTest: all view models are created through the tracking `viewModel()` helper (line 97), so the tearDown join covers the `saveServer` then `loadUser` work that previously resumed on Main after `resetMain`.
- AuthRepositoryTest (the logout outlives the screen, lines 455-485): unchanged. A nullable view model is declared before `try`, and in `finally` it is joined with `runCatching { runBlocking { withTimeout(5_000) { cancelAndJoin() } } }` before `resetMain`, so the join also runs when an assertion fails (CR-1).
- CalendarViewModelRoomCacheTest: both tests build view models through the tracking `viewModel()` helper (line 72).
- Criterion 3 sweep: the earlier sweep found only AuthRepositoryTest and SettingsServerChangeTest combining `resetMain` with a real DataStore or IO store, plus CalendarViewModelRoomCacheTest (a Room cache resuming on Main). All three are fixed. The formatting commit touches no other test, so that result still holds. I did not re-run the grep, because Bash is denied here.

I added no regression test. The defects are timing races in test teardown and test waiting, not in production behavior. A deterministic reproduction would need hooks in production code, which the task excludes. The repeated runs under load named in the criteria are the right evidence.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. BackupWebTests progress test waits (with deadline) for the gated step, 20x, also under load | `BackupWebTests` progress is announced as BACKUP change events while the export runs | `cd server && ./gradlew test --tests '*BackupWebTests*' --rerun` 20x under load (run by the parent, recorded with tasks.py verify) | Code review: Pass. Not run by the test-writer (Gradle denied). Server test code is unchanged by e94a8284c |
| 2. No coroutine touches Main after resetMain; SettingsServerChangeTest + SettingsViewModelTest 20x, also under load | `SettingsServerChangeTest`, `SettingsViewModelTest` | Android 4-test loop 20x under load (parent, recorded). Parent rerun on e94a8284c: `cd android && ./gradlew testDebugUnitTest --tests '*SettingsServerChangeTest' --tests '*CalendarViewModelRoomCacheTest'` | Code review: Pass. Parent run on the final revision: Pass. Not run by the test-writer |
| 3. Other Android tests with a real store + setMain/resetMain checked and fixed if local | AuthRepositoryTest logout test, CalendarViewModelRoomCacheTest | grep sweep over `android/app/src/test` (earlier run, recorded with tasks.py verify) | Pass. The formatting commit does not affect it |
| 4. `sh scripts/check.sh` passes | whole check | `sh scripts/check.sh` (parent) | Not run by the test-writer (Bash/Gradle denied). The parent runs it on the final revision. Parent ktlintCheck on e94a8284c: clean |

## Focused test execution

Command:

None by the test-writer, because Bash and Gradle are denied for this sub-agent. The parent ran on e94a8284c: `cd android && ./gradlew testDebugUnitTest --tests '*SettingsServerChangeTest' --tests '*CalendarViewModelRoomCacheTest'`, and `./gradlew ktlintCheck`.

Result:

Pass (parent run on the final revision; ktlintCheck clean). Not run by the test-writer.

## Full test execution

Command:

`sh scripts/check.sh` (the parent runs it)

Result:

Not run by the test-writer. The parent runs it.

## .taskcheck

Result:

Not run by the test-writer (Bash denied). The parent runs `sh scripts/check.sh`.

## Bugs found

None. No production defect was found. The races were in test teardown and test waiting logic. The app behavior is intended: `saveServer` reloads the user after a valid save, and logout completes non-cancellably.

## Coverage

Status: not applicable. The task changes no production code. The task commits (f1da8ec9e, 539b921d7, e94a8284c, as given by the parent) touch only the 4 test files listed above. Earlier task commits added review reports under `reviews/`. The earlier report confirmed this with `git diff --name-only`, and the formatting commit e94a8284c changes only two test files. The task coverage scope (task-changed production files) is therefore empty. No coverage run was made. The result is N/A, not 100%. The earlier N/A assessment still holds and is carried over.

Metric: whole-file coverage of task-changed production files: N/A, because the scope is empty.
Changed-line coverage: not measured, because no production lines changed.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: fix-flaky-backup-progress-and-settings-tests. The base is `git merge-base HEAD origin/main`, which the test-writer could not compute (Bash denied); the local origin/main ref was 9e0bb0de7b474934e04c17d6ca4b95fbffaf37cd. HEAD is e94a8284c5b4dc99210cafe18f3db9dac7ecb67e. The parent gave the task commits f1da8ec9e, 539b921d7 and e94a8284c.
- Source/test snapshot identifier (including uncommitted inputs): HEAD e94a8284c5b4dc99210cafe18f3db9dac7ecb67e. The tree was reported clean, and the test-writer added no tests.
- Exact included files; renamed/deleted/excluded files and reasons: no production files are included. The 4 test files and the `reviews/` reports are excluded because they are not production code. There are no renames or deletions.
- Provider/version, command, working directory, and test selection: none (no production scope; Bash and Gradle denied for this sub-agent)
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

- The test-writer could not run any command: Bash, and with it git and Gradle, is denied for this sub-agent. The parent ran the focused Android tests and ktlintCheck on the final revision (both passed). Before the formatting commit, the parent also ran the loops under load for criteria 1 and 2 and recorded them. The parent runs `sh scripts/check.sh` for criterion 4. That is the follow-up.
- I did not re-verify the task diff and merge-base with git in this run. The scope relies on the commit list from the parent and on the earlier report's `git diff --name-only`. Reading the files shows that e94a8284c changed only the chain formatting of the two tearDown joins.
- A deterministic regression test for the two races is not possible without hooks in production code, which the task excludes. No follow-up needed.

## Summary

No additional tests were necessary for the reviewed scope. The final revision e94a8284c only reformats the tearDown joins in SettingsServerChangeTest and CalendarViewModelRoomCacheTest, and their behavior is unchanged. The 4 changed tests are still correct against the production event and coroutine behavior, and no production defects were found. Coverage is N/A because no production code changed. The parent runs `sh scripts/check.sh` and records the final results.
