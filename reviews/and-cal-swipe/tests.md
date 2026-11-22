# Test Report

Date: 2026-11-22

Task: and-cal-swipe - Android calendar: swipe horizontally to the previous/next day, week, month (round 2, final code)

Scope: base 9bbd634344021b662e25f5aaa4ee8292665edb87 .. HEAD 0533f20d1884c58bff4a4f639da6f5e61ecd20b1 (branch main, local,
clean working tree). Task code commits as given by the parent: f509c20e0 (production), 588a17bb6 (tests), 6d3b12c45
(tests + round-1 report), 1397f2568 (fixes for CR-1/CR-2 in CalendarScreen.kt plus two tests). The remaining commits in
the range are board/report commits (5665bd80f, f720d5d46, 9a787e0af) and the merge 0533f20d1 of `origin/wip/and-cal-swipe`.
Production files in scope (`git diff --stat 9bbd63434..HEAD -- android`):
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModel.kt`

Round-2 focus: the fix commit 1397f2568 (PagerState no longer saveable; the follow-the-view-model effect waits for a
running scroll to end instead of skipping).

Requirements:
- `tasks/and-cal-swipe.md` acceptance criteria and recorded assumptions
- `reviews/and-cal-swipe/review.md` (CR-1, CR-2, CR-3) and `reviews/and-cal-swipe/fixes.md`
- `AGENTS.md`, `docs/ARCHITECTURE.md` "Coverage"

## Test files changed

None in round 2. Test files of the task (from earlier rounds, unchanged now):
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarSwipeInteractionTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModelNeighboursTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarSwipeTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarGridScrollTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModelEdgeCasesTest.kt`

## Tests added or updated

Count: 0 (round 2)

Review of the fix commit 1397f2568 against its tests (by reading the code and the tests; see "Focused test execution"
for why they were not re-run by me):

- CR-1, non-saveable PagerState (`CalendarScreen.kt` PeriodPager, `remember { PagerState(currentPage = shownPage) ... }`):
  covered by `a restored screen keeps the period without moving the view model again`. It uses
  `StateRestorationTester.emulateSavedInstanceStateRestore()` with a state holder that outlives the composition (like
  the view model), after a NEXT tap. With the old `rememberPagerState`, the restored page (PAGER_START + 1) would sit
  next to a recreated `base` equal to the new anchor (shownPage = PAGER_START), and the settled-page collector would
  call `move(+1)` again; the test asserts `moves == [1]`, the anchor and the displayed page, so it fails without the fix.
- CR-2, follow after the scroll ends (`snapshotFlow { isScrollInProgress }.first { !it }`): covered by
  `a period change during a drag is followed once the drag snaps back`. It holds a short drag, changes the anchor (as
  an arrow or Today would), then releases. With the old `!isScrollInProgress` skip, the pager would snap back to the
  old page, and the settled-page collector would emit `move(-1)` relative to the new period; the test asserts no move,
  the new anchor, the new page displayed and the old page gone, so it fails without the fix.
- Not separately tested: the same situation ending in a completed swipe or fling instead of a snap-back. The code path
  is the same wait-then-catch-up effect; the settled-page collector then moves relative to the new period, which no
  acceptance criterion defines further. Judged not a real gap; no test added (keep it small, as asked).

## Requirement coverage

Rows reflect the test suite on the final code. Result column: what is recorded as having run; my own round-2 run was
blocked (see below), so the last observed results come from the fixer's run of the agenda tests on the fix code.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 swipe pages day/week/month; settled page moves the view model | CalendarSwipeTest (2), `a pager swipe over several periods moves by as many days`, `month days can be tapped on the month swiped to` | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*'` | Not run by me in round 2: Gradle denied. Last observed Pass (round 1 and fixer run) |
| AC2 vertical scroll vs horizontal swipe separation | CalendarSwipeTest vertical swipe test, `a mostly vertical diagonal gesture ...`, `a horizontal swipe on the grid pages without scrolling it vertically`, `a short, slow, mostly horizontal drag snaps back ...` | same | Not run by me in round 2: Gradle denied. Last observed Pass |
| AC3 neighbours preloaded; empty grid fills later | CalendarViewModelNeighboursTest (10), CalendarViewModelTest preload test, `a page swiped to before its data arrives fills when the data comes` | same | Not run by me in round 2: Gradle denied. Last observed Pass |
| AC4 arrows, Today, title in sync with the pager; agenda unchanged | CalendarSwipeTest arrows test, `the previous arrow slides the pager back and Today jumps back ...`, `two quick taps on the next arrow ...`, `the agenda view has no pager`, `a period change during a drag is followed once the drag snaps back` (CR-2), `a restored screen keeps the period without moving the view model again` (CR-1) | same | Not run by me in round 2: Gradle denied. Fixer run: CalendarSwipeInteractionTest 11/11 Pass |
| AC5 taps, initial scroll, hidden-entry chips on the settled page | `day headers and entries can be tapped on the week swiped to`, `month days can be tapped on the month swiped to`, CalendarGridScrollTest (11), HiddenEntryChipsTest | same | Not run by me in round 2: Gradle denied. Last observed Pass |
| AC6 strings EN + DE | no strings changed (`git diff --stat 9bbd63434..HEAD -- android` lists no strings file) | git diff | Pass (no new strings) |
| AC7 ktlintCheck testDebugUnitTest lintDebug, scripts/check.sh | whole suite | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`, `sh scripts/check.sh` | Not run by me: Gradle denied; the parent must run them |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' createDebugUnitTestCoverageReport --rerun-tasks`
(also tried as `./android/gradlew -p android ...`)

