---
title: Android weekly review (read-only)
status: in_progress
owner: dell26/agent-1
deps: [and-login,review-api]
touches: [android/app/src/main/java/ch/sonensei/cockpit/review,android/app/src/test/java/ch/sonensei/cockpit/review]
prio: 3
lease_until: 2026-10-22T13:01:24Z
---
## Goal
Review progress on the phone.

## Acceptance criteria
- [ ] Shows current review steps and stalled projects read-only
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.review.*'` passes, covering step rendering (Robolectric/Compose UI tests for screens)

## Notes
- ASSUMPTION (2026-10-22 14:41+0200 by dell26/agent-1): Android review is read-only: shows last-review age, the open review's step (all 8 steps marked done/current/ahead) and only the stalled projects from the projects step; it never starts or moves a review (SPEC §9.2 'weekly review (read-only first)'). Strings live in res/values/strings_review.xml (outside touches, per-feature file like other features).
