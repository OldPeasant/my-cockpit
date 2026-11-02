# Fix Report

Date: 2026-11-02

Task: fix-flaky-backup-progress-and-settings-tests - Flaky tests: BackupWebTests progress step and SettingsServerChangeTest leak

Scope: current task; the two findings approved by the parent worker (CR-1 from review.md, the optional tearDown timeout hardening from tests.md). Test code only, no production code changed.

## Fixed findings

### CR-1 - AuthRepositoryTest joins the view model only when the assertions pass

Files changed:
- `android/app/src/test/java/ch/sonensei/cockpit/auth/AuthRepositoryTest.kt`

Problem:
In `a logout started from the app still forgets the tokens when its screen goes away during the revocation`, the join of the view model's `viewModelScope` job ran inside the `try` after the `assertNull` checks. When an assertion failed, `finally` ran `resetMain` without waiting for the non-cancellable logout, which could then touch Main after the reset and fail an unrelated later test with `UncaughtExceptionsBeforeTest`.

Fix:
The view model is now also stored in a nullable `var viewModel: SessionViewModel?` declared before the `try`, so `finally` can reach it. The join moved into `finally`, before `Dispatchers.resetMain()`, as `runCatching { runBlocking { withTimeout(5_000) { job.cancelAndJoin() } } }`. `cancelAndJoin` instead of `join`: on the normal path the scope is already cancelled and cancelling again is a no-op, but if the test fails before its own `viewModelScope.cancel()` (for example the DELETE assertion), a plain `join` on the never-cancelled scope would always wait the full 5 s. `runCatching` keeps a join timeout from replacing the original assertion error. The comment moved with the code and now says it also runs after a failed assertion. Import `kotlinx.coroutines.cancelAndJoin` added in lexicographic order (after `cancel`).

Validation:
- Gradle (ktlint, testDebugUnitTest) - not run by the fixer: Gradle is denied for sub-agents; the parent runs it
- `git diff` reviewed; added lines checked to stay within the 140-character ktlint limit - Pass

### tests.md hardening - tearDown cancelAndJoin without a timeout

Files changed:
- `android/app/src/test/java/ch/sonensei/cockpit/settings/SettingsServerChangeTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModelRoomCacheTest.kt`

Problem:
The `runBlocking { viewModels.forEach { ...cancelAndJoin() } }` in both tearDowns had no timeout. A hanging store write would block tearDown until the Gradle or `.taskcheck` timeout instead of failing fast.

Fix:
The loop is now wrapped in `withTimeout(5_000)` inside `runBlocking`. The remaining cleanup (`storeScope.cancel()` and `Dispatchers.resetMain()` in SettingsServerChangeTest; `Dispatchers.resetMain()` and `database.close()` in CalendarViewModelRoomCacheTest) moved into a `finally`, so a timeout still releases Main and the store instead of leaking them into later tests. The comments now mention the timeout. `withTimeout`, `runBlocking` and `cancelAndJoin` were already imported in both files; no import changes.

Validation:
- Gradle (ktlint, testDebugUnitTest) - not run by the fixer: Gradle is denied for sub-agents; the parent runs it
- `git diff` reviewed; added lines checked to stay within the 140-character ktlint limit - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- Not run by the fixer (Gradle denied for sub-agents). Suggested for the parent: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests '*AuthRepositoryTest' --tests '*SettingsServerChangeTest' --tests '*SettingsViewModelTest' --tests '*CalendarViewModelRoomCacheTest'`

Full test suite:
- Not run by the fixer; the parent runs it.

Lint/type-check/build:
- Not run by the fixer; the parent runs `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`.

`.taskcheck`:
- Not run (by the fixer; Gradle denied). The parent runs `sh scripts/check.sh`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed. Validation (ktlint, the Android unit tests, `.taskcheck`) is still to be run by the parent worker, because Gradle is denied for sub-agents.
