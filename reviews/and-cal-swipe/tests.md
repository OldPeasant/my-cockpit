# Test Report

Date: 2026-11-22

Task: and-cal-swipe - Android calendar: swipe horizontally to the previous/next day, week, month (round 4, final code)

Scope: base 9bbd634344021b662e25f5aaa4ee8292665edb87 .. final code revision fdb0e4fd100efe139c82d353ef4c913c17067343
as given by the parent (local HEAD = fdb0e4fd1, branch main). Task scope: `git diff 9bbd63434 fdb0e4fd1 -- android/`.
Production files in scope:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModel.kt`

Round-4 focus: commit fdb0e4fd1 (fix for round-3 CR-1, CR-2). `PeriodPager` now has one collector over
`(isScrollInProgress, shownPage)`; a `DragInteraction.Start` records the drag start page (`dragFrom`); when the pager
rests after a scroll, the view model moves only if a drag ended on a page other than its start page and the shown page,
otherwise the pager follows the view model in a child job.

Requirements:
- `tasks/and-cal-swipe.md` acceptance criteria and recorded assumptions
- `reviews/and-cal-swipe/review.md` round 3 (CR-1, CR-2) and earlier `tests.md` rounds
- `AGENTS.md`, `docs/ARCHITECTURE.md` "Coverage"

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarSwipeInteractionTest.kt`

## Tests added or updated

Count: 3 (plus two private helpers, `slidePastHalfway` and `assertPagerRestsOn`)

- `a drag that catches an arrow slide past halfway and lets go ends on the arrow's day without another move`: the drag
  starts when the pager's current page is already the arrow's target (`dragFrom == target`), and barely moves it.
  Expects `moves == [1]`, anchor today+1, and that page exactly filling the pager. Guards the `page != from` /
  `page != target` conditions against a drag started past the midpoint being read as a swipe.
- `a drag that catches an arrow slide past halfway and swipes back moves the view model back`: same start, then a real
  `swipeRight`. Expects a second, negative move, anchor = today + sum(moves), anchor before today+1, pager resting on
  the anchor page. Covers `dragFrom` = target page with the end page differing from both (move relative to the target).
- `a drag that catches an arrow slide and swipes past its target moves the view model on from the target`: drag started
  early (from = start page), `swipeLeft`. Expects no backward move, anchor at or past today+1, anchor =
  today + sum(moves), pager resting on the anchor page. Covers an end page different from both start and target; the
  move is computed from the target (`page - target`), not from the drag start. Assertions tolerate fling snap details.

Already covered by the parent's fdb0e4fd1 test (checked by reading): drag catching an early slide and snapping back
(`a drag that catches an arrow slide and snaps back keeps the pager and the view model in sync`), and the CR-2
precondition in the second-tap test.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 swipe pages day/week/month; settled page moves the view model | CalendarSwipeTest (6), `a horizontal swipe on the grid pages without scrolling it vertically`, `day headers and entries can be tapped on the week swiped to`, `month days can be tapped on the month swiped to`, `a swipe that interrupts an arrow slide still moves the view model`, the three new round-4 tests | `cd android && ./gradlew ktlintCheck testDebugUnitTest --rerun --tests 'ch.sonensei.cockpit.calendar.agenda.*' createDebugUnitTestCoverageReport -q` | Pass |
| AC2 vertical scroll vs horizontal swipe separation | CalendarSwipeTest vertical swipe test, `a mostly vertical diagonal gesture scrolls the grid and keeps the period`, `a horizontal swipe on the grid pages without scrolling it vertically`, `a short, slow, mostly horizontal drag snaps back without scrolling vertically or moving` | same | Pass |
| AC3 neighbours preloaded; empty grid fills later | CalendarViewModelNeighboursTest (10), CalendarViewModelTest preload test, `a page swiped to before its data arrives fills when the data comes` | same | Pass |
| AC4 arrows, Today, title in sync with the pager; agenda unchanged | CalendarSwipeTest arrows test, `the previous arrow slides ...`, `two quick taps on the next arrow ...`, `a second tap on the next arrow after the slide passed halfway ...`, `a drag that catches an arrow slide and snaps back ...`, the three new round-4 tests, `a swipe that interrupts an arrow slide ...`, `a period change during a drag is followed once the drag snaps back`, `a restored screen keeps the period ...`, `the agenda view has no pager` | same | Pass |
| AC5 taps, initial scroll, hidden-entry chips on the settled page | `day headers and entries can be tapped on the week swiped to`, `month days can be tapped on the month swiped to`, CalendarGridScrollTest, HiddenEntryChipsTest | same | Pass |
| AC6 strings EN + DE | no strings file in `git diff --stat 9bbd63434 fdb0e4fd1 -- android/` | git diff | Pass (no new strings) |
| AC7 `ktlintCheck testDebugUnitTest lintDebug`, `sh scripts/check.sh` | whole suite | full commands | Not run by me: only ktlintCheck and the agenda package tests ran (as the parent asked); full `testDebugUnitTest`, `lintDebug` and `scripts/check.sh` are left to the parent |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.CalendarSwipeInteractionTest' -q`

Result:

Pass: 17 tests, 0 failures, 0 errors, 0 skipped (result file timestamp 2026-11-22T10:42:04Z). ktlintCheck passed.

