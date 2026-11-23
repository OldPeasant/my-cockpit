---
title: Android: calendar tests depend on the real time of day (fix clock)
status: done
owner: dell26/agent-1
deps: []
touches: [android/app/src/test/java/ch/sonensei/cockpit/calendar]
prio: 2
lease_until: 
---
## Goal
`CalendarSwipeInteractionTest > a mostly vertical diagonal gesture scrolls the grid and keeps the period` fails
depending on the time of day: it failed in CI run 45242433088 (19:02 UTC, commit ed02bd8fd, an unrelated change) with
`AssertionError: the grid scrolled down`, and was reproduced locally at 19:13 UTC.

Cause: the day/week grid opens a day that is `today` and has entries at the current hour
(`firstVisibleHour` in `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt`, "now" from
`LocalNowClock`). The test fixes its `today` (2026-10-21) but provides no `LocalNowClock`, so the real system time
is used. In the evening the grid opens at the bottom, can't scroll further down, and the assertion fails. The app is
correct; the test is not deterministic.

## Acceptance criteria
- [x] `CalendarSwipeInteractionTest` provides a fixed `LocalNowClock` (e.g. 08:00 on its `today`, in its zone), so the
      failing test and the rest of the class don't depend on the real time
  - verified (2026-11-23 20:24+0100 by dell26/agent-1): CalendarSwipeInteractionTest.show() wraps content (incl. StateRestorationTester path) in CompositionLocalProvider(LocalNowClock provides Clock.fixed(2026-10-21T08:00Z, UTC)); ./gradlew testDebugUnitTest --tests '*CalendarSwipeInteractionTest' (with 9 other classes, .tmp/runclk.sh) -> pass
- [x] Other Android calendar Compose tests that render the day/week grid or the "now" line on the test's `today` without
      a fixed clock (e.g. `CalendarLongPressInteractionTest`, `CalendarScreensCachedTest`, `CalendarWithEditor*Test`,
      `CalendarRouteResumeTest`) are checked; each one that depends on the real time gets a fixed clock too. The tests
      checked and the ones changed are listed in `reviews/<id>/tests.md`
  - verified (2026-11-23 20:30+0100 by dell26/agent-1): grep for LocalNowClock/setContent/GRID_SCROLL/NOW across calendar tests: changed 9 more classes (CalendarSwipeTest, CalendarScreensStatesTest, CalendarLongPressInteractionTest, CalendarScreensCachedTest, CalendarOverlayMonthScreensTest, CalendarRouteResumeTest, CalendarWithEditor{,Delete,LongPress}Test); checked unchanged: CalendarScreenInAppTest, agenda/month-only tests; list in reviews/and-test-calendar-clock/tests.md; runclk.sh 105 passed
- [x] Shown time-independent: the changed classes pass with their fixed clock moved temporarily to 23:30 and to 00:30
      (or a parameterized test does this permanently, where the test's intent allows), as well as with the final clock
  - verified (2026-11-23 20:24+0100 by dell26/agent-1): sh .tmp/runclk.sh with nowClock sed-moved in all 10 changed classes: 08:00 -> 105 passed; 23:30 -> 1 failed (vertical gesture test, grid at bottom), test moved to today+5 (opens at default hour); then 00:30 -> 105 passed, 23:30 -> 105 passed, final 08:00 -> 105 passed
- [x] Test code only; production code is unchanged, unless the investigation shows a real problem (then record it with
      `tasks.py assume`)
  - verified (2026-11-23 20:24+0100 by dell26/agent-1): git diff --stat 3a3055d49..HEAD -- android/app/src/main -> empty; only android/app/src/test calendar files changed; no production problem found (firstVisibleHour opening today at the current hour is intended)
- [x] `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes
  - verified (2026-11-23 20:30+0100 by dell26/agent-1): cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug (HEAD 7b205a57a) -> BUILD SUCCESSFUL in 4m19s, 314 test result files, no failures
  - verified (2026-11-23 20:30+0100 by dell26/agent-1): cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug (HEAD 7b205a57a) -> BUILD SUCCESSFUL in 4m19s, rc=0, 477 test result files, no failures
- done by dell26/agent-1: `sh scripts/check.sh` passed in 98s on 9fdadd018; criteria verified 5/5
