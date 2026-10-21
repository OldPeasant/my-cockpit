# Fix Report

Date: 2026-10-21

Task: and-capture-entrypoints - Android widget, quick-settings tile, app shortcut

Scope: the four findings in `reviews/and-capture-entrypoints/review.md` (CR-1 to CR-4), on top of task commits c1f1f1f2 and bc4ead8f (base 0fd17751)

## Fixed findings

### CR-1 — Widget updater's queue collection is unguarded and runs in every process start

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/capture/entry/CaptureWidgetUpdater.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/capture/entry/CaptureWidgetUpdaterTest.kt`

Problem:
An exception from the upstream queue status flow (Room) was thrown inside `collect`, outside the try/catch around `refresh()`. In the application scope that has no exception handler, so it could crash the process, and the collection never restarted.

Fix:
The upstream is wrapped in `retryWhen`. Every failure except cancellation is retried after an exponential backoff (1 s, 2 s, 4 s, ... capped at 60 s, `retryDelayMillis`), so it never escapes, and collection resumes once the queue can be read again. The per-refresh try/catch is unchanged. New tests: a status flow that throws on its first two reads is retried after 1 s and 2 s of virtual time, then refreshes and follows later changes. A second test checks the backoff values and the cap.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.entry.*'` — Pass

### CR-2 — CaptureActivity is exported although no entry point needs it

Files changed:
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/test/java/ch/sonensei/cockpit/capture/entry/CaptureEntryPointsTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/capture/entry/CaptureEntrySurfacesTest.kt`

Problem:
`CaptureActivity` was `exported="true"`, so any installed app could launch the capture sheet. The launcher starts static shortcuts with the publishing app's identity, so exporting was not needed.

Fix:
Set `android:exported="false"` and reworded the manifest comment. The two test assertions that required `exported == true` now assert `false`: the one in `CaptureEntryPointsTest` named by the review, and the matching one in `CaptureEntrySurfacesTest` (the shortcut test from bc4ead8f). Both tests contradicted the review finding, so I changed them and updated their comments. The shortcut test still checks that the target component exists and belongs to this app.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.*'` — Pass
- I did not check on a device or emulator that the long-press shortcut still opens the sheet. None is available to this fixer. The parent should check it or record an assumption, as the review suggests.

### CR-3 — Tile pending subtitle and Android 14+ launch path are not tested

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/capture/entry/CaptureTileService.kt` (KDoc only)
- `android/app/src/test/java/ch/sonensei/cockpit/capture/entry/CaptureEntryPointsTest.kt`

Problem:
No test covered `onStartListening`. The KDoc said the count is shown "while the shade is open", but the code reads it once.

Fix:
The KDoc now says the count is read once each time the shade opens (listening starts) and is not followed while the shade stays open. I added the Robolectric test `the tile shows the pending captures when the shade opens` (SDK 33, real Hilt graph and Room queue). It queues one capture, sets the tile to `STATE_UNAVAILABLE`, calls `onStartListening`, waits for the Room emission (up to 5 s, idling the main looper), and asserts `STATE_INACTIVE` and the subtitle `1 to sync`. I added no test for the Android 14+ PendingIntent launch path. The review lists that gap only as context, and the existing test comment explains that Robolectric has no System UI to receive the PendingIntent.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.entry.*'` — Pass

### CR-4 — New test library missing from the ARCHITECTURE.md stack table

Files changed:
- `docs/ARCHITECTURE.md`

Problem:
`androidx.glance:glance-appwidget-testing` (version ref `glance` = 1.1.1 in `android/gradle/libs.versions.toml`) was not listed in the Android row.

Fix:
Added "Glance appwidget testing 1.1.1" to the Android row's tests list.

Validation:
- Checked the version against `android/gradle/libs.versions.toml` (`glance = "1.1.1"`) — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.entry.*'` — Pass (no failures in the test results)
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.*' lintDebug` — Pass

Full test suite:
- `sh scripts/check.sh` (`.taskcheck`: server, web and Android lint and unit tests) — Pass

Lint/type-check/build:
- `./gradlew ktlintCheck lintDebug` (in the command above) — Pass

`.taskcheck`:
- Pass (`sh scripts/check.sh` -> "== all checks passed", exit 0). It took more than 10 minutes because other agents' Gradle builds were running on the same host at the same time.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
