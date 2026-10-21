---
title: Android share target
status: in_progress
owner: dell26/agent-4
deps: [and-capture-queue]
touches: [android/app/src/main/java/ch/sonensei/cockpit/capture/share,android/app/src/test/java/ch/sonensei/cockpit/capture/share,android/app/src/main/AndroidManifest.xml]
prio: 1
lease_until: 2026-10-21T01:37:03Z
---
## Goal
Share text, URLs, images and files into the inbox (SPEC.md §4.5).

## Acceptance criteria
- [ ] Intent filters for `text/plain`, `image/*`, `*/*` (single + multiple); a compact capture sheet (edit text, save) writes to the queue
- [ ] Shared content URIs are copied (not referenced) before the activity ends
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.share.*'` passes, covering intent parsing for text, URL, multiple images (Robolectric/Compose UI tests for screens)

## Notes
