# Test Report

Date: 2026-10-21

Task: and-capture-entrypoints - Android widget, quick-settings tile, app shortcut

Scope: task diff `git diff e477f62132485ebf97608a3310f0095d7477a444..c6650a920f4b6215e182a776da94f880c1f2128b -- . ':!tasks' ':!reviews'` (branch `main`, HEAD `c6650a92`, merge of `origin/wip/and-capture-entrypoints`). Task code commits named by the parent: 566da0ac, 9847196e, 5bf44b1d, 5ddceb06, 23dc24c8 (plus review commit 355dfa82). Uncommitted at measurement: only this report and the one test-file change listed below.

Requirements:
- `tasks/and-capture-entrypoints.md` acceptance criteria and recorded assumptions (widget pill opens the capture sheet instead of an in-widget text field; badge "N to sync" / "N not synced"; CaptureWidgetUpdater started from CockpitApp; CaptureActivity not exported)
- `SPEC.md` §4.5 (more ways to capture)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- Parent instructions (focused test command, ktlint, coverage for `capture/entry`)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/capture/entry/CaptureWidgetUpdaterTest.kt`

## Tests added or updated

Count: 1

- `CaptureWidgetUpdaterTest.cancelling the scope during a running refresh stops the updates`: cancels the updater's scope while a (suspending) widget refresh is in flight and checks that the cancellation is not swallowed by the "a failing refresh does not stop later refreshes" handler: no further refresh after a queue change, and no live child job. The existing cancellation test only cancels between refreshes, so the `catch (e: CancellationException) { throw e }` path (CaptureWidgetUpdater.kt:58-60) was not exercised.

Tried and removed: a test that a destroyed `CaptureTileService` no longer updates the tile. Robolectric cannot run `TileService.onDestroy` (`ClassCastException: Cannot cast ShadowTileService to ShadowService` inside `android.app.Service.onDestroy`, a Robolectric shadow limitation, not an app defect). See "Unable to test".

The existing suite (35 tests in `capture.entry`, plus `capture.share.CaptureSheetTest`) already covers the acceptance criteria well; no other real gaps were found.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Glance widget: pill opens the capture sheet; capture is queued offline-first | `CaptureWidgetTest.a tap on the pill opens the capture sheet`, `CaptureEntryPointsTest.the capture sheet opens empty with the title focused`, `...a capture is queued and counted as pending for the widget`, `...cancel closes the sheet without queueing`, `CaptureEntryQueueStatesTest.saving a capture confirms it and closes the sheet` | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.entry.*'` | Pass |
| AC1 Widget pending count (none / N to sync / N not synced, live and placed, pre-Android 12) | `CaptureWidgetTest` (7 tests), `CaptureEntrySurfacesTest.the placed widget reads the pending count from the queue`, `...a shown widget follows the queue while its session runs`, `...the widget renders its count before Android 12 too`, `...the placed widget shows no count while nothing waits`, `CaptureEntryQueueStatesTest.the placed widget says not synced after a failed upload`, `...a shown widget drops its count once the last capture was sent` | same | Pass |
| AC1 Widget count kept fresh (CaptureWidgetUpdater) | `CaptureWidgetUpdaterTest` (8 tests incl. new one) | same | Pass |
| AC1 Quick-settings tile opens the capture sheet (also locked) and shows pending count | `CaptureEntryPointsTest.the tile opens the capture sheet in a new task` (SDK 33), `...the tile shows the pending captures when the shade opens`, `CaptureEntrySurfacesTest.a tap on the locked device unlocks first, then opens the capture sheet`, `...the tile subtitle shows how many captures wait`, `...the tile has no subtitle when everything is synced`, `CaptureEntryQueueStatesTest.the tile subtitle says not synced after a failed upload` | same | Pass (Android 13 Intent path only; Android 14+ PendingIntent path Not run: see below) |
| AC1 Static app shortcut opens the capture sheet | `CaptureEntrySurfacesTest.the static shortcut opens the capture sheet of this app`, `...the shortcut labels and icon are resources that exist`, `CaptureEntryPointsTest.the manifest registers the widget, the tile and the shortcut` | same | Pass (resource/manifest level; launcher behaviour on a device Not run: no emulator) |
| AC1 On a real device/launcher | none | none | Not run: no emulator/device in this environment |
| AC2 focused command passes, covering widget state with pending count | all of the above (35 tests, 5 classes) | same | Pass |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.entry.*' createDebugUnitTestCoverageReport`

Result:

Pass - BUILD SUCCESSFUL (06:54:02-06:55:34 +02:00); 35 tests, 0 failures, 0 skipped (CaptureEntrySurfacesTest 10, CaptureEntryPointsTest 6, CaptureWidgetUpdaterTest 8, CaptureWidgetTest 7, CaptureEntryQueueStatesTest 4); ktlintCheck passed.

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.*' createDebugUnitTestCoverageReport` (whole capture feature: entry, queue, share)

Result:

Pass - BUILD SUCCESSFUL (06:55:50-06:57:40 +02:00); 166 tests, 0 failures, 0 skipped, 0 errors.

## .taskcheck

Result:

Pass - `sh scripts/check.sh` (server, web, android: lint + unit tests) exited 0, "== all checks passed", 06:57:45-07:01:44 +02:00 (about 4 min: above the 2 min warning, well below the 15 min limit). The web build prints a pre-existing bundle budget warning unrelated to this task.

## Bugs found

None.

## Coverage

