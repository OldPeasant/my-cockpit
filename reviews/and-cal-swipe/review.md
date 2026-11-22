# Code Review

Date: 2026-11-22

Task: and-cal-swipe - Android calendar: swipe horizontally to the previous/next day, week, month

Scope: committed task changes under `android/` between origin/main and HEAD (`git diff origin/main...HEAD -- android`), static review only (Gradle was not run, as instructed by the parent worker). Untracked test files added by the test-writer in parallel (`CalendarSwipeInteractionTest.kt`, `CalendarViewModelNeighboursTest.kt`) are uncommitted and not part of this review.

Requirements:
- tasks/and-cal-swipe.md (acceptance criteria and recorded assumptions)
- SPEC.md, docs/ARCHITECTURE.md, AGENTS.md project notes
- Existing calendar code conventions (CalendarScreen.kt, CalendarViewModel.kt, CockpitShell.kt navigation, AndroidManifest.xml)

Git range:
- Branch: main (local, task work not yet pushed)
- HEAD: c3e03eca9e6b1403e0237700efb86110b0d4fc5b
- Base/start: 64469e1811fef8e3232ae139b6b0a829ce859c1e (origin/main; the task commits were rebased from 2e0313bb8 / 371e46670 onto it, same content)
- Commits reviewed: 2 (c0eb4043d day/week/month pager with preloaded neighbours; c3e03eca9 swipe and preload tests, adapt grid scroll tests to pages)

Summary: 0 P0, 1 P1, 2 P2, 0 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Restored pager page is interpreted as a swipe: after rotation or returning to the tab the period jumps forward again**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt:721`
    - Category: Correctness
    - Problem: `base` is kept with plain `remember { state.anchor }`, while `rememberPagerState(...)` (line 724) is saveable and restores its `currentPage`. After the user moves k periods (by swiping or with the arrows, which make the pager follow to `PAGER_START + k`), an activity recreation (rotation, dark mode or locale change; `MainActivity` declares no `configChanges`) or a return to the Calendar tab via `restoreState = true` (`CockpitShell.kt:326-328`) recreates the composable: the view model survives with anchor A+k, `base` becomes A+k, so `shownPage = PAGER_START`, but the pager comes back on `PAGER_START + k`. The `settledPage` collector (lines 728-731) emits that initial value immediately and calls `move(k)`, so the view model advances another k periods; at the same time the follow effect (line 734) scrolls the pager back to `PAGER_START`, which can produce a further compensating move. After process death the view model starts at today and the restored page moves it k periods from today.
    - Why it matters: Criterion 1 ("after settling, the view model's period is the new one") and criterion 4 ("stay in sync with the pager") are violated in a normal flow: e.g. go to the next week with the arrow, rotate the phone, and the calendar shows the week after. No swipe happened.
    - Suggested fix: make the page origin and the pager state survive together or not at all: either keep `base` in `rememberSaveable` (e.g. as an ISO string) so the restored page maps to the same period, or create the pager state with `remember { PagerState(initialPage = shownPage) { Int.MAX_VALUE } }` so it is not restored. In addition the collector could ignore the initial emission (or only react to settles that follow a user scroll). Add a Compose test with `StateRestorationTester` that moves a period, emulates a save/restore and asserts no `move` is issued.

## P2 Medium

- [ ] **CR-2 | The pager does not follow a period change made while it is still scrolling, and never re-syncs**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt:735`
    - Category: Correctness
    - Problem: `LaunchedEffect(shownPage)` only scrolls the pager when `!pagerState.isScrollInProgress`; if a scroll is in progress the effect simply ends and is not re-run until `shownPage` changes again. Example: the user drags a little and releases (pager snaps back to page N), and during that snap-back taps Today or an arrow. The view model moves, the effect is skipped, the pager settles on N again; since `settledPage` did not change, the collector does not emit either. The header now shows the new period while the pager keeps page N, rendered through `pageState` as a neighbour (preloaded data or an empty grid). The next swipe then computes `move(page - latestShownPage)` from that stale page and jumps by an unexpected number of periods.
    - Why it matters: Criterion 4 requires arrows and Today to stay in sync with the pager; the desync persists until the next interaction and the subsequent swipe lands on a surprising period.
    - Suggested fix: when a scroll is in progress, wait for it to end and then re-check, e.g. `snapshotFlow { pagerState.isScrollInProgress }.first { !it }` before comparing `pagerState.currentPage` with the latest `shownPage`; or derive a single sync loop that reconciles settled page and view-model period in both directions.

- [ ] **CR-3 | Pager/view-model synchronisation and preload staleness paths are untested**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarSwipeTest.kt:83`
    - Category: Tests
    - Problem: The committed tests cover a single swipe per direction, one arrow tap and the day-view preload happy path (`CalendarViewModelTest.kt`, new test at line 118). Not covered: state restoration (CR-1), Today or a multi-period jump (the `scrollToPage` branch at line 739), a change during an ongoing scroll (CR-2), a neighbour preload response arriving after the user moved on (the guard at `CalendarViewModel.kt:520`), a failed neighbour load, and week/month neighbour ranges in the view model.
    - Why it matters: Criterion 7 asks for tests of the new behaviour; the regression-prone parts (sync between two state owners and stale async results) are exactly the untested ones, and CR-1 would have been caught by a restoration test.
    - Suggested fix: add a `StateRestorationTester` test, a Today/far-jump test asserting no extra `move`, and view-model tests where the neighbour `occurrences` call is suspended until after `move(1)` and then answers (state must keep the new period's neighbours), plus a failed neighbour call. The test-writer's uncommitted files may cover part of this once committed.

## P3 Low

None.

## Review conclusion

Material issues found; fix before completing the task.
