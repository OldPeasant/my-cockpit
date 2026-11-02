# Code Review

Date: 2026-11-02

Task: fix-flaky-backup-progress-and-settings-tests - Flaky tests: BackupWebTests progress step and SettingsServerChangeTest leak

Scope: final task code, `git diff 9e0bb0de7..e94a8284c` restricted to code (commits f1da8ec9e, 539b921d7, e94a8284c), committed changes only. The commits in between touch only `reviews/`. Re-review on the final revision after the ktlint-format commit e94a8284c.

Requirements:
- tasks/fix-flaky-backup-progress-and-settings-tests.md (goal, acceptance criteria 1-4, notes and recorded assumption)
- AGENTS.md (task protocol, evidence rules, project conventions)
- Parent instructions: review the final revision; the earlier CR-1 is fixed in 539b921d7; e94a8284c is a ktlint-only change

Git range:
- Branch: main
- HEAD: e94a8284c5b4dc99210cafe18f3db9dac7ecb67e
- Base/start: 9e0bb0de7b474934e04c17d6ca4b95fbffaf37cd (merge-base with origin/main)
- Commits reviewed: 3 code commits (f1da8ec9e, 539b921d7, e94a8284c), plus 5 commits that touch only reviews

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

- Criterion 1 (BackupWebTests, server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupWebTests.kt:177-207): the test no longer stops at the first UPDATED event. `awaitGatedStepEvent` polls with a 5 s deadline until the job's `step` is `GATED_STEP` and at least `stepsDone + 1` UPDATED events were recorded for that job id. This is the same rule `BackupEventsStreamTests` uses (lines 89-98; that test uses `==`, this one uses `>=`, which is the same here because the gate holds the export). `recorder.events` is a `CopyOnWriteArrayList`, so counting it while the export thread adds events is safe. Events are filtered by job id, so events left over from earlier tests in the shared context do not count. The original intent ("progress must already have been delivered while the export is held") is still checked: if the event never arrives, the deadline fails the test. `GATED_STEP` is a constant in the companion object instead of a repeated literal.
- Criterion 2 (SettingsServerChangeTest.kt:67-85): tearDown now cancels and joins every view model's `viewModelScope` job under `withTimeout(5_000)` before `storeScope.cancel()` and `Dispatchers.resetMain()`. Both of those run in `finally`, so a join timeout still frees Main and the store and fails the test. A save that resumes on the DataStore IO thread is a child of that job, so the join waits for it. A `loadUser()` launched after cancellation starts on a cancelled scope and does not run its body. Every view model is created through `viewModel()`, so all of them are joined. The view models' scopes are read while Main is still set, so reading them in tearDown is safe.
- Criterion 3: the extra fixes are local test changes for the same pattern, which the criterion allows. They are outside the task's declared `touches`, and the task notes record this as an assumption. AuthRepositoryTest keeps the view model in a nullable variable and joins in `finally` with `runCatching`, so a join timeout cannot hide the original assertion error (the earlier CR-1 is resolved). CalendarViewModelRoomCacheTest joins its view models, created through `viewModel()`, before `resetMain()` and `database.close()`. No production code changed, which matches the recorded assumption that the race only exists because the tests reset Main.
- e94a8284c only splits `it.viewModelScope.coroutineContext.job.cancelAndJoin()` across two lines in two files. Behavior is unchanged. All needed imports (`cancelAndJoin`, `job`, `withTimeout`, `runBlocking`, `viewModelScope`) are present.
- Gradle is denied for sub-agents, so I could not run it here. The parent reports `./gradlew testDebugUnitTest --tests '*SettingsServerChangeTest' --tests '*CalendarViewModelRoomCacheTest'` passing and `./gradlew ktlintCheck` clean on this revision.
- Evidence: criteria 1-3 have recorded verify entries. The 20-run load loops for criteria 1 and 2 ran on an earlier revision (18cd7e1ad). That commit is not available in this clone, so I could not diff it. The only code change after the earlier review is the formatting-only e94a8284c. Criterion 4 (`sh scripts/check.sh`) is still unverified. The parent must run it and record it before `done`. This is a pending step, not a defect.
