---
title: Android files
status: in_progress
owner: dell26/agent-1
deps: [and-login,files-preview]
touches: [android/app/src/main/java/ch/sonensei/cockpit/files,android/app/src/test/java/ch/sonensei/cockpit/files]
prio: 3
lease_until: 2026-10-22T11:08:09Z
---
## Goal
Files on the phone (SPEC.md §9.2 item 7).

## Acceptance criteria
- [ ] Browse, download/open with intents (FileProvider), upload from device
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*'` passes, covering browse and upload states (Robolectric/Compose UI tests for screens)

## Notes
