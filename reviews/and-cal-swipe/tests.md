# Test Report

Date: 2026-11-22

Task: and-cal-swipe - Android calendar: swipe horizontally to the previous/next day, week, month

Scope: base 920279b78ad7dc044711bc903ee158d3487c4145 .. HEAD fd4e3ae1d6895dc7dbce36db20145ca56c7aef0b (branch main, local).
Task code commits as named by the parent were 2e0313bb8 and 371e46670; at measurement time they are present as
c0eb4043d (production) and c3e03eca9 (tests), plus fd4e3ae1d (review report only). Production files in scope
(`git diff --stat 920279b78..HEAD -- android`):
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModel.kt`

Uncommitted inputs: the two new, untracked test files listed below (nothing else dirty).

Requirements:
- `tasks/and-cal-swipe.md` acceptance criteria and the two recorded assumptions (network-only preload,
  beyondViewportPageCount 0)
- `AGENTS.md`, `docs/ARCHITECTURE.md` "Coverage"

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarSwipeInteractionTest.kt` (new, Compose/Robolectric)
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModelNeighboursTest.kt` (new, view model)

Existing tests (CalendarSwipeTest, CalendarViewModelTest, CalendarGridScrollTest, CalendarViewModelEdgeCasesTest) were
not changed.

## Tests added or updated

Count: 19 (9 Compose, 10 view model)

## Requirement coverage

Command for all rows: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 swipe pages day/week/month; settled page moves the view model like move(n) | CalendarSwipeTest (existing, 2 tests); new: `a pager swipe over several periods moves by as many days`, `month days can be tapped on the month swiped to` (also asserts the November page after a left swipe) | see above | Pass |
| AC2 vertical scroll still works; mostly vertical gesture keeps period; horizontal gesture does not scroll vertically | CalendarSwipeTest `a vertical swipe scrolls the grid and keeps the period`; new: `a mostly vertical diagonal gesture scrolls the grid and keeps the period`, `a horizontal swipe on the grid pages without scrolling it vertically`, `a short, slow, mostly horizontal drag snaps back without scrolling vertically or moving` | see above | Pass |
| AC3 neighbours preloaded; missing neighbour is an empty grid that fills | CalendarViewModelTest preload test (existing); new: `a neighbour stays an empty, not loaded page until its preload answers, then fills`, `a failed neighbour preload leaves only that neighbour empty`, `a failed load of the shown day preloads no neighbours`, `a preload that answers after a move is not shown for the new period`, `swiping back shows the day left behind at once`, `switching the view drops the neighbours of the previous view`, `the week and month views preload the previous and next week or month grid`, `a page swiped to before its data arrives fills when the data comes` (Compose) | see above | Pass |
| AC4 arrows, Today, range title stay in sync with the pager; agenda unchanged | CalendarSwipeTest arrows test (existing); new: `the previous arrow slides the pager back and Today jumps back from several days away`, `two quick taps on the next arrow end two days later without a move back`, `the agenda view has no pager`, `the agenda preloads no neighbours and ignores moves`, `a move by zero keeps the period and loads nothing` | see above | Pass |
| AC5 taps on entries, day headers, month days on the settled page | new: `day headers and entries can be tapped on the week swiped to`, `month days can be tapped on the month swiped to` | see above | Pass |
| AC5 initial scroll and hidden-entry chips on the settled page | CalendarGridScrollTest (adapted by the task, 11 tests), HiddenEntryChipsTest (8) still pass; no new swipe-specific chip test | see above | Pass (existing tests only; see "Unable to test") |
| AC6 strings EN + DE | No new strings in the diff; lint MissingTranslation is the guard | not run by me (lintDebug) | Not run: no strings changed, lintDebug is the parent's check |
| AC7 ktlintCheck testDebugUnitTest lintDebug, scripts/check.sh | ktlintCheck + agenda unit tests run | see above | Pass for ktlintCheck and agenda tests; full testDebugUnitTest, lintDebug and check.sh not run by me |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*'`

Result:

