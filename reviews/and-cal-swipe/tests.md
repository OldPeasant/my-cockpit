# Test Report

Date: 2026-11-22

Task: and-cal-swipe - Android calendar: swipe horizontally to the previous/next day, week, month (round 3, final code)

Scope: base 9bbd634344021b662e25f5aaa4ee8292665edb87 .. final code revision 65ee2d32ea88647b84242d2a56771e86d10fd13f
as given by the parent. When this round ran, local HEAD was 554dc3a661acffd35b1c5d5395097cd9bae519ab (branch main, history
rewritten by the parent). `git diff --stat 65ee2d32e 554dc3a66` lists only `tasks/and-cal-swipe.md`, so the android code
measured here is identical to 65ee2d32e. Task scope: `git diff 9bbd63434 554dc3a66 -- android/`.
Production files in scope:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModel.kt`

Round-3 focus: commit 65ee2d32e (fix for round-2 CR-1). It adds a `dragged` flag in `PeriodPager`: a
`DragInteraction.Start` sets it, the follow-the-view-model effect clears it before its own scroll, and the settled-page
collector only calls `move` when it is set.

Requirements:
- `tasks/and-cal-swipe.md` acceptance criteria and recorded assumptions
- `reviews/and-cal-swipe/fixes.md` "Round 2" (CR-1 fix and its claim that a user drag interrupting an arrow slide still
  counts as a swipe)
- `AGENTS.md`, `docs/ARCHITECTURE.md` "Coverage"

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarSwipeInteractionTest.kt`

## Tests added or updated

Count: 1

- `a swipe that interrupts an arrow slide still moves the view model` (new). Manual clock: tap Next, advance two
  frames so the arrow slide is running, then a real `swipeRight` on the pager, then idle. Asserts the arrow's move
  comes first (`1`), the swipe adds a second, backward move, the anchor equals today plus the sum of the moves, and
  the page of that anchor is displayed. Covers the gap left by 65ee2d32e: the follow effect clears `dragged` right
  before `animateScrollToPage`, so if a regression cleared it after a user drag (or stopped setting it while a slide
  is running), the swipe would settle without a move and the pager would show a period the view model does not have.
  The assertions accept either a one- or two-period swipe back, so they do not depend on fling snap details.
  Passed in 3 separate runs of the class.

