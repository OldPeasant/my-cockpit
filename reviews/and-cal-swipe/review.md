# Code Review

Date: 2026-11-22

Task: and-cal-swipe - Android calendar: swipe horizontally to the previous/next day, week, month

Scope: round 4 review of the final task code, reviewed revision fdb0e4fd100efe139c82d353ef4c913c17067343. Full code scope `git diff 9bbd63434 fdb0e4fd1 -- android/` (CalendarScreen.kt, CalendarViewModel.kt and the calendar agenda tests). The delta since the round-3 review is commit fdb0e4fd1, which fixes round-3 CR-1 and CR-2. `PeriodPager` now has a single collector over `(isScrollInProgress, shownPage)` and a `dragFrom` page that replace the `dragged` flag, the settled-page collector and the follow effect. Static review only: Gradle was not run, as the parent worker instructed (another helper runs it at the same time). Bash was not available in this session, so I read the current files directly instead of the raw diff output.

Requirements:
- tasks/and-cal-swipe.md (acceptance criteria and recorded assumptions)
- SPEC.md, docs/ARCHITECTURE.md, AGENTS.md project notes
- Round-3 reports reviews/and-cal-swipe/review.md (previous version) and reviews/and-cal-swipe/tests.md, reviews/and-cal-swipe/fixes.md (rounds 1 and 2)
- Existing calendar code conventions (CalendarScreen.kt PeriodPager, CalendarSwipeInteractionTest.kt)

Git range:
- Branch: main
- HEAD: fdb0e4fd100efe139c82d353ef4c913c17067343
- Base/start: 9bbd634344021b662e25f5aaa4ee8292665edb87
- Commits reviewed: full task scope; delta in this round: fdb0e4fd1

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Earlier findings

- Round-3 CR-1 (P2, a drag interrupts an arrow slide and snaps back, leaving the pager and the view model out of sync): resolved. The collector at CalendarScreen.kt:743-760 runs every time the pager comes to rest or the shown page changes while it rests. It no longer relies on a change of `settledPage`. The follow scroll runs in a child `launch` (CalendarScreen.kt:755-757), so when a user drag preempts it, only that child job is cancelled and the collector keeps running. Walked through the reported sequence: Next sets target N+1, and the drag starts with `dragFrom = N` (CalendarScreen.kt:736). After the snap back, the emission `(false, N+1)` has `page == from`, so the else branch scrolls the pager to N+1 again. Pager and VM end in sync. Covered by `a drag that catches an arrow slide and snaps back keeps the pager and the view model in sync` (CalendarSwipeInteractionTest.kt:181), but see CR-1 below. The variants are also handled: a grab past halfway that lets go on N+1 makes no move (test at :246), a swipe back makes `move(page - target)` (test at :265), and a swipe beyond the target moves on from the target (test at :283).
- Round-3 CR-2 (P3, the mid-slide test did not assert its precondition): resolved. CalendarSwipeInteractionTest.kt:164-171 now asserts that the next day's left edge is left of the pager centre and right of the pager's left edge before the second tap. The same check is used by the `slidePastHalfway()` helper (:225-235).
- Round-2 CR-1 (an arrow slide interrupted after its midpoint was read as a swipe): no regression. Next, past halfway, Next: the second change of the shown page arrives while the pager scrolls and is ignored (CalendarScreen.kt:748). The running slide finishes on N+1 with `dragFrom == null`, and the follow then animates to N+2. No move back. The regression test (:158) is kept.
- Round-1 CR-1 (a restored pager page read as a swipe): no regression. The pager state is still a non-saveable `remember { PagerState(currentPage = shownPage) }` (CalendarScreen.kt:727). On recreation the first emission has `page == target`, so nothing happens. The test `a restored screen keeps the period without moving the view model again` is kept.
- Round-1 CR-2 (the pager did not follow a period change made during a scroll): no regression. A change of the target during a drag is ignored while scrolling. When the drag ends on its start page, the else branch follows the new target. The test `a period change during a drag is followed once the drag snaps back` is kept.
- Round-2 CR-2 (P3 flicker of a preloaded page): accepted by the parent worker (task assumption of 2026-11-22 11:18). Not re-reported.
- Leftovers checked: no references to `dragged` or `settledPage` remain. The new imports (`abs`, `launch`, `mutableStateOf`, `snapshotFlow`, `DragInteraction`) are present, and no orphaned `first` import is left.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The regression test for round-3 CR-1 does not assert that the pager actually snapped back to the start page**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarSwipeInteractionTest.kt:188-203`
    - Category: Tests
    - Problem: The comment says the gesture "snaps back to the start page", but the assertions accept any final page ("Whatever page the pager rests on"). They only check that the anchor equals today plus the sum of moves and that the anchor page fills the pager. The gesture is `down`, `moveBy(width * 0.05f)` in one event period, then `up`, which can produce a fling velocity above the pager's snap threshold. If the pager flings to N-1, or rests on N+1, the test still passes. It then exercises the swipe branch (`move(page - target)`) or the no-op branch, not the snap-back follow path that round-3 CR-1 was about. This is the same weakness as round-3 CR-2, and nothing in the committed reports shows that this test failed on the pre-fix code.
    - Why it matters: The only test for the round-3 CR-1 path can stop covering it without failing, for example if the gesture timing or the pager's fling defaults change.
    - Suggested fix: Assert the precondition, for example `assertEquals(listOf(1), moves)` and anchor today+1. A snap back to N followed by the follow slide to N+1 issues no second move. Alternatively, record in tests.md or fixes.md that the test fails on 0ff4595e9 (before fdb0e4fd1). Optionally, reuse the precondition check at :164-171 through `slidePastHalfway()` instead of duplicating it.

## Review conclusion

No material issues found. Round-3 CR-1 and CR-2 are resolved, and the earlier fixed findings did not regress. The remaining P3 is a test-strength improvement; the parent worker decides whether to address it.
