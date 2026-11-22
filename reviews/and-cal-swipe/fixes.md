# Fix Report

Date: 2026-11-22

Task: and-cal-swipe - Android calendar: swipe horizontally to the previous/next day, week, month

Scope: current task; findings CR-1 and CR-2 from reviews/and-cal-swipe/review.md, TW-1 from reviews/and-cal-swipe/tests.md (CR-3 is the test gap for these paths and is covered by the tests added here and by the test-writer's committed tests).

## Fixed findings

### CR-1 - Restored pager page is interpreted as a swipe

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarSwipeInteractionTest.kt`

Problem:
`base` was a plain `remember`, while `rememberPagerState` restored its page. After a recreation (rotation, return to the tab) the restored page next to a fresh base made the settled-page collector call `move(k)` although no swipe happened.

Fix:
The pager state is now created with `remember { PagerState(currentPage = shownPage) { Int.MAX_VALUE } }`, so page origin and pager page are both recreated together from the view model's current period (the view model owns the period across recreations). Chosen over a saveable base because after process death the view model restarts at today while a saved base and page would still point at the old period and trigger a move.

Validation:
- New test `a restored screen keeps the period without moving the view model again` (StateRestorationTester: next arrow, emulate save/restore, assert moves stays `[1]` and the next day's page is shown). Fails on the previous code, passes with the fix.

### CR-2 - The pager does not follow a period change made while it is still scrolling

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarSwipeInteractionTest.kt`

Problem:
The follow-the-view-model effect skipped when `isScrollInProgress` was true and was not re-run, leaving the pager on a stale page.

Fix:
The effect now waits with `snapshotFlow { pagerState.isScrollInProgress }.first { !it }` and then compares the current page with the shown period and scrolls there. The effect is keyed on `shownPage`, so a newer change cancels and replaces the waiting one.

Validation:
- New test `a period change during a drag is followed once the drag snaps back` (finger down and a short drag on the pager, the period changes, finger up: the pager ends on the new period's page and no move is issued). Fails on the previous code, passes with the fix.

## Not fixed

### TW-1 - Preload result dropped when the anchor changed but the range did not

Status:
Invalid

Reason:
The scenario cannot happen through the view model's API. The early return in `show` (anchor update without reload) needs a non-forced call with a different anchor in the same view and the same range. `goToToday` and the settings path call `show` with `force = true`, which reloads and restarts the neighbour preload with the new anchor (the example in tests.md ends with Today, so it takes the forced path). `move` always changes the day, week or month range, `select` returns early for the same view, and `openDay` switches to the day view where a different anchor is a different range. No VM test can drive the case, so the code was left unchanged rather than adding an untestable branch.

## Validation summary

Focused tests:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.CalendarSwipeInteractionTest'` on the previous CalendarScreen.kt - 11 tests, 2 failed (the two new tests), as expected
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*'` with the fix - BUILD SUCCESSFUL (CalendarSwipeInteractionTest 11 tests, 0 failures)

Full test suite:
- Not run (scope limited to the calendar agenda package as requested by the parent worker)

Lint/type-check/build:
- `ktlintCheck` - Pass (part of the command above)

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.

# Round 2

Date: 2026-11-22

Task: and-cal-swipe - Android calendar: swipe horizontally to the previous/next day, week, month

Scope: current task; finding CR-1 (P2) from the round-2 reviews/and-cal-swipe/review.md (reviewed revision 1397f2568). CR-2 (P3) was accepted by the parent worker and is not addressed here.

## Fixed findings

### CR-1 - An arrow animation interrupted after its midpoint settles on the intermediate page, which is then read as a swipe

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarSwipeInteractionTest.kt`

Problem:
The settled-page collector in `PeriodPager` treated every settled page different from the shown period as a user swipe. A second Next tap after the first slide passed its midpoint cancelled the running `animateScrollToPage`, the pager settled on the page in between, and the collector called `move(-1)`. Reproduced: the new test failed on the previous code with moves `[1, 1, -1]` instead of `[1, 1]`.

Fix:
`PeriodPager` keeps a `dragged` flag. A collector on `pagerState.interactionSource` sets it on `DragInteraction.Start`. The follow-the-view-model effect clears it right before it starts its own scroll (`animateScrollToPage` / `scrollToPage`). The settled-page collector only calls `move` when `dragged` is set, so settles caused by the pager's own scrolling are ignored. A user drag that interrupts an arrow slide still sets the flag and is still read as a swipe.

Validation:
- New test `a second tap on the next arrow after the slide passed halfway ends two days later without a move back` (manual clock: Next, advance 200 ms, Next, then idle; asserts moves `[1, 1]`, anchor today+2 and the today+2 page shown). Fails on the previous code (`[1, 1, -1]`), passes with the fix.
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*'` - BUILD SUCCESSFUL

## Not fixed

### CR-2 - A preloaded page loses its "shown" state when it is settled on

Status:
Out of scope for this round (accepted by the parent worker as P3).

Reason:
The parent worker accepted this P3 finding and asked the fixer not to address it.

## Validation summary

Focused tests:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.CalendarSwipeInteractionTest'` on the previous CalendarScreen.kt with the new test - 12 tests, 1 failed (the new test), as expected
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*'` with the fix - BUILD SUCCESSFUL; every calendar agenda test class passed with 0 failures, including CalendarSwipeInteractionTest (12 tests) and CalendarSwipeTest (6 tests, real swipe gestures)

Full test suite:
- Not run (scope limited to the calendar agenda package as requested by the parent worker)

Lint/type-check/build:
- `ktlintCheck` - Pass (part of the command above; ktlintFormat made no further changes needed)

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
0 (CR-2 accepted as P3 by the parent worker)

## Final status

All validated findings fixed and validation passed.
