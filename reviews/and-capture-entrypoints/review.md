# Code Review

Date: 2026-10-21

Task: and-capture-entrypoints - Android widget, quick-settings tile, app shortcut

Scope: final task code, `git diff e477f621..f321300e -- . ':!tasks' ':!reviews'` (task commits 566da0ac, 9847196e, 5bf44b1d, 5ddceb06, 23dc24c8, f321300e). Committed changes only. Reviewed revision: f321300e2d8b25c85b102aa7b00bb5bcdfe23ed8.

Requirements:
- tasks/and-capture-entrypoints.md (acceptance criteria and recorded assumptions)
- SPEC.md §4.5 (Android quick capture: widget, quick-settings tile, app shortcut) and §9.3 (offline-first capture queue, pending count)
- docs/ARCHITECTURE.md (Android stack table, conventions)
- AGENTS.md (lint, assumptions, stack table updates, touches)
- Earlier review findings and reviews/and-capture-entrypoints/fixes.md; test report reviews/and-capture-entrypoints/tests.md

Git range:
- Branch: main
- HEAD: f321300e2d8b25c85b102aa7b00bb5bcdfe23ed8
- Base/start: e477f62132485ebf97608a3310f0095d7477a444
- Commits reviewed: 6 task commits (plus the merge c6650a92 and the review-only commit 355dfa82)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Previous findings

I checked each earlier fix against the code at f321300e:

- First-round CR-1 (widget updater's queue collection unguarded): still fixed. `CaptureWidgetUpdater.kt` wraps the upstream in `retryWhen`. The backoff is exponential and capped at 60 s, and cancellation is not retried. The per-refresh `try/catch` rethrows `CancellationException`. `CaptureWidgetUpdaterTest` covers read failure with backoff, refresh failure, double start, dedupe, cancellation between refreshes and cancellation during a refresh (the last one is new in f321300e), plus the backoff values including `Long.MAX_VALUE`.
- First-round CR-2 (CaptureActivity exported): still fixed. `AndroidManifest.xml` has `exported="false"`, and the tests assert that. Whether the launcher can start the static shortcut on a device is recorded as an assumption. It cannot be checked here.
- First-round CR-3 (tile subtitle untested, KDoc overstated): still fixed. The KDoc describes a one-time read when listening starts. The subtitle is tested for "1 to sync", "2 to sync", none, and "not synced" after a failed upload.
- First-round CR-4 (stack table): still fixed. ARCHITECTURE.md lists "Glance appwidget testing 1.1.1", which matches `glance = "1.1.1"`.
- Second-round CR-1 (P3, tile queue read unguarded): not addressed. The commits since then (23dc24c8, f321300e) only add tests. It is carried over as CR-1 below.

## Acceptance criteria

- Criterion 1: all three entry points open `CaptureActivity`. The widget pill uses `actionStartActivity`. The tile uses `onClick`, with `unlockAndRun` when the device is locked, through `TileServiceCompat`/`PendingIntentActivityWrapper` so it also works on Android 14+. The static shortcut is in `res/xml/shortcuts.xml`, referenced from the launcher activity. `CaptureActivity` reuses `CaptureSheet` and `ShareViewModel.save`, which queues to Room first (offline-first, §9.3). `load()` is never called, so the sheet starts empty. The widget shows the pending count from `CaptureQueue.status`, live during a Glance session and refreshed otherwise by `CaptureWidgetUpdater`, which `CockpitApp.onCreate` starts. The widget has no editable field; the recorded assumption explains that Glance cannot host one. Code-wise, the criterion is met. Behaviour on a real device or launcher cannot be checked here.
- Criterion 2: the tests in `ch.sonensei.cockpit.capture.entry` (35 tests, 5 classes, per tests.md) cover:
  - the widget content for none, N pending and failed;
  - the real `provideGlance` session reading the Room queue, live updates, and rendering before Android 12;
  - the sheet: focus, save leading to a queued and counted capture, cancel;
  - the tile: click, locked click, subtitle;
  - the shortcut XML and resources;
  - manifest registration;
  - the updater.

  Together they satisfy "covering widget state with pending count". I did not run the suite myself; tests.md records a pass at c6650a92 plus the f321300e test file.
- Evidence: `python tasks.py verify and-capture-entrypoints` shows both criteria unrecorded at the time of this review. Before `done`, the parent must record the command and result for criterion 2. For criterion 1, it must record the Robolectric evidence and skip the on-device part with a reason.

I also checked the following and found them correct:

- `targetPackage` `ch.sonensei.cockpit` matches the application id.
- minSdk 29 covers `Tile.subtitle`, `isLocked` and `unlockAndRun`.
- The tile scope is cancelled in `onDestroy`.
- The widget receiver is exported, as AppWidget broadcasts require.
- The tile service is protected by `BIND_QUICK_SETTINGS_TILE`.
- `CaptureActivity` reads nothing from its intent.
- `autoFocus` defaults to `false`, so the share target's behaviour is unchanged, and a test covers that.
- No secrets are added and nothing new is logged.
- Edits outside `touches` (`CockpitApp`, `CaptureSheet`, resources, the test dependency in `build.gradle.kts`/`libs.versions.toml`, ARCHITECTURE.md) are small and recorded as assumptions.
- The new test dependency is in the stack table.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Tile's queue read has no exception guard: a database failure crashes the app when the shade opens**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/entry/CaptureTileService.kt:28-38`
    - Category: Correctness
    - Problem: `onStartListening` launches `queue.status.first()` (line 33) in `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` (line 28). There is no try/catch and no `CoroutineExceptionHandler`. If the Room read throws (for example the database cannot be opened, a migration fails or the disk is full), the exception is uncaught on the main thread and kills the app process. Opening the quick-settings shade binds the tile, so this can happen while the user is not using the app at all. `CaptureWidgetUpdater` guards the same flow for exactly this reason; the tile is the one remaining reader without that guard. This finding was raised in the previous review and is still open at f321300e.
    - Why it matters: an optional subtitle can crash the app from System UI. The impact is low because it only happens when the local database is already broken.
    - Suggested fix: wrap the read in `try { ... } catch (e: CancellationException) { throw e } catch (_: Exception) { ... }`. On failure, still set `STATE_INACTIVE` with no subtitle so the tile stays usable. Alternatively, give the scope a `CoroutineExceptionHandler`. Add a test with a status flow that throws, if practical. Or record a disposition in quality.json if the parent decides not to fix it.

## Review conclusion

No material issues found. All earlier first-round fixes still hold at f321300e. The one remaining finding, CR-1, is a P3 that was carried over from the previous review. Before running `done`, the parent must still record the acceptance-criteria evidence and a disposition for CR-1.
