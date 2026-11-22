---
title: Android calendar: swipe horizontally to the previous/next day, week, month
status: in_progress
owner: dell26/agent-2
deps: [and-cal-hidden-indicator]
touches: [android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda,android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda,android/app/src/main/res/values/strings_calendar_agenda.xml,android/app/src/main/res/values-de/strings_calendar_agenda.xml]
prio: 2
lease_until: 2026-11-22T11:03:52Z
releases: 0
extended: 
hold: 
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
- release by dell26/agent-1: Done: implementation + tests; round-1 test-writer (tests.md), code-reviewer (review.md: CR-1 P1, CR-2 P2, CR-3 P2 test gaps) and fixer (fixes.md, commit 'fix review findings CR-1, CR-2': non-saveable PagerState, follow after scroll ends, 2 new tests; TW-1 judged invalid: unreachable early return) all committed. Criterion 6 verified. Left: round-2 test-writer + code-reviewer on final code (fixes changed CalendarScreen.kt), verify criteria 1-5,7 (ktlintCheck testDebugUnitTest lintDebug, scripts/check.sh), quality.json (CR-1/CR-2 fixed, CR-3 fixed by test-writer tests, TW-1 invalid, TW-2 = CR-2 fixed), done.
- ASSUMPTION (2026-11-22 11:18+0100 by dell26/agent-1): Round-2 review CR-2 (P3) accepted, not fixed: after settling on a preloaded neighbour the VM briefly marks it not-loaded until its own load answers, so an empty day's 'no events' text can flicker and the grid is re-positioned once; cosmetic, no data shown wrong
- release by dell26/agent-1: Round 2: fixer fixed review CR-1 (programmatic settle read as swipe; dragged flag in PeriodPager, regression test); CR-2 P3 flicker accepted (assumption recorded). Round 3 reports committed (4184a38c1): tests.md (new drag-interrupts-arrow test, agenda tests 302/302 pass, coverage recorded), review.md finds new P2 CR-1: tap Next, drag mid-slide, snap back to start page -> pager shows N while VM/title on N+1 (follow effect not restarted after interrupted programmatic scroll; CalendarScreen.kt ~743) and P3 CR-2 (test at CalendarSwipeInteractionTest.kt:162 does not assert slide passed halfway). Left: fixer for round-3 CR-1 (+ CR-2 optional) with a Compose test, round-4 test-writer + code-reviewer, verify criteria 1-5,7 (full ktlintCheck testDebugUnitTest lintDebug passed on 1397f2568 via cd android && ./gradlew), scripts/check.sh, quality.json (findings per round: R1 CR-1/2/3 fixed, TW-1 invalid, TW-2=CR-2 fixed; R2 CR-1 fixed, CR-2 accepted; R3 ...), done.