Status: verified (whole-file JaCoCo coverage of the task-changed Android production files).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (JaCoCo XML has no diff intersection here; all four `capture/entry` files are new in this task, so for them whole-file equals changed-file coverage).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-capture-entrypoints`; base `e477f62132485ebf97608a3310f0095d7477a444` (parent-supplied merge-base); HEAD `c6650a920f4b6215e182a776da94f880c1f2128b`; file scope from `git diff --name-only e477f621..HEAD -- android/app/src/main` matched against the parent's commit list.
- Source/test snapshot identifier (including uncommitted inputs): HEAD c6650a92 plus uncommitted `CaptureWidgetUpdaterTest.kt` sha256 `55ea3ee440d342971d7e4cc0d071d6481c0e66b62506d998054699dd7f5ced0c` (same hash before and after the run; `git status` showed no other change).
- Exact included files: `capture/entry/CaptureActivity.kt`, `CaptureTileService.kt`, `CaptureWidget.kt`, `CaptureWidgetUpdater.kt` (new); `CockpitApp.kt`, `capture/share/CaptureSheet.kt` (modified). Excluded: `AndroidManifest.xml`, `res/xml/shortcuts.xml`, `res/xml/capture_widget_info.xml`, `res/values/strings_capture_entry.xml`, `res/drawable/ic_capture_inbox.xml` (resources, not instrumentable; covered by the manifest/shortcut/resource tests), `build.gradle.kts`, `libs.versions.toml`, `docs/ARCHITECTURE.md` (build/docs). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (Gradle 9.8.1); `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.*' createDebugUnitTestCoverageReport` in `android/`; test selection `ch.sonensei.cockpit.capture.*` (166 tests). The entry-only run (`capture.entry.*`, 06:55:34, report sha256 `0c8737181da247f036db21d22509f9b9640306afe660aafe12f702f2a2184c26`) gives identical numbers for the four entry files; only CaptureSheet.kt is lower there (1018/1455 instr.) because its own tests live in `capture.share`.
- Run start/completion time and exit status: 2026-10-21T06:55:50+02:00 to 06:57:40+02:00, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 `f67097dca5c293b5b75d7e51aafc7d7cfeb4b3874db2e1be7b475f30a887986b` (rewritten by this run; Gradle re-executed the tests since inputs changed).
- Evidence outputs are fresh and inputs stayed unchanged: yes (test-file hash identical before/after; report mtime inside the run window).
- Final run after latest test additions and production fixes: yes (no production change during this session).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. "Statements" = JaCoCo INSTRUCTION counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/capture/entry/CaptureActivity.kt` | 255/255 (100%) | 2/2 (100%) | 7/7 (100%) | 22/22 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/entry/CaptureTileService.kt` | 112/128 (87.5%) | 3/4 (75%) | 7/9 (77.8%) | 19/22 (86.4%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/entry/CaptureWidget.kt` | 567/572 (99.1%) | 14/14 (100%) | 14/14 (100%) | 66/66 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/entry/CaptureWidgetUpdater.kt` | 140/146 (95.9%) | 3/4 (75%) | 8/8 (100%) | 24/25 (96%) |
| **capture/entry aggregate (summed counters, 4 files)** | 1074/1101 (97.5%) | 22/24 (91.7%) | 36/38 (94.7%) | 131/135 (97.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/CockpitApp.kt` | 13/17 (76.5%) | N/A (0 branches) | 3/4 (75%) | 5/5 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/share/CaptureSheet.kt` | 1440/1455 (99.0%) | 30/31 (96.8%) | 21/22 (95.5%) | 121/122 (99.2%) |

No scoped Kotlin file is missing from the report. Before the added test, CaptureWidgetUpdater.kt was 137/146 instructions, 22/25 lines (lines 59-60 uncovered).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CaptureWidgetUpdater.kt:59-60` (rethrow of CancellationException from refresh) | Updater must stop on cancellation, not swallow it as a failed refresh | Covered now: `cancelling the scope during a running refresh stops the updates` |
| `CaptureWidgetUpdater.kt:52` (retryWhen branch `cause is CancellationException`) | A cancellation from the queue flow is not retried | Not added: only reachable if the upstream flow itself throws CancellationException while the scope is alive; scope cancellation is covered by two tests. Low risk |
| `CaptureWidgetUpdater.kt:65` (normal end of the launch block) | None: the queue status flow never completes | Non-material |
| `CaptureTileService.kt:47-49` (`onDestroy` cancels the scope) | No coroutine leak after the tile service is destroyed | Not testable under Robolectric (`TileService.onDestroy` throws a ShadowTileService/ShadowService ClassCastException in the framework shadow). Needs a device/instrumented test if wanted |
| `CaptureTileService.kt:34` (`qsTile == null` early return) | Defensive: tile already unbound when the count arrives | Robolectric always provides a tile; non-material |
| `CaptureTileService.kt` Android 14+ `startActivityAndCollapse(PendingIntent)` path | Tile tap opens the sheet on Android 14+ | Not run: Robolectric lacks System UI for the PendingIntent variant (existing tests use SDK 33). Device check needed |
| `CockpitApp.kt` 1 method / 4 instructions | Generated lateinit accessor, not task behaviour | Non-material; `onCreate` starting the updater is covered |
| `CaptureSheet.kt:220`, branch at 213 | Pre-existing share-sheet code, not the task's `autoFocus` change (both autoFocus values are covered) | Outside task responsibility |

## Unable to test

- Tile, widget and shortcut on a real device/launcher (home-screen placement, long-press shortcut launching the non-exported CaptureActivity, lock-screen unlock): no emulator or device here. Follow-up: manual or instrumented check (already recorded as an assumption in the task).
- Tile tap on Android 14+ (PendingIntent variant) and `CaptureTileService.onDestroy`: Robolectric limitations described above. Follow-up only if an instrumented test suite is added later.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.
