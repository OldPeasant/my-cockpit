---
title: Android weekly review (read-only)
status: todo
owner:
deps: [and-login,review-api]
touches: [android/app/src/main/java/ch/sonensei/cockpit/review,android/app/src/test/java/ch/sonensei/cockpit/review]
prio: 3
---
## Goal
Review progress on the phone.

## Acceptance criteria
- [ ] Shows current review steps and stalled projects read-only
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.review.*'` passes, covering step rendering (Robolectric/Compose UI tests for screens)

## Notes
