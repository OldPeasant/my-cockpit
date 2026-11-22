# Code Review

Date: 2026-11-22

Task: and-cal-swipe - Android calendar: swipe horizontally to the previous/next day, week, month

Scope: round 2 review of the final task code, reviewed revision 1397f2568d5f83dc8dc2070487445c0a918917e8. Code scope `git diff 9bbd63434 1397f2568 -- android/` (CalendarScreen.kt, CalendarViewModel.kt and the calendar agenda tests). Static review only: Gradle was not run, as instructed by the parent worker (another helper runs it concurrently).

Requirements:
- tasks/and-cal-swipe.md (acceptance criteria and recorded assumptions)
- SPEC.md, docs/ARCHITECTURE.md, AGENTS.md project notes
- Round-1 reports reviews/and-cal-swipe/review.md and reviews/and-cal-swipe/fixes.md
- Existing calendar code conventions (CalendarScreen.kt TimeGrid positioning, CalendarViewModel.kt load/show)

Git range:
- Branch: main
- HEAD: 1397f2568d5f83dc8dc2070487445c0a918917e8 (reviewed revision)
- Base/start: 9bbd634344021b662e25f5aaa4ee8292665edb87
- Commits reviewed: 4 (f509c20e0, 588a17bb6, 6d3b12c45, 1397f2568)

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## Round-1 findings

- CR-1 (P1, restored pager page read as a swipe): resolved. `PeriodPager` now creates the pager state with `remember { PagerState(currentPage = shownPage) { Int.MAX_VALUE } }` (CalendarScreen.kt:726), so the page origin `base` and the pager page are recreated together from the view model's period; the restored state can no longer disagree with `base`. Covered by `a restored screen keeps the period without moving the view model again` (CalendarSwipeInteractionTest.kt:247, StateRestorationTester).
- CR-2 (P2, pager does not follow a change made during a scroll): resolved. The follow effect waits for `isScrollInProgress` to become false before comparing pages (CalendarScreen.kt:738); keyed on `shownPage`, a newer change replaces the waiting effect. Covered by `a period change during a drag is followed once the drag snaps back` (CalendarSwipeInteractionTest.kt:263).
- CR-3 (P2, sync and preload staleness untested): resolved. CalendarSwipeInteractionTest covers Today/far jumps, two quick arrow taps, restoration, drag interplay, taps on swiped-to pages and late data; CalendarViewModelNeighboursTest covers week/month neighbour ranges, a failed neighbour preload, a preload answering after a move, swipe back and view switches.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | An arrow animation interrupted after its midpoint settles on the intermediate page, which is then read as a swipe**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt:730`
    - Category: Correctness
    - Problem: The settled-page collector treats every settled page that differs from `latestShownPage` as a user swipe and calls `move(page - latestShownPage)`. Programmatic scrolls can also settle on such a page. Sequence: on page N the user taps Next; the view model moves, `shownPage` becomes N+1 and the follow effect starts `animateScrollToPage(N+1)`. Once the animation is past its midpoint, `currentPage` is already N+1. A second Next tap at that moment makes `shownPage` N+2, which restarts `LaunchedEffect(shownPage)` and cancels the running animation. The pager stops between pages with `currentPage` N+1, and `settledPage` takes that value (at the latest when the restarted effect starts its next scroll, because `PagerState.scroll` sets `settledPage = currentPage` when no scroll is running). The collector then sees N+1 while `latestShownPage` is already N+2 and calls `move(-1)`: the view model goes back one period, although the user tapped Next twice. Further moves can follow while the pager and the view model chase each other. The existing test `two quick taps on the next arrow end two days later without a move back` (CalendarSwipeInteractionTest.kt:142) taps again after two frames, before `currentPage` changes, so it does not cover this case. This is based on reading the code and the pager's settle semantics; it was not reproduced here because Gradle could not be run.
    - Why it matters: Criterion 4 requires the arrows to stay in sync with the pager. Tapping an arrow twice quickly is a normal interaction, and here it lands on the wrong period (or bounces) without any swipe.
    - Suggested fix: Only treat a settle as a swipe when it follows a user drag. For example, track drags through `pagerState.interactionSource` (a `DragInteraction.Start` since the last settle), or set a flag while the follow effect runs its own scroll and ignore settles during and right after it. Alternatively, have the follow effect scroll straight to the latest `shownPage` without the intermediate settle being acted on. Add a Compose test that taps Next again after advancing the clock past half of the slide animation, and asserts `moves == [1, 1]` and anchor today+2.

## P3 Low

- [ ] **CR-2 | A preloaded page loses its "shown" state when it is settled on, so the empty-day text flickers and the grid is positioned again**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModel.kt:428`
    - Category: Quality
    - Problem: While a neighbour is swiped in, `pageState` marks its preloaded data as `loaded = true` (CalendarViewModel.kt:113), so `TimeGrid` treats it as shown: it positions the grid and, in the day view, shows the empty-day text. When the page settles, `move` calls `show`, which keeps the preloaded occurrences but sets `loaded = it.loaded && sameRange`, which is false. The same page (the same pager key) now has `shown == false`. The empty-day text disappears, `positionedFor` is reset (CalendarScreen.kt:888), and when the load answers the text appears again and the grid scrolls back to the computed hour. That scroll also overrides any vertical scroll the user started between settling and the load answer.
    - Why it matters: This causes visible flicker on every swipe to an empty, preloaded day, and can jump the scroll position. Criterion 3 aims for "shows data immediately" without such churn.
    - Suggested fix: Let the settled state say that the preloaded data is displayable, for example a flag for preloaded data that `shown` includes but that does not block the offline cache fallback in `load`. Or keep the current positioning when the period was already positioned from preloaded data.

## Review conclusion

Material issues found; fix before completing the task.
