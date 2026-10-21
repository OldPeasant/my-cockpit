---
title: Android event create/edit
status: in_progress
owner: dell26/agent-1
deps: [and-cal-agenda]
touches: [android/app/src/main/java/ch/sonensei/cockpit/calendar/edit,android/app/src/test/java/ch/sonensei/cockpit/calendar/edit]
prio: 2
lease_until: 2026-10-21T16:11:21Z
---
## Goal
Create and edit events on the phone.

## Acceptance criteria
- [ ] Event form incl. recurrence presets and THIS/FOLLOWING/ALL prompt
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.*'` passes, covering form validation and scope prompt (Robolectric/Compose UI tests for screens)

## Notes
- ASSUMPTION (2026-10-21 17:39+0200 by dell26/agent-1): Entry points: a FAB on the Calendar screen creates an event (on the day the day view shows, else today; today from the next full hour, other days 09:00, 1h), tapping an occurrence edits it. Wiring needed a one-line change in calendar/agenda/CalendarScreen.kt (CalendarRoute -> CalendarWithEditor) and a new res/values/strings_calendar_edit.xml, both outside the task's touches
- ASSUMPTION (2026-10-21 17:39+0200 by dell26/agent-1): Mirrors web-cal-edit: repeat presets none/daily/weekly/monthly/yearly (FREQ=X), an existing rule they can't express is kept as 'Custom (rule)'; a new repeat rule offers only FOLLOWING/ALL; 409 and 412 both show 'changed elsewhere' with Reload (which drops the unsaved edits); only changed fields are PATCHed
- ASSUMPTION (2026-10-21 17:39+0200 by dell26/agent-1): Phone form scope: title, calendar, all-day, start/end date+time, repeat, location, description. Not on the phone (kept unchanged by the PATCH): reminders, attendees, time zone (new events use the device zone, existing ones keep theirs, shown read-only), delete. An existing event can't move to another calendar (synced events can't; CalendarInfo has no source type). New events default to the first visible writable calendar (CalendarInfo has no isDefault)
