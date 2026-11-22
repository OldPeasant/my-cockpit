---
title: Android calendar: long press in day/week/month creates an event there
status: in_progress
owner: dell26/agent-1
deps: [and-cal-swipe]
touches: [android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda,android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda,android/app/src/main/res/values/strings_calendar_agenda.xml,android/app/src/main/res/values-de/strings_calendar_agenda.xml,android/app/src/main/java/ch/sonensei/cockpit/calendar/edit,android/app/src/test/java/ch/sonensei/cockpit/calendar/edit]
prio: 2
lease_until: 2026-11-22T11:17:39Z
---
## Goal
A new event can only be created with the + button (today, or the shown day). A long press (~0.5 s, the platform
long-press timeout) on a point of the calendar should create an event right there.

## Acceptance criteria
- [ ] Day and week view, hour grid: long press opens the new-event editor on that day, starting at the pressed time
  rounded down to 15 minutes, lasting 1 hour (`NEW_EVENT_HOURS`); this also works when pressing on an existing event
  (a tap on an event still opens it)
- [ ] While the press is recognised: haptic feedback and a short "ghost" block showing the resulting slot
- [ ] Day and week view, all-day strip: long press opens the editor with an all-day event on that day
- [ ] Month view: long press on a day opens the editor with a timed event on that day, with the same defaults as the +
  button (next full hour if today, otherwise the default start); a tap still opens the day view
- [ ] The calendar is chosen like for the + button (first visible writable calendar); `EventEditorViewModel.create` gets
  an optional start time / all-day flag, the + button behaves as before
- [ ] The grid, all-day strip and month days offer a matching accessibility custom action ("New event")
- [ ] Long press and horizontal swipe (`and-cal-swipe`) / vertical scroll don't interfere
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-22 from the owner's calendar usability request; the design decisions in the criteria were confirmed by the owner.
- ASSUMPTION (2026-11-22 11:56+0100 by dell26/agent-1): The day/week all-day strip is now always shown (min 28dp, 'All day' label) also without all-day entries, otherwise a long press there would be impossible on most days
- ASSUMPTION (2026-11-22 11:56+0100 by dell26/agent-1): Grid long press: ghost shows when recognised, the editor opens on release (finger lifted); the accessibility 'New event' action on the grid uses the + button's default start (no pressed point), on the strip an all-day event
