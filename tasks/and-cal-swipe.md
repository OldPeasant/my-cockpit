---
title: Android calendar: swipe horizontally to the previous/next day, week, month
status: in_progress
owner: dell26/agent-1
deps: [and-cal-hidden-indicator]
touches: [android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda,android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda,android/app/src/main/res/values/strings_calendar_agenda.xml,android/app/src/main/res/values-de/strings_calendar_agenda.xml]
prio: 2
lease_until: 2026-11-22T10:15:54Z
releases: 1
extended: 
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
- [x] New strings are in English and German (`res/values/` + `res/values-de/`)
  - verified (2026-11-22 10:55+0100 by dell26/agent-1): git diff --stat origin/main...HEAD -> no strings*.xml changed; the pager adds no user-visible text (arrows/title reuse existing EN+DE strings), so no new strings are needed
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-22 from the owner's calendar usability request; the design decisions in the criteria were confirmed by the owner.
- ASSUMPTION (2026-11-22 10:50+0100 by dell26/agent-1): Neighbours are preloaded from the network only (two extra occurrence calls after a successful load of a day/week/month), not from the agenda cache; offline, a swiped-to neighbour shows an empty grid until its own load/cache fallback answers
- ASSUMPTION (2026-11-22 10:50+0100 by dell26/agent-1): Only the visible pager page is composed (beyondViewportPageCount 0) so test tags stay unique; a page reached without preloaded data opens at the default hour (7) and is positioned when its data arrives, instead of keeping the previous page's scroll offset (CalendarGridScrollTest adapted)
- release by dell26/agent-1: Implemented: HorizontalPager (PeriodPager in CalendarScreen.kt) for day/week/month, settled page -> move(n), arrows/Today scroll the pager; VM preloads previous/next period (state.neighbours, pageState(), shiftPeriod), move seeds the preloaded occurrences. Tests: new CalendarSwipeTest + VM preload test; CalendarGridScrollTest/EdgeCases adapted; all calendar unit tests pass. No new strings needed. Left: test-writer + code-reviewer helpers, verify criteria, full ktlintCheck/lintDebug + scripts/check.sh, quality.json, done.
