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
