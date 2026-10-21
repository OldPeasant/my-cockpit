# Code Review

Date: 2026-10-21

Task: p3-smoke - Phase 3 demo script (emulator)

Scope: `git diff 0bbf2b5c..HEAD` (task commits e33071a7, 6d5a63d7, 3e67705f), committed changes only

Requirements:
- tasks/p3-smoke.md (acceptance criteria and recorded assumptions)
- SPEC.md §13.2 Phase 3, steps 1-5
- docs/ARCHITECTURE.md (Test table, server profiles/test hooks, rules on slow suites)
- AGENTS.md project notes (lint, `.taskcheck` < 2 min, no secrets in logs, fictitious test data)
- Parent agent instructions (correctness, `.taskcheck` unaffected, security of test hooks, maintainability)

Git range:
- Branch: main
- HEAD: 3e67705f9d51e14ba2d5ca37efa11562bb6a692c
- Base/start: 0bbf2b5c70d2ed45a565c1297279b307ad037a95
- Commits reviewed: 3

Summary: 0 P0, 0 P1, 1 P2, 0 P3

Files reviewed in full: `android/app/build.gradle.kts` (changed parts), `android/gradle/libs.versions.toml`,
`android/app/src/androidTest/.../HiltTestRunner.kt`, `.../smoke/Phase3DemoEmulatorTest.kt`,
`android/app/src/sharedTest/.../smoke/Phase3Demo.kt`, `.../smoke/E2eStack.kt`,
`android/app/src/test/.../smoke/Phase3DemoRobolectricTest.kt`, the `docs/ARCHITECTURE.md` changes; context:
`CockpitApp.kt`, `CaptureSync.kt` (worker entry point), `network_security_config.xml`, `TestFilesProvider.kt`,
`deploy/docker-compose.e2e.yml` (default port 18090 on 127.0.0.1).

What was checked and found in order:
- Steps 1-5 of SPEC.md §13.2 are each covered by one step method in `Phase3Demo` (TOTP login through the UI; two
  ACTION_SEND shares plus the CaptureActivity widget path with pending count 3; sync within 60 s, photo bytes compared
  through `/links` and `/files/{id}/content`, re-sync without duplicates; Inbox processing on the phone compared with
  API lists for Next/Today/Projects; reminder created via API and notification asserted by channel and title).
- `.taskcheck` is unaffected: without `-Pe2eBaseUrl` every `Test` task excludes `ch.sonensei.cockpit.smoke.*`;
  `RobolectricDevice` additionally `assumeTrue`s a non-empty base URL. `providers.gradleProperty` is configuration-cache
  safe; `upToDateWhen { false }` only applies when the property is set.
- Test hooks: `/api/test/reset` is only called against the URL given explicitly (Robolectric has no default; the
  emulator default is the e2e stack's port via 10.0.2.2) and the run fails with an assertion on any server without the
  e2e profile, before setup is attempted. Credentials are fictitious and match the web e2e specs; nothing logs tokens
  or secrets (failure messages print the Compose tree and HTTP status only).
- Cleartext to 127.0.0.1 / 10.0.2.2 is allowed by the existing network security config; Hilt workers resolve their
  dependencies through `EntryPointAccessors`, so they also work under `HiltTestApplication`.
- The dependency addition (androidx.test:runner 1.7.0) is one version + one library line and is listed in the
  ARCHITECTURE stack table, as the conventions require. Deviations from `touches` are recorded as assumptions.
- No acceptance-criterion evidence is recorded yet (`tasks.py verify p3-smoke` shows both unticked); the parent must
  record the Robolectric run and mark the emulator part skipped with its reason before `done`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Emulator runner queries the stack while airplane mode is on, so step 2 fails on a real emulator**
    - File: `android/app/src/sharedTest/java/ch/sonensei/cockpit/smoke/Phase3Demo.kt:220`
    - Category: Correctness
    - Problem: `step2CaptureThreeThingsInAirplaneMode` calls `device.setOnline(false)` and then, still offline, asserts
      `stack.tasks(list = "INBOX").isEmpty()`. `E2eStack` makes that HTTP call from the test process, which on the
      emulator runs inside the device. `EmulatorDevice.setOnline(false)` switches on real airplane mode and waits until
      the device has no network with internet capability, so the request to `http://10.0.2.2:...` cannot connect and
      fails with an IOException (or the 30 s call timeout). The Robolectric runner passes only because its
      "offline" is just an unmet WorkManager constraint, while the JVM keeps its network. The emulator variant was
      compiled but never run (recorded assumption), so this was not noticed.
    - Why it matters: `docs/ARCHITECTURE.md` "Android demo script" documents `Phase3DemoEmulatorTest` as a working way
      to run the demo, and it is the variant the acceptance criterion prefers. The first person who runs it on an
      emulator gets a failure in step 2 that is unrelated to the app.
    - Suggested fix: Do not call the stack from the device while it is offline. For example, record the server's Inbox
      state before going offline and check it again right after `setOnline(true)` but before the sync can run, or
      move the check behind a `SmokeDevice` capability (such as `canReachStackOffline`, true on Robolectric only). The
      `pendingCount() == 3` assertion already proves the captures were queued. If the emulator variant stays
      unexecuted, say in the ARCHITECTURE section that it has never been run.

## P3 Low

None.

## Review conclusion

Material issues found; fix before completing the task.
