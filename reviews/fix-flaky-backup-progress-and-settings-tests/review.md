# Code Review

Date: 2026-11-02

Task: fix-flaky-backup-progress-and-settings-tests - Flaky tests: BackupWebTests progress step and SettingsServerChangeTest leak

Scope: task commit 182352549 on top of base 5113da1d5 (`git diff 5113da1d5..182352549`), committed changes only

Requirements:
- tasks/fix-flaky-backup-progress-and-settings-tests.md (goal and acceptance criteria 1-4)
- AGENTS.md (task protocol, evidence rules, project conventions)
- Parent instructions: verify the race reasoning (cancelAndJoin hang risk, whether joining a cancelled scope waits for children resumed on IO threads, other tests with the same pattern)

Git range:
- Branch: main
- HEAD: 182352549
- Base/start: 5113da1d5429ddc30001f501c56652ad45dd562c
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | AuthRepositoryTest joins the view model only when the assertions pass**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/auth/AuthRepositoryTest.kt:477`
    - Category: Tests
    - Problem: the new `viewModelScope` job `join()` sits in the `try` block after the `assertNull` checks (lines 473-474). If one of those assertions fails (for example the 7 s polling deadline runs out under load), the `finally` runs `resetMain` without waiting for the non-cancellable logout. That logout can then resume on Main after the reset, which is the same leak this task fixes.
    - Why it matters: a real failure in this test would also show up as a misleading `UncaughtExceptionsBeforeTest` in an unrelated later test. That is the kind of confusing cross-test failure the task was opened to remove.
    - Suggested fix: move the `runBlocking { withTimeout(5_000) { ...job.join() } }` into the `finally` block before `Dispatchers.resetMain()`, for example wrapped in `runCatching` so a join timeout does not hide the original assertion error.

## Review conclusion

No material issues found.

I checked the race reasoning against the current code:

- BackupWebTests: `BackupService.export` calls `progress(BackupProgress(name, done++, total))` before each step. `BackupJobService.update` then publishes exactly one UPDATED event per progress call. So the step with `stepsDone == n` is announced by the (n + 1)th UPDATED event for that job id. `awaitGatedStepEvent` reads the event count before the job. Because the export is held at the gated step, the job cannot move past it, so the condition cannot be met too early. Out-of-order delivery from the publish executor does not matter, because only the count is used. The 5 s deadline fails with a clear message. This matches `BackupEventsStreamTests` (lines 91-95).
- SettingsServerChangeTest: `saveServer` resumes after `setServerUrl` on the DataStore IO thread (Main is `UnconfinedTestDispatcher`), then calls `loadUser()`, which calls `viewModelScope.launch` and so touches Main. Cancelling the scope's job moves it to "cancelling". `join()` returns only after every child body has finished, including a body that is running on an IO thread at the time. If that body runs `loadUser()` concurrently, the new `launch` attaches to the still-cancelling parent (the save child is not finished yet), touches Main synchronously, and completes cancelled before `join()` returns. If the save has not resumed yet, it resumes with `CancellationException`, which is rethrown, so `loadUser()` never runs. Running `cancelAndJoin` before `storeScope.cancel()` is the right order.
- Hang risk: every suspension point in the joined scopes can be cancelled: DataStore `edit` and `data` collection, Room suspend DAO calls (a blocking statement already running finishes first), `CompletableDeferred.await`, and mocked `getCurrentUser`. `AuthRepository.logout` runs under `NonCancellable`, but it is bounded by `REVOKE_TIMEOUT_MS`, and the test only joins after the tokens are cleared and inside a 5 s `withTimeout`. No join can hang on the current code. SettingsServerChangeTest and CalendarViewModelRoomCacheTest join without a timeout, unlike AuthRepositoryTest. I am not reporting that as a finding because no current path can block.
- CalendarViewModelRoomCacheTest: all cache work in `CalendarViewModel` runs in `viewModelScope` (lines 156, 198, 228, 308), so joining covers it. `database.close()` now runs after the join.
- Same pattern elsewhere: I cross-checked the tests that call `setMain` against the tests that use a real `PreferenceDataStoreFactory`, Room, OkHttp/MockWebServer or `Dispatchers.IO`. Only these three overlap: AuthRepositoryTest, CalendarViewModelRoomCacheTest and SettingsServerChangeTest. ShareViewModelTest also has `withContext(Dispatchers.IO)` in the view model (ShareViewModel.kt:83), but every test that loads files waits until `loadingFiles` is false. That state is written by the coroutine's last statement, after it has already resumed on Main, so nothing touches Main after the test ends. The ShareReadLater tests do not load files. I found no missed test.
- Evidence: `tasks.py verify` lists all four criteria as not yet verified. The parent still has to record the 20-run loops (with load) and `sh scripts/check.sh`. Nothing is claimed without evidence.