Pass. 34 agenda test classes, 0 failures, 0 skipped (CalendarSwipeInteractionTest 9/9, CalendarViewModelNeighboursTest
10/10, CalendarSwipeTest 6/6, CalendarViewModelTest 6/6, CalendarGridScrollTest 11/11, CalendarViewModelEdgeCasesTest 26/26).
ktlintCheck passed after `./gradlew ktlintTestSourceSetFormat` (formatting of the new test file only).

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest` (whole Android unit suite)

Result:

Not run: limited time budget; the parent runs it with `.taskcheck`.

## .taskcheck

Result:

Not run (parent runs `sh scripts/check.sh` before done).

## Bugs found

No production defect found that violates an acceptance criterion. Observations (no failing test added, as the
criterion says "usually"):

- Low, CalendarViewModel.kt:520 (loadNeighbours guard `it.anchor == anchor`): the preload result is discarded
  whenever the anchor changed meanwhile, also when the range did not change (the `show` early return at line 399-401
  only updates the anchor and does not cancel or restart the preload). Example: month view with a clamped anchor
  (31 October, move +1 to 30 November, move -1 to 30 October, then Today to 31 October while the preload is pending):
  the neighbours of October stay empty until the next load. Comparing the range (from/to) instead of the anchor
  would avoid it. A swipe in this case still shows an empty page that fills from its own load.
- Low, CalendarScreen.kt:735: an arrow or Today tapped while a drag is in progress is not followed by the pager
  (`!isScrollInProgress`); when the drag settles, the settled-page collector then moves the view model by
  `page - shownPage`, relative to the new period. Not covered by a test (hard to drive deterministically).
- Test note: after a drag that snaps back, the neighbouring page stays composed for a moment, so tags such as
  `calendar-grid-scroll` can match two nodes; the new tests scope lookups to `CalendarTags.page(date)`.

## Coverage

Status: verified (task-scoped, agenda test selection).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (JaCoCo report has no diff intersection here).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-cal-swipe; base
  920279b78ad7dc044711bc903ee158d3487c4145; HEAD fd4e3ae1d6895dc7dbce36db20145ca56c7aef0b; task commits c0eb4043d,
  c3e03eca9 (from parent, renamed after rebase), file scope from `git diff --stat 920279b78..HEAD -- android`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD above plus untracked
  CalendarSwipeInteractionTest.kt sha256 84a054cb9e18be16d81a35f44db2308c481d3d705255870adae34f648466511d and
  CalendarViewModelNeighboursTest.kt sha256 413d05cbf2f233f2abbd22c8d18072eb7f0291825df632b14cd4f539d72cfa99.
- Exact included files; renamed/deleted/excluded files and reasons: CalendarScreen.kt, CalendarViewModel.kt; none
  excluded (test files excluded by definition).
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`;
  `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' createDebugUnitTestCoverageReport --rerun-tasks`
  in `android/`; only the agenda package tests.
- Run start/completion time and exit status: completed 2026-11-22T11:02 local (report mtime 2026-11-22T11:02:07),
  build succeeded (no failure output).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`,
  sha256 90bf24c1033ac22fa5ad3fba7cdc24283165047f9d07db73ca76d1fde3e8eeb5.
- Evidence outputs are fresh and inputs stayed unchanged: `--rerun-tasks` forced a fresh run; no file was edited
  during or after it.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage). Statements are JaCoCo instructions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `CalendarScreen.kt` | 8166/8264 (98.8%) | 259/284 (91.2%) | 158/173 (91.3%) | 683/688 (99.3%) |
| `CalendarViewModel.kt` | 3346/3371 (99.3%) | 267/280 (95.4%) | 70/70 (100%) | 309/309 (100%) |
| Aggregate (summed) | 11512/11635 (98.9%) | 526/564 (93.3%) | 228/243 (93.8%) | 992/997 (99.5%) |

No scoped file is missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalendarViewModel.kt:520` (3 missed instructions, guard else branch) | stale preload discarded | Not reachable through a new load because each load cancels the neighbour job; reachable only via the anchor-only update, see observation above. Left uncovered. |
| `CalendarScreen.kt:735` (1 missed branch, `isScrollInProgress` true) | arrow during a drag | Not deterministic in Robolectric; see observation above. |
| `CalendarScreen.kt` other partial lines (136-186, 262-269, 321-523, 644-649, 810-812, 897-1198) | pre-existing code (callbacks defaults, other views) | Outside the task change. |
| `CalendarViewModel.kt` 33-37, 182-376, 399, 427, 536 | pre-existing code | Outside the task change. |

## Unable to test

- Real-device feel of the swipe (finger following, fling thresholds): Robolectric injects synthetic gestures only;
  needs a manual or instrumented check if the parent wants device evidence.
- Hidden-entry chips specifically on a page reached by swiping: not added (time); existing HiddenEntryChipsTest
  covers the chips for the shown state, which the settled page uses.
- Arrow tapped during an ongoing drag: not deterministic in Robolectric.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found (two low-severity observations reported
for the parent to decide on).
