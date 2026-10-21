# Code Review

Date: 2026-10-21

Task: p3-smoke - Phase 3 demo script (emulator)

Scope: `git diff 0a2260658bf4f25f36e310b05d28b6fec3d087da...HEAD` (task commits 0924a3bc, 11e4da0f, a387f825, cdd61bab, 2bdf5cbd plus the merge commits dfc7cd8b and 004bbe4c that bring them in), committed changes only. This replaces the earlier review of revision 3e67705f.

Requirements:
- tasks/p3-smoke.md (acceptance criteria and recorded assumptions)
- SPEC.md §13.2 Phase 3, steps 1-5
- docs/ARCHITECTURE.md (Test table, "Android demo script", rules on slow suites)
- AGENTS.md project notes (lint, `.taskcheck` < 2 min, no secrets in logs, fictitious test data)
- Parent agent instructions (check the CR-1 fix and the stronger step 3, review the final code in full)

Git range:
- Branch: main
- HEAD: 004bbe4c3bb6e6e5380048acead50290284e0e0d
- Base/start: 0a2260658bf4f25f36e310b05d28b6fec3d087da
- Commits reviewed: 7 (5 task commits, 2 merges of `origin/wip/p3-smoke`)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Files read in full: `android/app/src/sharedTest/.../smoke/Phase3Demo.kt`, `.../smoke/E2eStack.kt`,
`android/app/src/androidTest/.../smoke/Phase3DemoEmulatorTest.kt`, `.../HiltTestRunner.kt`,
`android/app/src/test/.../smoke/Phase3DemoRobolectricTest.kt`, plus the diffs of `android/app/build.gradle.kts`,
`android/gradle/libs.versions.toml` and `docs/ARCHITECTURE.md`. Context: `capture/queue/CaptureQueueDatabase.kt` (DAO
ordering, `insert`, `remoteFileId`) and the app manifest and `CockpitApp.kt`. Neither customizes WorkManager
initialization, so the default initializer also works under `HiltTestApplication`.

What I checked:
- **CR-1 from the previous review is fixed.** `SmokeDevice.stackReachableOffline` (Phase3Demo.kt:87) is `false` for
  `EmulatorDevice` and `true` for `RobolectricDevice`. Step 2 now calls the stack only when that flag is true
  (Phase3Demo.kt:230-232). I found no other stack call between `setOnline(false)` and `setOnline(true)`: the shares,
  the widget sheet, `pendingCount()` (local Room) and `captureDao.sendable()` (local) run on the device only. The
  emulator run no longer checks "nothing reached the server offline". That is acceptable: on a real device WorkManager
  sends the queue as soon as the network returns, so there is no window to check it. Step 3 still checks that exactly
  the 3 queued client ids were created (`queued.map { it.capture.id } == inbox ids`) and that there are 4 tasks in
  total.
- **Step 3 (cdd61bab) is correct.** `sendable()` orders by `createdAtEpochMs, id`, so `queued` is in capture order, and
  the id comparison is valid. Re-inserting the captures with `remoteFileId = photoFile` simulates a lost answer after
  the upload. The test then checks that the server deduplicates on client id (same Inbox ids, 4 tasks in total) and
  that the photo is linked once. The `captureWorkFinished()` wait could pass early (no work info yet, or the old
  finished work), but the queue-empty wait after it covers that. The shared URL in `notesMd` and the photo bytes
  through `/links` and `/files/{id}/content` match SPEC.md §13.2 step 3.
- **Steps 1, 2, 4 and 5 still match the spec and the recorded assumptions:** TOTP login through the UI, then an API
  stand-in login with a recovery code. Step 4 compares Next, Today and Projects with the API lists, including that
  "Holiday ideas" is the new project's step. In step 5 the reminder is created through the API, the alarm time is
  checked exactly on Robolectric and the real clock is used on the emulator.
- **`.taskcheck` is not affected.** Without `-Pe2eBaseUrl`, `ch.sonensei.cockpit.smoke.*` is excluded from every
  `Test` task, and `RobolectricDevice.setUp` also calls `assumeTrue` on the base URL.
- **Security:** `/api/test/reset` is only called on the URL given explicitly, and it fails on stacks without the e2e
  profile. Credentials are fictitious, and no tokens or secrets are logged.
- **Re-run during this review:** `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.smoke.*'
  -Pe2eBaseUrl=http://127.0.0.1:18390/` on HEAD 004bbe4c gave 1 test, 0 skipped, 0 failures, 29.9 s
  (`TEST-ch.sonensei.cockpit.smoke.Phase3DemoRobolectricTest.xml`). The emulator variant was not run because this
  machine has no emulator.
- **Evidence status:** `tasks.py verify p3-smoke` still shows both criteria unticked. Before `done`, the parent must
  record the Robolectric run for criterion 1 together with `--skip` and the reason for the emulator part, and record
  criterion 2.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The ARCHITECTURE.md demo section presents the emulator runner as working, but it has never run**
    - File: `docs/ARCHITECTURE.md:165`
    - Category: Quality
    - Problem: The "Android demo script" section describes `Phase3DemoEmulatorTest` and its run commands without any
      caveat. The task's recorded assumption says this variant compiles (`compileDebugAndroidTestKotlin`) but has
      never run on an emulator. The previous review suggested noting this in the docs if the variant stayed
      unexecuted; that was not done.
    - Why it matters: The emulator path depends on behavior no test has exercised (real airplane mode through
      `cmd connectivity`, `appops` for exact alarms, MediaStore inserts, cleartext to 10.0.2.2 from the instrumentation
      process). The first person to run it may hit a problem and assume their own setup is at fault.
    - Suggested fix: Add one sentence to the emulator bullet, for example "not yet run on an emulator (see task
      p3-smoke); report problems as a task".

## Review conclusion

No material issues found.
