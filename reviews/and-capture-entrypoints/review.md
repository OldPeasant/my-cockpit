# Code Review

Date: 2026-10-21

Task: and-capture-entrypoints - Android widget, quick-settings tile, app shortcut

Scope: task commit 2f420e12 on top of base 501eb47f (`git diff 501eb47f..2f420e12`), committed changes only

Requirements:
- tasks/and-capture-entrypoints.md (acceptance criteria and recorded assumptions)
- SPEC.md §4.5 (quick capture: Android widget, quick-settings tile, app shortcut, offline) and §9.3 (offline-first capture)
- docs/ARCHITECTURE.md (Android stack, conventions)
- AGENTS.md (project conventions: lint, assumptions, stack table updates)

Git range:
- Branch: main
- HEAD: 2f420e12
- Base/start: 501eb47f0d9adb3ed95de84a6f92506977db4193
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 1 P2, 3 P3

Notes on acceptance criteria:
- Criterion 1: the widget (`CaptureWidget`), the tile (`CaptureTileService`) and the static shortcut (`res/xml/shortcuts.xml`
  on `MainActivity`) all open `CaptureActivity`, which reuses the share sheet and queues offline first through
  `ShareViewModel.save` -> `CaptureQueue.capture`. Glance cannot host an editable text field, so the widget is a pill
  that opens the sheet. The task notes record this as an assumption, so it is not reported as a finding. The pending
  count is rendered by `CaptureWidgetContent` and kept fresh by the session's own collection plus
  `CaptureWidgetUpdater`.
- Criterion 2: `CaptureWidgetTest` covers widget content with 0, N and failed pending counts, plus the click action.
  `CaptureEntryPointsTest` covers the sheet, queueing, the tile on API 33 and manifest registration. When this review
  was written, `tasks.py verify` showed no evidence recorded for either criterion. The parent must record it.
- Checked and found correct: `applicationId` has no debug suffix, so the shortcut's `targetPackage` resolves.
  `minSdk` 29 covers `Tile.subtitle`. `TileServiceCompat`/`PendingIntentActivityWrapper` (immutable) handles the
  Android 14+ PendingIntent requirement. `unlockAndRun` handles the lock screen. Hilt `@AndroidEntryPoint` works on
  `TileService`, and the tile scope is cancelled in `onDestroy`. The widget receiver is exported, as AppWidget
  broadcasts need. The tile service is guarded by `BIND_QUICK_SETTINGS_TILE`. `CaptureActivity` reads nothing from its
  intent (`ShareViewModel.load` is never called).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Widget updater's queue collection is unguarded and runs in every process start**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/entry/CaptureWidgetUpdater.kt:43`
    - Category: Correctness
    - Problem: `start()` launches `status.distinctUntilChanged().collect { ... }` in `CoroutineScope(SupervisorJob() + Dispatchers.Default)` and sets no `CoroutineExceptionHandler`. The try/catch wraps only `refresh()`. An exception from the upstream Room flow (`CaptureQueueDao.observeStatus()`, e.g. an `SQLiteException`, a failed open or migration, or a full disk) is thrown inside `collect`, outside that try. It is uncaught, and on Android that kills the process. `CockpitApp.onCreate` calls `start()` on every process start, including background starts for the widget broadcast, WorkManager sync jobs and the share target. A database problem would then crash the app in the background instead of only on the screens that use the queue. The collection also never restarts after such an error.
    - Why it matters: an app-wide crash path from an optional widget feature, and silent loss of widget updates after one upstream failure.
    - Suggested fix: guard the upstream as well. Use `.catch { }` plus `retryWhen`/a backoff, or a `CoroutineExceptionHandler` on the scope, so a queue read failure is ignored or retried and never escapes. Add a unit test with a status flow that throws.

## P3 Low

- [ ] **CR-2 | CaptureActivity is exported although no entry point needs it**
    - File: `android/app/src/main/AndroidManifest.xml:52-56` (also asserted in `android/app/src/test/java/ch/sonensei/cockpit/capture/entry/CaptureEntryPointsTest.kt:156-157`)
    - Category: Security
    - Problem: the manifest comment says the activity is "Exported only for the launcher's shortcut". The launcher does not need that: it starts static shortcut intents on behalf of the publishing app (`LauncherApps.startShortcut`), the widget uses the app's own PendingIntent, and the tile starts it from the app's own process. With `exported="true"`, any installed app can launch the capture sheet over other apps. The test pins `exported == true` as a requirement.
    - Why it matters: an unnecessary attack surface. The impact is low, because the activity reads no input from the intent and the user still has to type and save.
    - Suggested fix: set `android:exported="false"`, check on a device or emulator that the long-press shortcut still opens the sheet, and change the comment and the test assertion to match. If the shortcut does need it on some launcher, record that as an assumption.

- [ ] **CR-3 | Tile pending subtitle and Android 14+ launch path are not tested**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/entry/CaptureTileService.kt:29-38, 51-54`
    - Category: Tests
    - Problem: no test covers `onStartListening`, which sets the subtitle from `pendingLabel` and the inactive state. The click is tested only on API 33, the Intent variant. The PendingIntent variant that `TileServiceCompat` picks on API 34-36 has no test. The test comment explains that Robolectric lacks System UI there. The KDoc also says the subtitle shows the count "while the shade is open", but it is a one-time snapshot (`status.first()`) taken when listening starts.
    - Why it matters: the tile's pending count is user-visible behavior with no regression protection, and the KDoc overstates what the code does.
    - Suggested fix: add a Robolectric test that drives `onStartListening` with a queued capture and asserts `qsTile.subtitle` (ShadowTileService/ShadowTile), if practical. Reword the KDoc to say the count is read when the shade opens.

- [ ] **CR-4 | New test library missing from the ARCHITECTURE.md stack table**
    - File: `android/gradle/libs.versions.toml:80`, `docs/ARCHITECTURE.md:13`
    - Category: Quality
    - Problem: `androidx.glance:glance-appwidget-testing` was added as a test dependency, but the Android row's "tests:" list in ARCHITECTURE.md does not name it. AGENTS.md requires: "When you add a tool, runtime or library, update the stack table".
    - Why it matters: the documented stack drifts from the build.
    - Suggested fix: add "Glance appwidget testing 1.1.1" to the tests list in the Android row. This is a one-line shared-doc edit.

## Review conclusion

Material issues found; fix before completing the task. CR-1 should be fixed. CR-2 to CR-4 are low-priority cleanups.
