---
title: Android event create/edit
status: in_progress
owner: dell26/agent-1
deps: [and-cal-agenda]
touches: [android/app/src/main/java/ch/sonensei/cockpit/calendar/edit,android/app/src/test/java/ch/sonensei/cockpit/calendar/edit]
prio: 2
lease_until: 2026-10-21T15:56:09Z
---
## Goal
Create and edit events on the phone.

## Acceptance criteria
- [ ] Event form incl. recurrence presets and THIS/FOLLOWING/ALL prompt
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.*'` passes, covering form validation and scope prompt (Robolectric/Compose UI tests for screens)

## Notes
