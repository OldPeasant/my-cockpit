---
title: Android widget, quick-settings tile, app shortcut
status: in_progress
owner: dell26/agent-1
deps: [and-share-target]
touches: [android/app/src/main/java/ch/sonensei/cockpit/capture/entry,android/app/src/test/java/ch/sonensei/cockpit/capture/entry,android/app/src/main/AndroidManifest.xml,android/app/src/main/res/xml]
prio: 2
lease_until: 2026-10-21T05:22:27Z
extended: 
releases: 1
---
## Goal
More ways to capture (SPEC.md §4.5).

## Acceptance criteria
- [~] Glance widget (text field → queue, pending count), quick-settings tile and static app shortcut opening the capture sheet
  - skipped (2026-10-21 07:06+0200 by dell26/agent-1): no emulator/device here to check the real launcher widget, QS tile and long-press shortcut; the Robolectric tests cover each one (CaptureEntryPointsTest, CaptureEntrySurfacesTest, CaptureWidgetTest: widget renders the pending count and opens CaptureActivity, the tile opens the capture sheet, shortcuts.xml targets CaptureActivity), 35/35 pass at f321300e
- [x] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.entry.*'` passes, covering widget state with pending count (Robolectric/Compose UI tests for screens)
  - verified (2026-10-21 07:06+0200 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.entry.*' at f321300e -> BUILD SUCCESSFUL, 35 tests (EntryPoints 6, Surfaces 10, WidgetUpdater 8, Widget 7, QueueStates 4), 0 failures; widget pending-count states covered in CaptureWidgetTest/CaptureEntryQueueStatesTest; capture/entry JaCoCo line coverage 97.0%

## Notes
- ASSUMPTION (2026-10-21 05:03+0200 by dell26/agent-2): Widget has no real text field (RemoteViews/Glance cannot host editable text): its pill looks like the capture title field and opens the capture sheet (CaptureActivity), which queues offline-first; badge shows 'N to sync' / 'N not synced', nothing when all synced.
- ASSUMPTION (2026-10-21 05:03+0200 by dell26/agent-2): Widget count stays fresh via CaptureWidgetUpdater started in CockpitApp.onCreate (outside touches, 3 lines): it follows CaptureQueue.status and calls updateAll; captures and uploads happen only in the app process so every change is seen.
- ASSUMPTION (2026-10-21 05:03+0200 by dell26/agent-2): Small edits outside touches: CaptureSheet gets autoFocus param (keyboard opens for empty capture), new res/values/strings_capture_entry.xml and res/drawable/ic_capture_inbox.xml, glance-appwidget-testing test dependency.
- ASSUMPTION (2026-10-21 05:04+0200 by dell26/agent-2): Widget pill corners are rounded only on Android 12+ (Glance cornerRadius); on 10/11 square. QS tile subtitle shows pending count; tapping it on the lock screen unlocks first.
- ASSUMPTION (2026-10-21 05:38+0200 by dell26/agent-2): CaptureActivity is not exported (review CR-2): static shortcuts are started by the launcher with the publisher's identity, so the long-press shortcut should still work; not checked on a device (no emulator here).
- extended by dell26/agent-2: Implementation done and committed (widget, QS tile, shortcut, CaptureActivity, review fixes in 5f85bca7, extra tests 7895be88); focused tests + ktlint pass. Left: run test-writer (reviews/.../tests.md with coverage), re-run code-reviewer on final code (review.md is pre-fix), write quality.json, tasks.py verify 1+2 (criterion 1 device check: --skip, no emulator), run .taskcheck, done.
- release by dell26/agent-2: Implementation done and committed (CaptureActivity, Glance widget with pending-count badge, QS tile, static shortcut, review fixes 2f077e00, extra tests 1067acfb/82d2f250). Focused tests 'ch.sonensei.cockpit.capture.entry.*' pass at 82d2f250. reviews/: review.md (final-code review 6276ec8e) + fixes.md exist; tests.md MISSING. Left: run test-writer (tests.md with coverage), re-run code-reviewer if 82d2f250 test commit makes review stale, write quality.json, tasks.py verify 1 (--skip device check: no emulator; covered by Robolectric) and 2, run .taskcheck, done.
- ASSUMPTION (2026-10-21 07:06+0200 by dell26/agent-1): Criterion 1 on-device behavior (Glance widget on a real launcher, QS tile in System UI, static shortcut with non-exported CaptureActivity) was only checked with Robolectric, not on a device or emulator (none available).
