---
title: Android calendar: day/week show which entries are scrolled out of view
status: in_progress
owner: dell26/agent-1
deps: [and-cal-initial-scroll]
touches: [android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda,android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda,android/app/src/main/res/values/strings_calendar_agenda.xml,android/app/src/main/res/values-de/strings_calendar_agenda.xml]
prio: 2
lease_until: 2026-11-22T09:25:26Z
---
## Goal
In the day and week grid you cannot see that entries are above or below the visible part. Show it per day column and
let a tap bring them into view.

## Acceptance criteria
- [ ] Day and week view: each day column shows a small chip at the top edge ("▲ n") when n of its timed entries are entirely
  above the visible part, and at the bottom edge ("▼ n") when entirely below; partly visible entries count as visible;
  no chip when n = 0; the chips update while scrolling
- [ ] In the narrow week columns the chip fits (arrow + number only) on a 360dp-wide phone; in the day view it may say
  more (e.g. "2 earlier" / "1 later")
- [ ] Tapping a chip scrolls (animated) so the nearest hidden entry in that direction is in view
- [ ] Chips have content descriptions for TalkBack (e.g. "2 events earlier, show")
- [ ] The visibility calculation is a pure function (entries, scroll offset, viewport height) with unit tests
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-22 from the owner's calendar usability request; the design decisions in the criteria were confirmed by the owner.
- ASSUMPTION (2026-11-22 10:04+0100 by dell26/agent-1): Tapping a chip scrolls so the nearest hidden entry is fully in view: above -> its start at the top edge, below -> its end at the bottom edge (its start at the top if taller than the viewport); all non-all-day entries of the column count, including ones continuing from the previous day
- ASSUMPTION (2026-11-22 10:04+0100 by dell26/agent-1): Week-view chips drop the 48dp minimum touch target (columns are ~44dp on a 360dp phone); day-view chips keep it
