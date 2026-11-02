---
title: Flaky tests: BackupWebTests progress step and SettingsServerChangeTest leak
status: in_progress
owner: dell26/agent-1
deps: [backup-core,and-settings]
touches: [server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupWebTests.kt,android/app/src/test/java/ch/sonensei/cockpit/settings/SettingsServerChangeTest.kt]
prio: 1
lease_until: 2026-11-02T05:12:56Z
extended: 
releases: 1
---
## Goal
Two CI runs on main failed on one flaky test each, unrelated to the task that triggered them (2026-11-01):
- run 41355157150 (done: and-dashboard), server fast check: `BackupWebTests > progress is announced as BACKUP
  change events while the export runs()` -> `expected:<controllable: data> but was:<bookmarks: data>`
  (BackupWebTests.kt:184). `awaitEvent(id, UPDATED)` returns on the first UPDATED event of any step, then the test
  expects the gated step. `BackupEventsStreamTests` (lines 91-95) already loops until the step is `GATED_STEP`.
- run 41352217885 (done: web-tests-load-robust), android fast check: `SettingsViewModelTest > sync now enqueues
  ...` failed with `UncaughtExceptionsBeforeTest`; the suppressed cause is `Dispatchers.Main was accessed ... after
  Dispatchers.resetMain()` from `SettingsViewModel.saveServer -> loadUser` resumed on the DataStore IO thread. It
  leaked from `SettingsServerChangeTest` (ran just before): `changeServerTo` stops waiting when `serverForm` becomes
  null, but `saveServer` then calls `loadUser()`, which launches on Main while `tearDown` cancels the scopes and
  resets Main.
Test-only fixes; no production code change unless the race is a real app bug (then record why).

## Acceptance criteria
- [ ] `BackupWebTests` progress test waits (with deadline) until the job's step is the gated step instead of the
      first UPDATED event, as `BackupEventsStreamTests` does; `cd server && ./gradlew test --tests
      '*BackupWebTests*'` passes 20 times in a row (e.g. a loop, or `@RepeatedTest` temporarily), also under load
      (parallel `stress-ng` or a second Gradle build); record the load
- [ ] `SettingsServerChangeTest` waits until the save is fully finished (incl. the user reload) before the test
      ends, or its tearDown waits for the ViewModels' work (cancel and join) before `resetMain`, so no coroutine
      touches Main afterwards; demonstrated by running `SettingsServerChangeTest` and `SettingsViewModelTest`
      together 20 times (`--tests` both, `--rerun-tasks` in a loop) -> 0 failures, also under load
- [x] Search the other Android tests that use a real `PreferenceDataStoreFactory` / `Dispatchers.IO` store with
      `setMain`/`resetMain` (e.g. auth/passkey tests) for the same pattern; fix those in the same way only if they
      are local test changes, otherwise list them in the notes
  - verified (2026-11-02 05:16+0100 by dell26/agent-1): grep -rln resetMain android/app/src/test | xargs grep -lE 'PreferenceDataStoreFactory|Dispatchers.IO|Room\.' -> only AuthRepositoryTest and SettingsServerChangeTest; both fixed locally (cancelAndJoin with 5s timeout before resetMain), plus CalendarViewModelRoomCacheTest (Room cache resuming on Main, same pattern); no production changes needed
- [ ] `sh scripts/check.sh` passes

## Notes
Found by the owner's request to look into the failing CI checks (2026-11-02). The CI job of run 41355157150 was
re-run to confirm the failure is flaky.
- ASSUMPTION (2026-11-02 05:12+0100 by dell26/agent-1): No production change: the Settings/Auth races only exist because tests reset Dispatchers.Main while view-model work still runs; in the app Main always exists. Fixed the same pattern locally in AuthRepositoryTest and CalendarViewModelRoomCacheTest (outside the declared touches, test-only). Load generated with 16 busy shell loops on 16 cores (stress-ng not installed); loops use the test task's --rerun instead of --rerun-tasks to avoid recompiling 20 times.
- extended by dell26/agent-1: Code fixes and re-review done (0 findings, review.md/tests.md/fixes.md committed on final code 686c12184's parent). Left: run BackupWebTests 20x under load (crit 1), Android loop SettingsServerChangeTest+SettingsViewModelTest+AuthRepositoryTest+CalendarViewModelRoomCacheTest 20x --rerun-tasks (crit 2), sh scripts/check.sh (crit 4), record verify, write quality.json (reviewed_revision=ee2e0e04c full hash), then done.
- release by dell26/agent-1: Implementation done and committed (64d7b9c4a, fa6d77459 incl. CR-1 fix + tearDown timeouts); test-writer + code-reviewer re-ran on final code (reviews/ committed, no open findings; tests.md coverage N/A, test-only change). Criterion 3 verified. LEFT: (1) run 'cd server && ./gradlew test --tests *BackupWebTests* --rerun-tasks' 20x under load (stress-ng or 2nd Gradle build), verify #1 with load; (2) run android 'testDebugUnitTest --tests *SettingsServerChangeTest* --tests *SettingsViewModelTest* (+AuthRepositoryTest, CalendarViewModelRoomCacheTest) --rerun-tasks' 20x under load, verify #2; (3) sh scripts/check.sh, verify #4; (4) write+commit reviews/<id>/quality.json (reviewed_revision = HEAD hash, result exactly 'pass', fixer ran for CR-1 + tests.md hardening); (5) tasks.py done.