Fix behaviour already covered (checked by reading the tests):
- Programmatic settle not read as a swipe: `a second tap on the next arrow after the slide passed halfway ends two days
  later without a move back` (fixer's regression test, failed on the pre-fix code with `[1, 1, -1]`), plus
  `two quick taps on the next arrow ...` and the Today jump in `the previous arrow slides the pager back ...`.
- User drags still move the view model: every real swipe test (CalendarSwipeTest, `a horizontal swipe on the grid
  pages ...`, `day headers and entries can be tapped on the week swiped to`, `month days can be tapped ...`,
  `a page swiped to before its data arrives ...`). They would fail if `dragged` were never set.
- Drag that snaps back during a period change: `a period change during a drag is followed once the drag snaps back`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 swipe pages day/week/month; settled page moves the view model | CalendarSwipeTest (6), `a horizontal swipe on the grid pages without scrolling it vertically`, `day headers and entries can be tapped on the week swiped to`, `month days can be tapped on the month swiped to`, `a swipe that interrupts an arrow slide still moves the view model` | `cd android && ./gradlew ktlintCheck testDebugUnitTest --rerun --tests 'ch.sonensei.cockpit.calendar.agenda.*' createDebugUnitTestCoverageReport` | Pass |
| AC2 vertical scroll vs horizontal swipe separation | CalendarSwipeTest vertical swipe test, `a mostly vertical diagonal gesture scrolls the grid and keeps the period`, `a horizontal swipe on the grid pages without scrolling it vertically`, `a short, slow, mostly horizontal drag snaps back without scrolling vertically or moving` | same | Pass |
| AC3 neighbours preloaded; empty grid fills later | CalendarViewModelNeighboursTest (10), CalendarViewModelTest preload test, `a page swiped to before its data arrives fills when the data comes` | same | Pass |
| AC4 arrows, Today, title in sync with the pager; agenda unchanged | CalendarSwipeTest arrows test, `the previous arrow slides the pager back and Today jumps back from several days away`, `two quick taps on the next arrow end two days later without a move back`, `a second tap on the next arrow after the slide passed halfway ends two days later without a move back`, `a swipe that interrupts an arrow slide still moves the view model`, `a period change during a drag is followed once the drag snaps back`, `a restored screen keeps the period without moving the view model again`, `the agenda view has no pager` | same | Pass |
| AC5 taps, initial scroll, hidden-entry chips on the settled page | `day headers and entries can be tapped on the week swiped to`, `month days can be tapped on the month swiped to`, CalendarGridScrollTest, HiddenEntryChipsTest | same | Pass |
| AC6 strings EN + DE | no strings file in `git diff --stat 9bbd63434 554dc3a66 -- android/` | git diff | Pass (no new strings) |
| AC7 `ktlintCheck testDebugUnitTest lintDebug`, `sh scripts/check.sh` | whole suite | full commands | Not run by me: only ktlintCheck and the agenda package tests ran (as the parent asked); full `testDebugUnitTest`, `lintDebug` and `scripts/check.sh` are left to the parent |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.CalendarSwipeInteractionTest'`
(then twice more with `--rerun -q`)

Result:

Pass: 13 tests, 0 failures, 0 errors, 0 skipped, in all three runs.

Agenda package run (the parent's command, with `--rerun` on the test task so the results are fresh):

`cd android && ./gradlew ktlintCheck testDebugUnitTest --rerun --tests 'ch.sonensei.cockpit.calendar.agenda.*' createDebugUnitTestCoverageReport -q`

Result:

Pass, exit 0. Agenda package: 302 tests, 0 failures, 0 errors, 0 skipped (summed from the 34 result files written
2026-11-22 11:21:29 +0100). CalendarSwipeInteractionTest 13/13, CalendarSwipeTest 6/6, CalendarViewModelNeighboursTest
10/10. ktlintCheck passed, new test included.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run (the parent limited the run to the agenda package; the parent runs the full suite).

## .taskcheck

Result:

Not run (left to the parent worker, who runs `sh scripts/check.sh` before done).

## Bugs found

No production defect found in round 3.

## Coverage

Status: verified (agenda package test selection, final code).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured separately. Method: the lines added in `git diff -U0 9bbd63434 554dc3a66` for the two
files, intersected with the lines JaCoCo reports as executable in report.xml (a line with any covered instruction
counts as covered; branch counters of those lines summed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-cal-swipe; base
  9bbd634344021b662e25f5aaa4ee8292665edb87; final code revision 65ee2d32ea88647b84242d2a56771e86d10fd13f (parent);
  measured HEAD 554dc3a661acffd35b1c5d5395097cd9bae519ab, same android tree as 65ee2d32e (diff only in `tasks/`); file
  scope from `git diff --stat 9bbd63434 554dc3a66 -- android/`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 554dc3a66 plus one uncommitted test file.
  SHA-256 before and after the run: CalendarSwipeInteractionTest.kt
  535dd7b160e8362e4f576fed3967c433d75f12c4019af04828831f191bbf368f, CalendarScreen.kt
  879e3c9d012e8dcab0c51f15657c234a7a51b4c8124dce8e7393d13c62d112c2, CalendarViewModel.kt
  a86780029dfd6c0777ff2441c72a87d449459ce7500c8cbfd70b2ce20e259463. (`reviews/and-cal-swipe/review.md` was also
  modified in the working tree by another helper; it is not a measurement input.)
- Exact included files; renamed/deleted/excluded files and reasons: CalendarScreen.kt, CalendarViewModel.kt; nothing
  renamed, deleted or excluded.
- Provider/version, command, working directory, and test selection: JaCoCo through AGP unit test coverage
  (`createDebugUnitTestCoverageReport`, Gradle 9.8.1); command
  `./gradlew ktlintCheck testDebugUnitTest --rerun --tests 'ch.sonensei.cockpit.calendar.agenda.*' createDebugUnitTestCoverageReport -q`
  in `android/`; tests of package `ch.sonensei.cockpit.calendar.agenda`.
- Run start/completion time and exit status: started after 2026-11-22 11:20:58 +0100, test results written 11:21:29,
  report written 11:21:31; exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`,
  SHA-256 ff6a64d61af37d4013e3e94dec11b26f549afd50443eebc1ca215af24a267496.
- Evidence outputs are fresh and inputs stayed unchanged: yes; `--rerun` forced the test task, report mtime is from
  this run, input hashes identical before and after.
- Final run after latest test additions and production fixes: yes (includes the round-3 test and the 65ee2d32e fix).

Values below use covered/total (percentage); statements are JaCoCo instructions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `CalendarScreen.kt` | 8537/8642 (98.8%) | 335/372 (90.1%) | 164/179 (91.6%) | 688/694 (99.1%) |
| `CalendarViewModel.kt` | 3346/3371 (99.3%) | 267/280 (95.4%) | 70/70 (100%) | 309/309 (100%) |
| Aggregate (summed, both files, none missing) | 11883/12013 (98.9%) | 602/652 (92.3%) | 234/249 (94.0%) | 997/1003 (99.4%) |

Previous parent-run numbers on 1397f2568 for comparison: CalendarScreen.kt lines 684/689, branches 332/368;
CalendarViewModel.kt lines 309/309, branches 267/280. The extra totals in CalendarScreen.kt come from the `dragged`
code added in 65ee2d32e.

Changed-line coverage (task-added executable lines only):

| Changed file | Lines | Branches |
|---|---:|---:|
| `CalendarScreen.kt` | 44/46 (95.7%) | 18/19 (94.7%) |
| `CalendarViewModel.kt` | 49/49 (100%) | 56/59 (94.9%) |
| Aggregate | 93/95 (97.9%) | 74/78 (94.9%) |

Remaining uncovered task code:

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalendarScreen.kt:735`, `CalendarScreen.kt:741` (missed lines) | none: closing braces of the two `LaunchedEffect` lambdas whose `collect` never returns | Unreachable by design (the flows never complete; the effects end by cancellation); no test |
| `CalendarScreen.kt:739` (3/4 branches) | the `dragged && page != latestShownPage` condition; the missed combination is a drag that settles on the page the view model already shows | Outcome is "no move", the same as without the condition; not material. The drag paths that matter (swipe moves, snap back, drag during a period change, drag interrupting an arrow slide) are tested |
| `CalendarViewModel.kt:520` (3/6 branches) | the guard that drops a neighbour preload when view, anchor or zone changed meanwhile | Unchanged since round 1; TW-1 was judged invalid by the fixer (the case cannot be reached through the view model's API); left uncovered |
| `CalendarScreen.kt` other missed lines/branches (141, 271, 427, 525 and partial lines outside the task's added lines) | pre-existing code | Outside the task change |

## Unable to test

- Real-device swipe feel (finger following, fling thresholds): Robolectric gestures only; needs a manual or
  instrumented check if device evidence is wanted.
- I did not run the full `testDebugUnitTest`, `lintDebug` or `scripts/check.sh` (outside the parent's requested
  command); the parent runs them.

## Summary

Tests added and all validation passed. One test was added for the only real gap in the round-2 fix: a user drag that
interrupts an arrow slide still moves the view model. ktlintCheck and the 302 agenda package tests pass on the final
code. Fresh task-scoped coverage: CalendarScreen.kt lines 688/694, branches 335/372; CalendarViewModel.kt lines
309/309, branches 267/280. The only uncovered task-added lines are unreachable closing braces. No production defects
found.
