---
title: Android weekly review (read-only)
status: in_progress
owner: dell26/agent-1
deps: [and-login,review-api]
touches: [android/app/src/main/java/ch/sonensei/cockpit/review,android/app/src/test/java/ch/sonensei/cockpit/review]
prio: 3
lease_until: 2026-10-22T13:16:34Z
---
## Goal
Review progress on the phone.

## Acceptance criteria
- [x] Shows current review steps and stalled projects read-only
  - verified (2026-10-22 14:55+0200 by dell26/agent-1): ReviewScreenTest (9) + ReviewRouteInAppTest (Hilt/Robolectric, More > Review shows real screen: 8 steps with current step 5, stalled project listed) + ReviewRepositoryCallsTest (only GET status/projects called, never start/move/complete) via cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.review.*' -> 28 tests, 0 failures
- [x] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.review.*'` passes, covering step rendering (Robolectric/Compose UI tests for screens)
  - verified (2026-10-22 14:55+0200 by dell26/agent-1): cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.review.*' on 1abe0c84a -> BUILD SUCCESSFUL, 28 tests 0 failures (Compose UI screen tests for step rendering done/current/ahead, Robolectric in-app route); coverage review pkg 245/250 lines

## Notes
- ASSUMPTION (2026-10-22 14:41+0200 by dell26/agent-1): Android review is read-only: shows last-review age, the open review's step (all 8 steps marked done/current/ahead) and only the stalled projects from the projects step; it never starts or moves a review (SPEC §9.2 'weekly review (read-only first)'). Strings live in res/values/strings_review.xml (outside touches, per-feature file like other features).
