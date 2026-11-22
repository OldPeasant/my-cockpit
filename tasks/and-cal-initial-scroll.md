---
title: Android calendar: day/week grid opens scrolled to the earliest entry
status: in_progress
owner: dell26/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda,android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda,android/app/src/main/res/values/strings_calendar_agenda.xml,android/app/src/main/res/values-de/strings_calendar_agenda.xml]
prio: 2
lease_until: 2026-11-22T08:44:53Z
---
## Goal
The day and week grid (`CalendarScreen.kt` `TimeGrid`) always opens scrolled to 07:00 (`FIRST_VISIBLE_HOUR`). It
should open where the entries are: the earliest timed entry visible, and as many entries below it as fit.

## Acceptance criteria
- [ ] Day and week view: the grid's top is the full hour at or before the earliest start of a timed entry on the shown
  day(s) (entry at 14:20 -> top at 14:00), clamped to the scrollable range (late entries near midnight scroll to the end)
- [ ] Entries continuing from an earlier day (e.g. 22:00-02:00, shown from 00:00 on the next day) and all-day entries are
  ignored for this position
- [ ] No timed entry in the shown period: scroll to the current time (full hour before now) if today is shown, else 07:00
- [ ] The position is applied when the view opens and on every period change (arrows, Today, view switch, later swipe),
  once the period's first data (cache or network) is there; a background refresh/reload of the same period does not
  move the scroll position
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-22 from the owner's calendar usability request; the design decisions in the criteria were confirmed by the owner.
- ASSUMPTION (2026-11-22 09:17+0100 by dell26/agent-1): 'Full hour before now' read as the current hour floored (15:40 -> 15:00), consistent with the entry rule (14:20 -> 14:00).
- ASSUMPTION (2026-11-22 09:17+0100 by dell26/agent-1): A period is (view, first day, last day): pressing Today while today's period is already shown is not a period change and does not move the scroll position.
