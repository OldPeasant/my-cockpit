# Code Review

Date: 2026-11-02

Task: fix-flaky-backup-progress-and-settings-tests - Flaky tests: BackupWebTests progress step and SettingsServerChangeTest leak

Scope: final task code, `git diff 5113da1d5..82b7afd09` (commits 182352549 and 82b7afd09), committed changes only. Re-review after the fix of the earlier CR-1.

Requirements:
- tasks/fix-flaky-backup-progress-and-settings-tests.md (goal and acceptance criteria 1-4)
- AGENTS.md (task protocol, evidence rules, project conventions)
- reviews/fix-flaky-backup-progress-and-settings-tests/fixes.md (what 82b7afd09 claims to fix)
- Parent instructions: verify CR-1 is resolved and that 82b7afd09 adds no new issues

Git range:
- Branch: main
- HEAD: 82b7afd09996b5f8fa8422a88645199c15329568
- Base/start: 5113da1d5429ddc30001f501c56652ad45dd562c
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.

What I checked on the final code:

- Earlier CR-1 (AuthRepositoryTest joined the view model only when the assertions passed) is resolved. The view model is now kept in a nullable `viewModel` declared before the `try`. The join moved into `finally`, before `Dispatchers.resetMain()`, as `runCatching { runBlocking { withTimeout(5_000) { job.cancelAndJoin() } } }`. The old `join()` in the `try` is gone. `cancelAndJoin` does nothing extra on the normal path, because the scope is already cancelled. If the test fails before its own `cancel()`, the join cannot wait on a scope that was never cancelled. `runCatching` stops a join timeout from replacing the original assertion error. If the test fails before the view model is built, `viewModel` is null and the join is skipped.
- tearDown hardening in SettingsServerChangeTest and CalendarViewModelRoomCacheTest: the joins are now wrapped in `withTimeout(5_000)`. `storeScope.cancel()`, `resetMain()` and `database.close()` run in `finally`, so a timeout still frees Main and the store. A timeout fails the test, which is what "fail fast" should do here. SettingsServerChangeTest still cancels and joins the view models before `storeScope.cancel()`, which is the right order. Every `CalendarViewModel` in CalendarViewModelRoomCacheTest is created through `viewModel()`, so all of them get joined.
- Compile and lint: `withTimeout`, `runBlocking`, `job`, `cancelAndJoin` and `viewModelScope` are imported in all three Android files. The new imports follow the existing order. No changed line is longer than 140 characters. I could not run Gradle here (it is denied for sub-agents), so the parent's ktlint and test run is the final proof.
- BackupWebTests: no change since the last review. `awaitGatedStepEvent` waits until the job is at `GATED_STEP` and at least `stepsDone + 1` UPDATED events were delivered for that job, with a 5 s deadline. This works because `BackupService.export` reports progress once before each step, and each report publishes exactly one UPDATED event. It matches the loop in `BackupEventsStreamTests` that criterion 1 asks for.
- Criterion 3 scope: the extra changes to AuthRepositoryTest and CalendarViewModelRoomCacheTest are local test changes for the same pattern, so the criterion allows them. No production code changed.
- Evidence: `python tasks.py verify fix-flaky-backup-progress-and-settings-tests` shows all four criteria still unverified. The parent still has to record the 20-run loops (with the load recorded) for criteria 1 and 2, the search result for criterion 3, and `sh scripts/check.sh` for criterion 4, all on this revision. Nothing is claimed without evidence.
