# Code Review

Date: 2026-10-21

Task: and-capture-entrypoints - Android widget, quick-settings tile, app shortcut

Scope: final task code, `git diff 7a64d7b7..2e9114bf` (task commits 53263d6f, 36971f0e, ab34893a, 2e9114bf), excluding `tasks/` and `reviews/`. Committed changes only. Reviewed revision: 2e9114bf.

Requirements:
- tasks/and-capture-entrypoints.md (acceptance criteria and recorded assumptions)
- SPEC.md §4.5 (Android quick capture: widget, quick-settings tile, app shortcut, offline) and §9.3 (offline-first capture, pending count)
- docs/ARCHITECTURE.md (Android stack table, conventions)
- AGENTS.md (lint, assumptions, stack table updates, touches)
- Previous review (CR-1 to CR-4) and reviews/and-capture-entrypoints/fixes.md

Git range:
- Branch: main
- HEAD: 2e9114bf
- Base/start: 7a64d7b72f3d3ad1c3defc5565ab0e66ab3523d9
- Commits reviewed: 4

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Previous findings

I checked each fix against the code at 2e9114bf:

- CR-1 (widget updater's queue collection unguarded): fixed. In `CaptureWidgetUpdater.kt:49-66` the upstream is wrapped in `retryWhen`, with exponential backoff capped at 60 s (`retryDelayMillis`). Cancellation is not retried. The per-refresh try/catch rethrows `CancellationException`. `CaptureWidgetUpdaterTest` covers a status flow that throws on its first reads, recovery after the backoff, and the backoff values including `Long.MAX_VALUE`. The shift is capped at 6 before shifting, so it cannot overflow.
- CR-2 (CaptureActivity exported): fixed. `AndroidManifest.xml:57` has `exported="false"`. Both tests now assert `false`. The launcher starts static shortcuts as the publisher (`LauncherApps.startShortcut` -> start as package), so the shortcut does not need the activity exported. Whether that works on a real device is recorded as an assumption and was not checked here (no device).
- CR-3 (tile subtitle untested, KDoc overstated): fixed. The KDoc in `CaptureTileService.kt:19-23` now describes a one-time read. `onStartListening` is covered by `CaptureEntryPointsTest` ("1 to sync") and `CaptureEntrySurfacesTest` ("2 to sync", and no subtitle when everything is synced). The Android 14+ PendingIntent path is still untested because Robolectric has no System UI. That limit is documented in the test.
- CR-4 (stack table): fixed. `docs/ARCHITECTURE.md` lists "Glance appwidget testing 1.1.1", which matches `glance = "1.1.1"`.

## Acceptance criteria

- Criterion 1: the widget (`CaptureWidget`, pill with `actionStartActivity` to `CaptureActivity`), the tile (`CaptureTileService.onClick`, with `unlockAndRun` on the lock screen) and the static shortcut (`res/xml/shortcuts.xml`, declared on the launcher activity `MainActivity`) all open `CaptureActivity`. `CaptureActivity` reuses `CaptureSheet` and `ShareViewModel.save` -> `CaptureQueue.capture`, which writes to Room first and then schedules the upload (§9.3). The widget shows the pending count from `CaptureQueue.status`. It updates live during a session, and `CaptureWidgetUpdater` refreshes it otherwise. The widget has no editable field (Glance cannot host one), so the "text field" opens the sheet. That deviation is recorded as an assumption in the task. The implementation is complete as far as code review can tell. No on-device check is possible here.
- Criterion 2: the tests under `ch.sonensei.cockpit.capture.entry` cover the widget content for 0, N and failed counts (`CaptureWidgetTest`), the real `provideGlance` session reading the Room queue and redrawing live, including pre-Android-12 colours (`CaptureEntrySurfacesTest`), the capture sheet flow (focus, save -> queued and counted, cancel), the tile (click, locked click, subtitle), the shortcut XML, manifest registration, and the updater (dedupe, double start, refresh failure, read failure with backoff). This is sufficient for the criterion. I did not run the suite myself, because a concurrent test-writer is using the same Gradle build.
- Evidence: when this review was written, `python tasks.py verify and-capture-entrypoints` showed no evidence for either criterion. The parent must record evidence for criterion 2 (the command and its result) and mark criterion 1's on-device part as skipped with a reason, as its notes plan.

Also checked and found correct: the `targetPackage` `ch.sonensei.cockpit` resolves because there is no debug `applicationIdSuffix`. `minSdk` 29 covers `Tile.subtitle` and the `unlockAndRun`/`isLocked` APIs. The tile's coroutine scope is cancelled in `onDestroy`. The widget receiver is exported, as AppWidget broadcasts require. The tile service is protected by `BIND_QUICK_SETTINGS_TILE`. `CaptureActivity` reads nothing from its intent. `autoFocus` defaults to `false`, so the share target's behaviour is unchanged, and a test covers that. No secrets are added and nothing new is logged. Edits outside `touches` (`CockpitApp`, `CaptureSheet`, resources, the test dependency, the ARCHITECTURE.md line) are small and recorded as assumptions.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Tile's queue read has no exception guard: a database failure crashes the app when the shade opens**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/entry/CaptureTileService.kt:28-39`
    - Category: Correctness
    - Problem: `onStartListening` launches `queue.status.first()` in `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`, with no try/catch and no `CoroutineExceptionHandler`. If the Room read fails (the database cannot be opened, a migration error, a full disk), the exception is uncaught on the main thread and kills the app process. Opening the quick-settings shade binds the tile, so this happens even when the user is not using the app. The previous CR-1 fix guards the same flow in `CaptureWidgetUpdater` for this reason. The tile is the one remaining path without that guard.
    - Why it matters: an optional status subtitle can crash the app from System UI. The impact is low because it only happens when the local database is already broken.
    - Suggested fix: wrap the read in `try { ... } catch (e: CancellationException) { throw e } catch (_: Exception) { ... }`. On failure, still set `STATE_INACTIVE` with no subtitle, so the tile stays usable. Alternatively, add a `CoroutineExceptionHandler` to the scope. Add a Robolectric or unit test with a queue whose status throws, if practical.

## Review conclusion

No material issues found. All four earlier findings are fixed in the code. The remaining finding, CR-1, is a P3 cleanup. Before running `done`, the parent still has to record the evidence for the acceptance criteria.