Result:

Not run: the sandbox denied every Gradle invocation in this helper session ("Permission to use Bash has been denied"),
while plain git commands worked. No output was produced.

Last observed result (not my run, recorded for context only): `android/app/build/test-results/testDebugUnitTest/`
files written 2026-11-22 11:07:13 +0100, 24 seconds before commit 1397f2568 (11:07:31), i.e. the fixer's run on the
fix code: CalendarSwipeInteractionTest 11 tests, 0 failures, 0 errors, 0 skipped, including both fix tests. The parent
should re-run the focused command on HEAD before recording evidence.

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest`

Result:

Not run (Gradle denied in this session).

## .taskcheck

Result:

Not run (Gradle denied; `sh scripts/check.sh` runs Gradle). The parent runs it before done.

## Bugs found

No production defect found in round 2. Round-1 observation TW-2 (arrow during a drag not followed) is addressed by
1397f2568 and now covered by the CR-2 test. Round-1 observation TW-1 (preload guard compares the anchor) was judged
invalid by the fixer (see fixes.md); not re-raised.

## Coverage

Status: unavailable for the final code. The coverage command could not be run (Gradle denied in this helper session).
The only existing report predates the fix commit and is therefore stale for the final revision.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-cal-swipe; base
  9bbd634344021b662e25f5aaa4ee8292665edb87; HEAD 0533f20d1884c58bff4a4f639da6f5e61ecd20b1; task commits f509c20e0,
  588a17bb6, 6d3b12c45, 1397f2568 from the parent; file scope from `git diff --stat 9bbd63434..HEAD -- android`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD above, clean working tree (`git status --short`
  empty); no uncommitted inputs.
- Exact included files; renamed/deleted/excluded files and reasons: CalendarScreen.kt, CalendarViewModel.kt; none
  excluded.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; intended
  command above in `android/`, agenda package tests. Not executed.
- Run start/completion time and exit status: no run (command refused by the sandbox before starting).
- Generated report paths and report hashes/run identifier: none generated. Existing
  `android/app/build/reports/coverage/test/debug/report.xml` has mtime 2026-11-22 11:02:07 +0100, before the fix commit
  (11:07:31); it is the round-1 measurement and is stale for CalendarScreen.kt.
- Evidence outputs are fresh and inputs stayed unchanged: not applicable (no fresh output).
- Final run after latest test additions and production fixes: no, Gradle denied.

Round-1 measurement on the pre-fix revision, kept for reference only (NOT the final code; CalendarScreen.kt changed by
1397f2568, CalendarViewModel.kt did not change after round 1). Values use covered/total (percentage); statements are
JaCoCo instructions.

| Changed file (pre-fix revision) | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `CalendarScreen.kt` | 8166/8264 (98.8%) | 259/284 (91.2%) | 158/173 (91.3%) | 683/688 (99.3%) |
| `CalendarViewModel.kt` | 3346/3371 (99.3%) | 267/280 (95.4%) | 70/70 (100%) | 309/309 (100%) |
| Aggregate (summed) | 11512/11635 (98.9%) | 526/564 (93.3%) | 228/243 (93.8%) | 992/997 (99.5%) |

Significant gaps (from round 1, updated by reading the final code):

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalendarScreen.kt` PeriodPager follow effect (round 1: missed `isScrollInProgress` branch at line 735) | arrow/Today during a drag | The branch was replaced by the wait-for-scroll-end in 1397f2568 and is exercised by `a period change during a drag is followed once the drag snaps back`; expected covered now, not re-measured |
| `CalendarViewModel.kt:520` guard else branch | stale preload discarded | Unchanged since round 1; TW-1 judged invalid by the fixer; left uncovered |
| `CalendarScreen.kt` other partial lines (pre-existing callbacks defaults, other views) | pre-existing code | Outside the task change |

Follow-up for the parent: run `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' createDebugUnitTestCoverageReport`
and read the CalendarScreen.kt / CalendarViewModel.kt sourcefile counters from the report XML if final-revision numbers
are wanted.

## Unable to test

- Running any Gradle task (tests, ktlintCheck, coverage) in this helper session: denied by the sandbox. Needs the parent
  to run them.
- Real-device swipe feel (finger following, fling thresholds): Robolectric gestures only; manual or instrumented check
  if device evidence is wanted.
- Arrow during a drag that ends in a completed swipe or fling (rather than a snap-back): same code path as the tested
  snap-back; no criterion defines the resulting period further; not tested.

## Summary

No additional tests were necessary for the reviewed scope: both fix behaviors of 1397f2568 (non-saveable PagerState,
follow after the scroll ends) already have regression tests that fail without the fix. Validation and final-revision
coverage could not be run here because Gradle is denied in this helper session; the parent must run the focused tests,
ktlintCheck and the full check on HEAD.
