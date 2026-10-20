---
title: Android widget, quick-settings tile, app shortcut
status: todo
owner:
deps: [and-share-target]
touches: [android/app/src/main/java/ch/sonensei/cockpit/capture/entry,android/app/src/test/java/ch/sonensei/cockpit/capture/entry,android/app/src/main/AndroidManifest.xml,android/app/src/main/res/xml]
prio: 2
---
## Goal
More ways to capture (SPEC.md §4.5).

## Acceptance criteria
- [ ] Glance widget (text field → queue, pending count), quick-settings tile and static app shortcut opening the capture sheet
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.entry.*'` passes, covering widget state with pending count (Robolectric/Compose UI tests for screens)

## Notes
