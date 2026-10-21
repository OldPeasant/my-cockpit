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
- ASSUMPTION (2026-10-21 17:39+0200 by dell26/agent-1): Entry points: a FAB on the Calendar screen creates an event (on the day the day view shows, else today; today from the next full hour, other days 09:00, 1h), tapping an occurrence edits it. Wiring needed a one-line change in calendar/agenda/CalendarScreen.kt (CalendarRoute -> CalendarWithEditor) and a new res/values/strings_calendar_edit.xml, both outside the task's touches
