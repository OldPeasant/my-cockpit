# Code Review

Date: 2026-11-22

Task: and-cal-swipe - Android calendar: swipe horizontally to the previous/next day, week, month

Scope: round 5 review, reviewed revision 8ab9974f11aa5d2ac02c7dda72215c0f47663bcc. The delta since the round-4 review (fdb0e4fd1) is test code only: three new tests and two helpers (`slidePastHalfway`, `assertPagerRestsOn`) in `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarSwipeInteractionTest.kt`, added by the test-writer (see reviews/and-cal-swipe/tests.md). The parent worker states production code is unchanged; the PeriodPager logic I read (CalendarScreen.kt:727-760) matches what I reviewed in round 4. Static review only: Gradle was not run, as instructed. Bash was not available in this session, so I read the current files directly instead of the raw diff.

Requirements:
- tasks/and-cal-swipe.md (acceptance criteria and recorded assumptions)
- SPEC.md, docs/ARCHITECTURE.md, AGENTS.md project notes
- Round-4 reports reviews/and-cal-swipe/review.md (previous version) and reviews/and-cal-swipe/tests.md

Git range:
- Branch: main
- HEAD: 8ab9974f11aa5d2ac02c7dda72215c0f47663bcc
- Base/start: 9bbd634344021b662e25f5aaa4ee8292665edb87
- Commits reviewed: full task scope reviewed in earlier rounds; delta in this round: fdb0e4fd1..8ab9974f1 (test code only)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Earlier findings

- Round-4 CR-1 (P3: the snap-back regression test at CalendarSwipeInteractionTest.kt:181 does not assert that the pager actually snapped back): **not addressed**. That test is unchanged (:188-203 still accepts "whatever page the pager rests on"). The new past-halfway test (:246) does not cover the same path. Its drag starts when `pagerState.currentPage` is already the arrow's target, so `dragFrom == target`. When it ends on N+1, `page == from == target`, so neither branch at CalendarScreen.kt:752-758 does anything. Round-3 CR-1 was a different case: the drag starts on N, snaps back to N, and the pager must follow again to N+1 (`page != target`, the `launch` follow at :755-757). The new test's `moves == [1]` plus "pager rests on N+1" would also pass if this drag snapped back, so it does not pin that path either. It is a useful test for the "drag started past the midpoint is not a swipe" guard, but it does not fix round-4 CR-1. Carried forward as CR-1 below.
- Round-3 CR-1 (P2, snapped-back drag over an arrow slide left pager and view model out of sync) and round-3 CR-2 (P3, mid-slide precondition): resolved in fdb0e4fd1, confirmed in round 4. No production change since.
- Round-2 CR-1, round-1 CR-1, round-1 CR-2: no regression (round 4). Round-2 CR-2 (P3 flicker of a preloaded page): accepted by the parent worker through a task assumption. Not re-reported.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The snap-back regression test still does not assert that the pager snapped back (round-4 CR-1, carried forward)**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarSwipeInteractionTest.kt:188-203`
    - Category: Tests
    - Problem: The test is unchanged since round 4. It accepts any final page, so a fling to N-1 (swipe branch) or a rest on N+1 (no-op branch) also passes, and then the follow-after-snap-back path from round-3 CR-1 is not exercised. The new :246 test starts its drag on the target page and does not reach that path (see "Earlier findings").
    - Why it matters: The only test for the round-3 CR-1 path can stop covering it without failing.
    - Suggested fix: In the :181 test, assert `assertEquals(listOf(1), moves)` and use `assertPagerRestsOn(today.plusDays(1))`. A snap back to N followed by the follow slide to N+1 issues no second move. Optionally, also assert after the gesture, with the clock still paused, that the pager is back on N before the follow.

- [ ] **CR-2 | The "swipes past its target" test cannot detect a move computed from the drag start instead of the target**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarSwipeInteractionTest.kt:283-300`
    - Category: Tests
    - Problem: tests.md says this test covers that "the move is computed from the target (`page - target`), not from the drag start". It does not. Take a buggy `move(page - from)` with from = N, ending on N+2: it issues `move(2)`, the anchor becomes N+3, and the pager then follows to N+3. Every assertion still holds: `moves.first() == 1`, anchor = today + sum(moves), all later moves > 0, anchor >= today+1, and the pager rests on the anchor. The test also accepts ending on the target with no second move, and then the move branch is not exercised at all.
    - Why it matters: The test claims to protect a specific calculation that it cannot tell apart from the wrong one. The swipe-back test (:265) cannot catch this mistake either, because there `from == target`.
    - Suggested fix: Bound the result by what one swipe can reach. From N, a single fling lands at most on N+2, so assert `anchor <= today.plusDays(2)`. The buggy formula gives N+3. Better, make the gesture end on N+2 every time and assert `moves == listOf(1, 1)`.

## Review conclusion

No material issues found. The production code is unchanged and was confirmed correct in round 4. The new past-halfway test is valid and useful, but it does not address round-4 CR-1, which stays open as a P3 together with a new P3 on the strength of the "past its target" test. Both are test-strength improvements; the parent worker decides whether to address them.
