# Code Review

Date: 2026-11-22

Task: and-cal-swipe - Android calendar: swipe horizontally to the previous/next day, week, month

Scope: round 3 review of the final task code, reviewed revision 65ee2d32ea88647b84242d2a56771e86d10fd13f. Full code scope `git diff 9bbd63434 65ee2d32e -- android/` (CalendarScreen.kt, CalendarViewModel.kt and the calendar agenda tests); the delta since the round-2 review is commit 65ee2d32e (fix for round-2 CR-1, described in reviews/and-cal-swipe/fixes.md "Round 2"). The local branch has since been rewritten to 554dc3a661acffd35b1c5d5395097cd9bae519ab; `git diff 65ee2d32e 554dc3a66 -- android/` is empty, so the reviewed Android code is identical. Static review only: Gradle was not run, as instructed by the parent worker (another helper runs it concurrently).

Requirements:
- tasks/and-cal-swipe.md (acceptance criteria and recorded assumptions)
- SPEC.md, docs/ARCHITECTURE.md, AGENTS.md project notes
- Round-2 reports reviews/and-cal-swipe/review.md (previous version) and reviews/and-cal-swipe/fixes.md "Round 2"
- Existing calendar code conventions (CalendarScreen.kt PeriodPager, CalendarSwipeInteractionTest.kt)

Git range:
- Branch: main
- HEAD: 65ee2d32ea88647b84242d2a56771e86d10fd13f (reviewed revision; same Android code as the rewritten 554dc3a66)
- Base/start: 9bbd634344021b662e25f5aaa4ee8292665edb87
- Commits reviewed: 5 code commits in the full scope (f509c20e0, 588a17bb6, 6d3b12c45, 1397f2568, 65ee2d32e); delta in this round: 65ee2d32e

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## Earlier findings

- Round-2 CR-1 (P2, an arrow slide interrupted after its midpoint settles on the page in between, which was read as a swipe): resolved. `PeriodPager` keeps a `dragged` flag (CalendarScreen.kt:732), set by a collector on `pagerState.interactionSource` on `DragInteraction.Start` (CalendarScreen.kt:733-735) and cleared by the follow effect right before its own `animateScrollToPage`/`scrollToPage` (CalendarScreen.kt:747). The settled-page collector only calls `move` when `dragged` is set (CalendarScreen.kt:739). For the reported sequence (Next, slide past halfway, Next) the flag is false, so the intermediate settle is ignored and the restarted follow effect slides on to the latest page. Checked the ordering: the flag is cleared before the follow scroll starts, so the settle that `PagerState.scroll` records at scroll start cannot be read as a swipe; a normal swipe sets the flag long before the settle, and a swipe that settles on the shown page issues no move. A drag that snaps back leaves the flag set, which is harmless because every later programmatic scroll clears it first. Covered by `a second tap on the next arrow after the slide passed halfway ends two days later without a move back` (CalendarSwipeInteractionTest.kt:157); fixes.md records that it failed on the previous code with moves `[1, 1, -1]`.
- Round-2 CR-2 (P3, a preloaded page loses its "shown" state when settled on, so the empty-day text flickers and the grid is positioned again): known and accepted by the parent worker as non-blocking (task assumption of 2026-11-22 11:18). Not re-reported.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A drag that interrupts an arrow slide and snaps back to the starting page leaves the pager and the view model out of sync**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt:743`
    - Category: Correctness
    - Problem: On page N the user taps Next: the view model moves, `shownPage` becomes N+1 and the follow effect starts `animateScrollToPage(N+1)`. If the user grabs the pager during this slide, the user-input scroll preempts the animation through the pager's mutator mutex, which cancels the follow effect's coroutine; the effect does not run again until `shownPage` changes. `settledPage` stays N during the whole sequence (it is fixed when a scroll starts while none is running, and the drag starts while the animation is still running). If the finger is released so that the pager snaps back to N (a short drag, or a grab before the slide reaches halfway), `settledPage` is N again and does not change, so the settled-page collector emits nothing; the follow effect is no longer running either. Result: the pager shows day N while the view model, header title and arrows are on N+1. Releasing towards N+2 or N-1 works (the settle differs from the previous value and `dragged` is set). This path existed before 65ee2d32e and was not introduced by the round-2 fix (the round-1 CR-2 fix only waits for a running scroll before starting the follow scroll, not for an interruption of its own scroll), but it was not reported in round 2. Found by reading the code and the pager's settle and mutator semantics; not reproduced here because Gradle could not be run.
    - Why it matters: Criterion 4 requires the arrows and the range title to stay in sync with the pager. In this state the title names a different period than the visible page, and the next arrow tap moves from the wrong period.
    - Suggested fix: Make the result of a user drag authoritative when the drag's scroll ends, not only when the settled page changes. For example, collect `snapshotFlow { pagerState.isScrollInProgress }` and, on the transition to false with `dragged` set, call `move(currentPage - latestShownPage)` if they differ (and drop the separate settled-page collector, or keep it guarded so the move is issued only once). Alternatively, let the follow logic re-check after any interruption (a long-lived collector over the shown page and `isScrollInProgress` that scrolls to the shown page whenever the pager is idle on another page and no drag result is pending). Add a Compose test: manual clock, tap Next, advance part of the slide, `down` on the pager, a small `moveBy`, `up`, idle; then assert that the visible page and `holder.value.anchor` agree.

## P3 Low

- [ ] **CR-2 | The new mid-slide test does not assert its own precondition**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarSwipeInteractionTest.kt:162`
    - Category: Tests
    - Problem: The test relies on `advanceTimeBy(200)` putting the default `animateScrollToPage` animation past its midpoint, as the comment says, but does not check that the pager's current page already is the next day before the second tap. If the default pager animation spec or the frame timing changes, the second tap lands before the midpoint and the test silently degrades into a duplicate of `two quick taps on the next arrow end two days later without a move back`, still passing without covering round-2 CR-1.
    - Why it matters: The regression test for round-2 CR-1 can lose its coverage without any failure.
    - Suggested fix: Before the second tap, assert the precondition. Both pages are on screen during the slide, so `assertIsDisplayed` alone does not show it. Instead, compare positions: the left bound of the today+1 page (`fetchSemanticsNode().boundsInRoot`) is already left of the pager's horizontal center. Alternatively, expose the pager's current page through a semantics property that only tests use.

## Review conclusion

Material issues found; fix before completing the task. Round-2 CR-1 is resolved and the delta introduces no new problem. CR-1 above is a P2 sync gap that existed before this round's fix. The parent worker decides whether it blocks the task.
