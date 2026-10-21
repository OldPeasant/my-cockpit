---
title: Android widget, quick-settings tile, app shortcut
status: in_progress
owner: dell26/agent-2
deps: [and-share-target]
touches: [android/app/src/main/java/ch/sonensei/cockpit/capture/entry,android/app/src/test/java/ch/sonensei/cockpit/capture/entry,android/app/src/main/AndroidManifest.xml,android/app/src/main/res/xml]
prio: 2
lease_until: 2026-10-21T03:22:54Z
---
## Goal
More ways to capture (SPEC.md §4.5).

## Acceptance criteria
- [ ] Glance widget (text field → queue, pending count), quick-settings tile and static app shortcut opening the capture sheet
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.entry.*'` passes, covering widget state with pending count (Robolectric/Compose UI tests for screens)

## Notes
- ASSUMPTION (2026-10-21 05:03+0200 by dell26/agent-2): Widget has no real text field (RemoteViews/Glance cannot host editable text): its pill looks like the capture title field and opens the capture sheet (CaptureActivity), which queues offline-first; badge shows 'N to sync' / 'N not synced', nothing when all synced.
