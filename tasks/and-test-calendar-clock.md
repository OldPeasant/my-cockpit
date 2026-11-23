---
title: Android: calendar tests depend on the real time of day (fix clock)
status: in_progress
owner: dell26/agent-1
deps: []
touches: [android/app/src/test/java/ch/sonensei/cockpit/calendar]
prio: 2
lease_until: 2026-11-23T19:43:48Z
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
- [ ] Other Android calendar Compose tests that render the day/week grid or the "now" line on the test's `today` without
      a fixed clock (e.g. `CalendarLongPressInteractionTest`, `CalendarScreensCachedTest`, `CalendarWithEditor*Test`,
      `CalendarRouteResumeTest`) are checked; each one that depends on the real time gets a fixed clock too. The tests
      checked and the ones changed are listed in `reviews/<id>/tests.md`
- [x] Shown time-independent: the changed classes pass with their fixed clock moved temporarily to 23:30 and to 00:30
      (or a parameterized test does this permanently, where the test's intent allows), as well as with the final clock
  - verified (2026-11-23 20:24+0100 by dell26/agent-1): sh .tmp/runclk.sh with nowClock sed-moved in all 10 changed classes: 08:00 -> 105 passed; 23:30 -> 1 failed (vertical gesture test, grid at bottom), test moved to today+5 (opens at default hour); then 00:30 -> 105 passed, 23:30 -> 105 passed, final 08:00 -> 105 passed
- [ ] Test code only; production code is unchanged, unless the investigation shows a real problem (then record it with
      `tasks.py assume`)
- [ ] `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes
