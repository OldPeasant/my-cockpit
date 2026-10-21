---
title: Android inbox processing
status: in_progress
owner: dell26/agent-4
deps: [and-gtd-data]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox,android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox]
prio: 1
lease_until: 2026-10-21T08:09:02Z
---
## Goal
Process the inbox on the phone (SPEC.md §5.3).

## Acceptance criteria
- [ ] Inbox list + one-at-a-time processing with the same actions as web (except calendar/reference until later), setting energy/creativity/contexts/estimate
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.inbox.*'` passes, covering each action calls the repository; offline state disables actions (Robolectric/Compose UI tests for screens)

## Notes
- ASSUMPTION (2026-10-21 09:51+0200 by dell26/agent-4): Android inbox omits 'To reference' and 'To calendar' (task says: except calendar/reference until later); actions shown: Do it, Next, To project, Waiting, Schedule, Tickler, Someday, Trash.
