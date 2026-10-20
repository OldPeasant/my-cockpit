---
title: Android: share URL to read later
status: todo
owner:
deps: [and-capture-entrypoints,bookmarks-api]
touches: [android/app/src/main/java/ch/sonensei/cockpit/capture/share,android/app/src/test/java/ch/sonensei/cockpit/capture/share]
prio: 3
---
## Goal
Share sheet offers Inbox or Read later (SPEC.md §8.4).

## Acceptance criteria
- [ ] Shared URLs show a choice Inbox / Read later; read-later goes through the offline queue too
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.share.*'` passes, covering the choice routing (Robolectric/Compose UI tests for screens)

## Notes
