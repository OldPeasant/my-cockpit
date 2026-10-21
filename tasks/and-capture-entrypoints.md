---
title: Android widget, quick-settings tile, app shortcut
status: in_progress
owner: dell26/agent-2
deps: [and-share-target]
touches: [android/app/src/main/java/ch/sonensei/cockpit/capture/entry,android/app/src/test/java/ch/sonensei/cockpit/capture/entry,android/app/src/main/AndroidManifest.xml,android/app/src/main/res/xml]
prio: 2
lease_until: 2026-10-21T03:48:00Z
---
## Goal
More ways to capture (SPEC.md §4.5).

## Acceptance criteria
- [ ] Glance widget (text field → queue, pending count), quick-settings tile and static app shortcut opening the capture sheet
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.entry.*'` passes, covering widget state with pending count (Robolectric/Compose UI tests for screens)

## Notes
- ASSUMPTION (2026-10-21 05:03+0200 by dell26/agent-2): Widget has no real text field (RemoteViews/Glance cannot host editable text): its pill looks like the capture title field and opens the capture sheet (CaptureActivity), which queues offline-first; badge shows 'N to sync' / 'N not synced', nothing when all synced.
- ASSUMPTION (2026-10-21 05:03+0200 by dell26/agent-2): Widget count stays fresh via CaptureWidgetUpdater started in CockpitApp.onCreate (outside touches, 3 lines): it follows CaptureQueue.status and calls updateAll; captures and uploads happen only in the app process so every change is seen.
- ASSUMPTION (2026-10-21 05:03+0200 by dell26/agent-2): Small edits outside touches: CaptureSheet gets autoFocus param (keyboard opens for empty capture), new res/values/strings_capture_entry.xml and res/drawable/ic_capture_inbox.xml, glance-appwidget-testing test dependency.
- ASSUMPTION (2026-10-21 05:04+0200 by dell26/agent-2): Widget pill corners are rounded only on Android 12+ (Glance cornerRadius); on 10/11 square. QS tile subtitle shows pending count; tapping it on the lock screen unlocks first.