Agenda package (the parent's command, `--rerun` so results are fresh):

`cd android && ./gradlew ktlintCheck testDebugUnitTest --rerun --tests 'ch.sonensei.cockpit.calendar.agenda.*' createDebugUnitTestCoverageReport -q`

Result:

Pass. Output contained only two JVM CDS warnings, no failure. Agenda package: 306 tests, 0 failures, 0 errors,
0 skipped (summed from the result XML files of this run). CalendarSwipeInteractionTest 17/17 again (second run of the
new tests).

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run (the parent limited the run to the agenda package; the parent runs the full suite).

## .taskcheck

Result:

Not run (left to the parent worker, who runs `sh scripts/check.sh` before done).

## Bugs found

No production defect found in round 4.

## Coverage

Status: verified (agenda package test selection, final code fdb0e4fd1 plus the uncommitted round-4 tests).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured separately. Method: lines added in `git diff -U0 9bbd63434 fdb0e4fd1` for the two
files, intersected with lines JaCoCo reports as executable in report.xml (a line with any covered instruction counts as
covered; branch counters of those lines summed). Script: `.tmp/acs4_cov.py` (Git-ignored).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-cal-swipe; base
  9bbd634344021b662e25f5aaa4ee8292665edb87; measured HEAD fdb0e4fd100efe139c82d353ef4c913c17067343 (= final code
  revision given by the parent); file scope from `git diff --stat 9bbd63434 fdb0e4fd1 -- android/`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD fdb0e4fd1 plus one uncommitted test file.
  SHA-256, identical before and after the run: CalendarSwipeInteractionTest.kt
  cd7ff11d03025f3c97f5113c63ae50312c4d0c188d08646f888582b34c386c94, CalendarScreen.kt
  d786defc79b4aee31fc571c4a71f806a5db669b69768ad2910223f9c8ef29f72, CalendarViewModel.kt
  a86780029dfd6c0777ff2441c72a87d449459ce7500c8cbfd70b2ce20e259463. `git status --short` showed only the test file.
- Exact included files; renamed/deleted/excluded files and reasons: CalendarScreen.kt, CalendarViewModel.kt; nothing
  renamed, deleted or excluded.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP unit test coverage
  (`createDebugUnitTestCoverageReport`); command as above in `android/`; tests of package
  `ch.sonensei.cockpit.calendar.agenda`.
- Run start/completion time and exit status: started after 2026-11-22 11:42:18 +0100, report written
  11:42:48 +0100; no failure output (the tool did not show the numeric exit code; all 306 results passed and the report
  was regenerated).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`,
  SHA-256 230d3ff3b6e42b83e6b46dbd68bb273e716b675141871284da5d0bd08955a55d.
- Evidence outputs are fresh and inputs stayed unchanged: yes; `--rerun` forced the test task, report mtime is from
  this run, input hashes identical before and after.
- Final run after latest test additions and production fixes: yes (includes fdb0e4fd1 and the round-4 tests).

Values below use covered/total (percentage); statements are JaCoCo instructions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `CalendarScreen.kt` | 8547/8652 (98.8%) | 345/382 (90.3%) | 162/177 (91.5%) | 694/700 (99.1%) |
| `CalendarViewModel.kt` | 3346/3371 (99.3%) | 267/280 (95.4%) | 70/70 (100%) | 309/309 (100%) |
| Aggregate (summed, both files, none missing) | 11893/12023 (98.9%) | 612/662 (92.4%) | 232/247 (93.9%) | 1003/1009 (99.4%) |

Round 3 for comparison (65ee2d32e): CalendarScreen.kt lines 688/694, branches 335/372.

Changed-line coverage (task-added executable lines only):

| Changed file | Lines | Branches |
|---|---:|---:|
| `CalendarScreen.kt` | 50/52 (96.2%) | 28/29 (96.6%) |
| `CalendarViewModel.kt` | 49/49 (100%) | 56/59 (94.9%) |
| Aggregate | 99/101 (98.0%) | 84/88 (95.5%) |

The new decision line `CalendarScreen.kt:752` (`scrollEnded && from != null && page != from && page != target`) has all
branches covered, as do 748, 754 and 756.

Remaining uncovered task code:

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalendarScreen.kt:738`, `CalendarScreen.kt:760` (missed lines) | none: closing braces of the two `LaunchedEffect` lambdas whose `collect` never returns | Unreachable by design (flows never complete; effects end by cancellation); no test |
| `CalendarScreen.kt:736` (3/4 branches) | `dragFrom == null` false: a second drag start before the pager came to rest keeps the first drag's start page | Needs two drag starts within one scroll; Robolectric's single-pointer gestures end the scroll in between. Effect only matters for the "ended on its start page" check; not material |
| `CalendarViewModel.kt:520` (3/6 branches) | guard dropping a neighbour preload when view, anchor or zone changed meanwhile | Unchanged since round 1; TW-1 judged invalid by the fixer (not reachable through the view model API) |
| `CalendarScreen.kt` other missed lines/branches (141, 271, 427, 525 and partial lines outside the task's added lines) | pre-existing code | Outside the task change |

## Unable to test

- Real-device swipe feel (finger following, fling thresholds): Robolectric gestures only; needs a manual or
  instrumented check if device evidence is wanted.
- Full `testDebugUnitTest`, `lintDebug` and `scripts/check.sh` not run by me (outside the parent's requested command).

## Summary

Tests added and all validation passed. Three tests cover the new drag-decision paths in fdb0e4fd1 that the parent's test
did not: a drag started past the slide midpoint that lets go (no extra move), the same drag swiping back (move relative
to the target), and a drag swiping past the arrow's target from the start page. ktlintCheck and the 306 agenda package
tests pass on the final code. Fresh task-scoped coverage: CalendarScreen.kt lines 694/700, branches 345/382;
CalendarViewModel.kt lines 309/309, branches 267/280. No production defects found.
