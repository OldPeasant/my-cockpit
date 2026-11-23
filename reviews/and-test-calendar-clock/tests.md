# Test Report

Date: 2026-11-23

Task: and-test-calendar-clock - Android: calendar tests depend on the real time of day (fix clock)

Scope: base 3a3055d4978dfd8791f6fb81feb2b8eef588a02e, measured HEAD 8f9a8d4f6493c1ce316f0899f0aa901c6558a900.
Task-owned code commits (after the parent's rebase onto board commits): 4bd7898f63bcc380b12ee9df7236acdf0449fcc6
"fixed now clock in calendar grid tests" (originally 82c8efece) and 8f9a8d4f6493c1ce316f0899f0aa901c6558a900
"vertical gesture test on a day that opens at the default hour" (originally e1cab946a). The other commits in the
range (d9a1af79a, c77ab7197, 90f29251d, 6cfc9276a) only touch `tasks/and-test-calendar-clock.md`.
`git diff 3a3055d49..HEAD -- android/app/src/main` is empty. Working tree clean when inspected.

Requirements:
- `tasks/and-test-calendar-clock.md` acceptance criteria
- `AGENTS.md` (project notes, test commands)
- production behavior read for context: `firstVisibleHour`, `TimeGrid` and `NowLine` in
  `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt` (the only readers of `LocalNowClock`)

## Test files changed

By the task commits (parent):

- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarSwipeInteractionTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarSwipeTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreensStatesTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarLongPressInteractionTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreensCachedTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarOverlayMonthScreensTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarRouteResumeTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/CalendarWithEditorTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/CalendarWithEditorDeleteTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/CalendarWithEditorLongPressTest.kt`

By the test-writer: none. No real gap was found (see below), so no test was added.

## Tests added or updated

Count: 0 by the test-writer. The parent's commits wrap the content of the 10 classes above in
`CompositionLocalProvider(LocalNowClock provides nowClock)` with
`nowClock = Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), utc)`, and move
`CalendarSwipeInteractionTest > a mostly vertical diagonal gesture scrolls the grid and keeps the period` to
`today.plusDays(5)`: that day is not `today`, so `firstVisibleHour` returns the default hour regardless of "now"
and the grid always has room to scroll down.

## Survey of calendar Compose tests

Every file under `android/app/src/test/java/ch/sonensei/cockpit/calendar` that uses a compose rule / `setContent`
was checked. `LocalNowClock` is read only by `TimeGrid` (initial scroll position on today) and `NowLine`, both drawn
only in the DAY and WEEK views. `CalendarTags.TIME_GRID`, `GRID_SCROLL` and `NOW` are referenced only by
CalendarGridScrollTest, CalendarScreenInAppTest, CalendarScreensTest, CalendarLongPressInteractionTest,
CalendarSwipeInteractionTest and HiddenEntryChipsTest (whole `android/app/src/test` tree searched).

| Test class | Renders day/week grid or now line? | Clock | Result |
|---|---|---|---|
| CalendarSwipeInteractionTest | yes | fixed by this task | changed |
| CalendarSwipeTest | yes | fixed by this task | changed |
| CalendarScreensStatesTest | yes | fixed by this task | changed |
| CalendarLongPressInteractionTest | yes | fixed by this task | changed |
| CalendarScreensCachedTest | yes | fixed by this task | changed |
| CalendarOverlayMonthScreensTest | yes (day/week cases) | fixed by this task | changed |
| CalendarRouteResumeTest | yes (CalendarRoute) | fixed by this task | changed |
| CalendarWithEditorTest | yes | fixed by this task | changed |
| CalendarWithEditorDeleteTest | yes | fixed by this task | changed |
| CalendarWithEditorLongPressTest | yes | fixed by this task | changed |
| CalendarGridScrollTest | yes | already fixed before the task | unchanged |
| CalendarScreensTest | yes | already fixed before the task | unchanged |
| HiddenEntryChipsTest | yes | already fixed before the task | unchanged |
| CalendarScreenInAppTest | yes (MainActivity via Hilt) | system clock | unchanged: only asserts TIME_GRID is displayed, which holds at any time of day (checked by the parent, confirmed by reading) |
| CalendarAgendaInfiniteScrollTest | no: `view = CalendarView.AGENDA` only | none needed | unchanged |
| CalendarAgendaPositionTest | no: AGENDA only | none needed | unchanged |
| CalendarAgendaStartTest | no: AGENDA only | none needed | unchanged |
| CalendarMonthLayoutTest | no: `view = CalendarView.MONTH`; `openDay` is a recording callback, the view never switches | none needed | unchanged |
| BirthdayContactTest | no: renders `BirthdayContact` only | none needed | unchanged |
| EventEditorScreenTest | no: editor screen only | none needed | unchanged |
| EventEditorExtrasScreenTest | no: editor screen only | none needed | unchanged |
| EventEditorDeleteScreenTest | no: editor screen only | none needed | unchanged |

The remaining files in the folder (view models, repositories, caches, grouping, strings) are not Compose tests. View
models take an injected `Clock` already. Conclusion: no other calendar Compose test renders the grid or now line
with the system clock in a time-dependent way.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: CalendarSwipeInteractionTest has a fixed LocalNowClock | CalendarSwipeInteractionTest (17 tests) | `sh .tmp/runclk.sh 0800b` (parent) | Pass (17/17) |
| AC2: other grid tests checked, time-dependent ones get a clock, listed here | the 10 changed classes; survey table above | `sh .tmp/runclk.sh 0800b` (parent); survey by reading/grep (test-writer) | Pass (105/105); survey found no further gap |
| AC3: changed classes pass with clock at 23:30, 00:30 and the final 08:00 | the 10 changed classes, 105 tests | `sh .tmp/runclk.sh 2330b`, `0030b`, `0800b` (parent, clock edited temporarily for the first two) | Pass, 105 tests each run |
| AC4: test code only | `git diff 3a3055d49..HEAD -- android/app/src/main` | git (test-writer) | Pass: empty diff |
| AC5: `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` | full Android check | not run by the test-writer (Gradle is denied to this sub-agent) | Not run: parent must run it |

## Focused test execution

Command (parent's script `.tmp/runclk.sh`, from repo root):

`cd android && ./gradlew testDebugUnitTest --tests '*CalendarSwipeInteractionTest' --tests '*CalendarSwipeTest' --tests '*CalendarScreensStatesTest' --tests '*CalendarLongPressInteractionTest' --tests '*CalendarScreensCachedTest' --tests '*CalendarOverlayMonthScreensTest' --tests '*CalendarRouteResumeTest' --tests '*CalendarWithEditorTest' --tests '*CalendarWithEditorDeleteTest' --tests '*CalendarWithEditorLongPressTest'`

Result:

Clock runs recorded by the parent (logs in `.tmp/clk-*.log`, 2026-11-23 local time):

| Run | nowClock | Code | Result |
|---|---|---|---|
| clk-0800 (20:21:01) | 08:00 UTC | first commit only | BUILD SUCCESSFUL, 105 tests |
| clk-2330 (20:21:33) | 23:30 UTC | first commit only | 105 tests, 1 failed: the vertical diagonal gesture test, `AssertionError: the grid scrolled down` (the original flake, reproduced deterministically) |
| clk-0030 (20:22:03) | 00:30 UTC | first commit only | BUILD SUCCESSFUL |
| clk-2330b (20:23:08) | 23:30 UTC | both commits | BUILD SUCCESSFUL, 105 tests |
| clk-0030b (20:22:41) | 00:30 UTC | both commits | BUILD SUCCESSFUL, 105 tests |
| clk-0800b (20:23:37) | 08:00 UTC (final) | both commits | BUILD SUCCESSFUL, 105 tests |

Pass. The test-writer confirmed the latest JUnit XML results in `android/app/build/test-results/testDebugUnitTest/`
(written 20:23:37, matching clk-0800b): CalendarLongPressInteractionTest 21, CalendarOverlayMonthScreensTest 28,
CalendarSwipeInteractionTest 17, CalendarScreensStatesTest 14, CalendarWithEditorTest 7, CalendarSwipeTest 6,
CalendarScreensCachedTest 4, CalendarWithEditorDeleteTest 4, CalendarWithEditorLongPressTest 3,
CalendarRouteResumeTest 1 = 105 tests, 0 failures, 0 errors, 0 skipped.
The task commits were later rebased (committer time 20:24:17) onto board-only commits; the test file contents are
the ones that were measured (no `android/` change outside the two task commits in the range).

The test-writer attempted one extra focused Gradle run (the 10 classes plus CalendarAgendaInfiniteScrollTest,
CalendarAgendaPositionTest, CalendarAgendaStartTest and CalendarMonthLayoutTest); the Gradle command was denied by
the sub-agent's permissions, so it did not run.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run by the test-writer (Gradle denied to this sub-agent). The parent must run it for AC5.

## .taskcheck

Result:

Not run by the test-writer (it runs Gradle). The parent runs it before `done`.

## Bugs found

None. The production behavior (today opens at the current hour) is as intended; only the tests were
nondeterministic.

Observation, not a defect of this task: compiling the unit tests prints the Kotlin warning
`CalendarSwipeInteractionTest.kt:80:93 Unnecessary non-null assertion (!!) on a non-null receiver of type 'LocalDate'`.
The `!!` in `state()` (`server.filter { it.startDate!! >= from ... }`) predates the task (present at the base, only
shifted down by the added lines). It is a compiler warning, not a ktlint failure; the parent may decide whether to
tidy it.

## Coverage

Status: not applicable - the task changes test code only; no production file is in the task coverage scope.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not applicable (zero changed production lines).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-test-calendar-clock;
  base 3a3055d4978dfd8791f6fb81feb2b8eef588a02e; HEAD 8f9a8d4f6493c1ce316f0899f0aa901c6558a900; task commits
  4bd7898f6 and 8f9a8d4f6 (given by the parent as 82c8efece and e1cab946a before the rebase, same file list).
- Source/test snapshot identifier (including uncommitted inputs): HEAD 8f9a8d4f6, clean working tree, no
  uncommitted inputs.
- Exact included files; renamed/deleted/excluded files and reasons: no production files included. The 10 changed
  files are all under `android/app/src/test` and are excluded as test files. No renames or deletions.
- Provider/version, command, working directory, and test selection: no coverage run; with an empty production scope
  a coverage figure would be meaningless.
- Run start/completion time and exit status: N/A (no coverage run).
- Generated report paths and report hashes/run identifier: N/A.
- Evidence outputs are fresh and inputs stayed unchanged: N/A for coverage; the test results above match HEAD's test
  contents.
- Final run after latest test additions and production fixes: yes for tests (clk-0800b on the final test code); no
  coverage run needed.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (no production files changed) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| none | none | test-only task; the clock-dependent production branches (`firstVisibleHour` today branch, `NowLine`) are exercised by the changed tests with a fixed clock at 08:00, 23:30 and 00:30 |

## Unable to test

- AC5 (`./gradlew ktlintCheck testDebugUnitTest lintDebug`) and `.taskcheck`: Gradle is denied to this sub-agent.
  Follow-up: the parent runs them.
- A confirmation run of the four agenda/month Compose tests at a fixed time: not possible here (Gradle denied) and not
  needed, since they never render the time grid or the now line.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. No other calendar Compose test renders
the day/week grid or the now line with the real clock in a time-dependent way, so no test was added. The parent still
has to run AC5 (`ktlintCheck testDebugUnitTest lintDebug`) and `.taskcheck`.
