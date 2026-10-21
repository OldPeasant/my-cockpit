---
title: Android inbox processing
status: in_progress
owner: dell26/agent-4
deps: [and-gtd-data]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox,android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox]
prio: 1
lease_until: 2026-10-21T07:58:58Z
---
## Goal
Process the inbox on the phone (SPEC.md §5.3).

## Acceptance criteria
- [ ] Inbox list + one-at-a-time processing with the same actions as web (except calendar/reference until later), setting energy/creativity/contexts/estimate
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.inbox.*'` passes, covering each action calls the repository; offline state disables actions (Robolectric/Compose UI tests for screens)

## Notes
