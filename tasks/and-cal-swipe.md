---
title: Android calendar: swipe horizontally to the previous/next day, week, month
status: todo
owner:
deps: [and-cal-hidden-indicator]
touches: [android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda,android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda,android/app/src/main/res/values/strings_calendar_agenda.xml,android/app/src/main/res/values-de/strings_calendar_agenda.xml]
prio: 2
---
## Goal
The previous/next period is only reachable with the ‹ › arrows. Swiping horizontally should page through days, weeks and
months like a pager, with the content following the finger.

## Acceptance criteria
- [ ] Day view: swiping left/right shows the next/previous day; week view: next/previous week; month view: next/previous
  month. The content follows the finger and the neighbouring period slides in (pager style, e.g. `HorizontalPager`
  over a virtual page range); after settling, the view model's period is the new one (same effect as `move(±1)`)
- [ ] Vertical scrolling of the day/week grid still works; a mostly vertical gesture does not change the period and a
  horizontal one does not scroll vertically
- [ ] The neighbouring periods are preloaded (cache/network) so a swipe usually shows data immediately; if not loaded,
  the neighbour shows an empty grid that fills when its data arrives
- [ ] Arrows, Today and the range title keep working and stay in sync with the pager; the agenda view is unchanged
- [ ] Taps on entries, day headers and month days still work; the initial scroll position (`and-cal-initial-scroll`) and
  the hidden-entry chips (`and-cal-hidden-indicator`) work on the page that is settled on
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-22 from the owner's calendar usability request; the design decisions in the criteria were confirmed by the owner.
