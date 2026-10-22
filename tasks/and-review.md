---
title: Android weekly review (read-only)
status: in_progress
owner: dell26/agent-1
deps: [and-login,review-api]
touches: [android/app/src/main/java/ch/sonensei/cockpit/review,android/app/src/test/java/ch/sonensei/cockpit/review]
prio: 3
lease_until: 2026-10-22T12:56:48Z
---
## Goal
Review progress on the phone.

## Acceptance criteria
- [ ] Shows current review steps and stalled projects read-only
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.review.*'` passes, covering step rendering (Robolectric/Compose UI tests for screens)

## Notes
