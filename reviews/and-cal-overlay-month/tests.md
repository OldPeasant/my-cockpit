# Test Report

Date: 2026-11-08

Task: and-cal-overlay-month - Android calendar: task overlay and month view

Scope: `git diff 84de6fcd6ab5e67b6641d20f623d0b418742fb6f..993c56bbce9b4c4350c115d59d389cd142d4df93 -- android/` on branch `main`. The task commits are `git log 84de6fcd..HEAD -- android/`: 8d9b4d68, 1af1cfce, 87d2fefe, 90395ed0, 4c0193c9, c19238a2, 40476d78, 15ba5b43, 5d0d2984, 0f65a245 and 993c56bb. The parent worker supplied the base and the commit list. The changed files match the task's `touches`. The working tree was clean during every run, so there were no uncommitted inputs. This round adds no test changes.

Requirements:
- `tasks/and-cal-overlay-month.md`: acceptance criteria 1-4 and the recorded assumptions. All task marks go in the all-day strip. Scheduled tasks come from `list=SCHEDULED`, plus released-today tasks from `scheduled=today`. The toggle is remembered per device and is off by default. The month view shows up to 3 titles plus "+n". The toggles are in an overflow menu.
- `SPEC.md` §6.3, `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- `reviews/and-cal-overlay-month/fixes.md` and `review.md` (review rounds 1-3; round 3 CR-1 adds `state.today` to the `remember` keys of the task marks)

## Test files changed

None in this round. Commit 993c56bb, from the earlier interrupted test-writer run, added the two tests that this round checks:

- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarOverlayMonthScreensTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModelOverlayTest.kt`

## Tests added or updated

Count: 0 in this round. I checked the 2 tests added by 993c56bb:

- `when today moves on with the same tasks, the day view drops yesterday's released-today mark` (`CalendarOverlayMonthScreensTest`). It uses the shared helper `assertReleasedMarkFollowsToday(CalendarView.DAY)`. The test shows a task with `releasedOn = today`, checks that its mark is shown, then moves only `state.today` on by one day, with the same tasks and range, and checks that the mark is gone. The day view uses the remembered marks in `CalendarScreen.kt:735`, the same place as the week view, so this test checks the round 3 CR-1 `remember` key from the day-view side. With the agenda, week and month cases, all three `remember` places (`CalendarOverlayMonth.kt:224`, `CalendarScreen.kt:481`, `CalendarScreen.kt:735`) are now covered in each view. Correct; passes.
- `a refresh on the next day in the same week asks for the new today's tasks and drops yesterday's released mark` (`CalendarViewModelOverlayTest`). It uses a `MovableClock` that moves from Wed 2026-10-21 to Thu 2026-10-22, 10:00 Zurich. After the refresh, the test checks four things: `state.today` moved on, the week range stayed the same, the task source was asked with the new today, and the task with `releasedOn` = yesterday gets no mark. The fake still returns that task on the second day, because its released list is keyed only on the range. So the empty marks come from the production `releasedOn == today` rule in `taskMarks`, not from the fake leaving the task out. This is the right thing to check. Correct and deterministic (no real delays); passes.

I found no further meaningful gap (see the coverage gap table), so I added no tests.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: day/week/agenda show due and scheduled tasks and ticklers as an overlay | `TaskOverlayMarksTest` (all), `TaskOverlayRepositoryTest` (all); `CalendarOverlayMonthScreensTest`: agenda/day/week overlay tests, `a task released to Next today shows as scheduled on today...`, `when today moves on with the same tasks, the {agenda, day view, week view, month view} drops yesterday's released-today mark`; `CalendarViewModelOverlayTest`: `a task released from Scheduled to Next today is marked scheduled on today`, `a refresh on the next day in the same week asks for the new today's tasks and drops yesterday's released mark` | `android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug -q` | Pass |
| AC1: toggle (off by default, remembered, switches the load on and off) | `TaskOverlaySettingTest` (both); VM: `the overlay is off by default and loads nothing`, `switching the overlay on loads ... both are remembered`, `a remembered overlay is on from the start...`, `tasks still loading when the overlay is switched off are not shown`, `appended tasks that answer after the overlay is switched off are not shown`, `retrying the tasks does nothing while the overlay is off`; screens: toggle/overflow-menu tests, `with the overlay off no task is shown...` | same | Pass |
| AC1: tap opens the task | screens: agenda/day/week tap tests, `the route opens a tapped task with its snapshot through the task opener`; repository: `the task carries a snapshot for the detail screen` | same | Pass |
| AC1: load failures, retry and stale answers | VM: `tasks that cannot be loaded set a flag...`, `another range shown while tasks load drops the old range's tasks`, `appended tasks that answer after another view is shown are dropped`, `appended tasks that cannot be loaded...`, `another range does not keep the old range's tasks failure...`, `the same range shown again keeps its tasks failure`, `retrying the tasks reloads only the shown range's tasks...`, `retrying after the agenda's appended tasks failed...`, `a retry that fails again keeps the failure...`; repository offline/failed mapping tests; screens: failure line and retry tests | same | Pass |
| AC2: month view (compact titles, +n, tap a day opens the day view, moves by a month) | `CalendarMonthLayoutTest`, `MonthTitlesShownTest`, `TaskOverlayMarksTest` month-grid tests; VM: `the month view loads its whole weeks and moves by a month`, `moving the month from the 31st...`, `the month view loads the overlay's tasks of its whole weeks...`, `Today in the month view returns to today's month...`; screens: month titles/+n, day description, `tapping a task title in the month view opens its day, not the task`, `the month view lists no task titles while the overlay is off`, 360dp segment-width tests (EN, DE) | same | Pass |
| AC3: new strings in English and German | screens: `the new strings exist in English and German`, the German month/overlay and day-view label tests; Android lint `MissingTranslation` in `lintDebug` | same | Pass (lint: 0 errors, 0 warnings, 2 hints in unrelated `journal`/`notes` files) |
| AC4: `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes | full Android suite | `android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug -q` (2026-11-08 20:41:52-20:48:47 +01:00) | Pass (exit 0): 4584 tests in 376 classes, 0 failures, 0 errors, 0 skipped. Of these, 230 tests are in 27 `calendar.agenda` classes |
| AC4: `sh scripts/check.sh` passes | `.taskcheck` | `sh scripts/check.sh` (20:49:45-20:50:55 +01:00); then `sh scripts/check.sh tfp web android` and `sh scripts/check.sh android` | Fail, environment only (see ".taskcheck"). The `tfp` and `android` parts pass. |

## Focused test execution

Command:

`android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.CalendarViewModelOverlayTest' --tests 'ch.sonensei.cockpit.calendar.agenda.CalendarOverlayMonthScreensTest' -q`

Result:

Pass (exit 0). `CalendarOverlayMonthScreensTest`: 28 tests, 0 failures. `CalendarViewModelOverlayTest`: 26 tests, 0 failures. JUnit XML timestamps 2026-11-08T19:41:24Z and 19:41:38Z. Both tests from 993c56bb appear as passed testcases.

## Full test execution

Command:

`android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug -q`

Result:

Pass (exit 0): 4584 tests, 0 failures, 0 errors, 0 skipped. ktlint is clean. Lint: 0 errors, 0 warnings.

## .taskcheck

Result:

Fail. The cause is the environment, not this task. This task changes only `android/` files.

- `sh scripts/check.sh`: the `tfp` step passes. The `server` step fails: `3068 tests completed, 424 failed`. Every failure is an `initializationError` with "Could not find a valid Docker environment" from Testcontainers (`PostgresTestBase.kt:23`). Docker is not available on this host.
- `sh scripts/check.sh tfp web android`: `tfp` passes (22 tests OK). `web` stops with "Node.js version v20.9.0 detected. The Angular CLI requires a minimum Node.js version of v22.22.3 or v24.15.0 or v26.0.0."
- `sh scripts/check.sh android`: "== all checks passed (android)". Gradle reused the up-to-date results of the full Android run above.

The parent worker must run the full `.taskcheck` on a host with Docker and the required Node.js version, or record the skip.

## Bugs found

None. No test failed because of production behavior.

The previous report noted that a range change kept `tasksFailed`. That is now fixed in `CalendarViewModel.kt:373` (`tasksFailed = it.tasksFailed && sameRange`). The tests `another range does not keep the old range's tasks failure while its own tasks load` and `the same range shown again keeps its tasks failure` cover it.

## Coverage

Status: verified (Android part; fresh JaCoCo report on HEAD 993c56bb, clean tree)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. The project's JaCoCo setup has no changed-line report. Intersecting the per-line data with the diff by hand would count Compose-generated synthetic lines. Instead, the gap table below maps the uncovered lines to the task diff.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-cal-overlay-month`. Base 84de6fcd6ab5e67b6641d20f623d0b418742fb6f; measured HEAD 993c56bbce9b4c4350c115d59d389cd142d4df93. The commit list and file scope come from the parent worker (`git log 84de6fcd..HEAD -- android/`), checked with `git diff --name-only 84de6fcd..HEAD -- android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 993c56bb with a clean working tree (`git status --short` empty before and after). I took sha256 hashes of all `calendar/agenda` main and test `.kt` files before the first run and compared them after the last run: no differences.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/{CalendarModels.kt, CalendarOverlayMonth.kt, CalendarScreen.kt, CalendarViewModel.kt, TaskOverlay.kt}`. Excluded: `res/values{,-de}/strings_calendar_agenda.xml`, which have no executable code; the string tests and lint cover them. No renames or deletions. All 5 files are present in the report.
- Provider/version, command, working directory, and test selection: JaCoCo through AGP `enableUnitTestCoverage`, Gradle wrapper of `android/`, JVM 21. Command `android/gradlew -p android createDebugUnitTestCoverageReport -q`, run from the clone root, over the whole debug unit test suite.
- Run start/completion time and exit status: the full check `ktlintCheck testDebugUnitTest lintDebug` ran 20:41:52-20:48:47 +01:00 (exit 0) and wrote `android/app/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec` (mtime 20:46:18). The coverage report task ran 20:48:59-20:49:07 +01:00 (exit 0) and used that execution data from the same snapshot.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, mtime 2026-11-08 20:49:07 +01:00, sha256 `f133f676814f9281282573bbe53fd2e1b0bb411d16c4da3dbc7674a5c46e77ae`. The report left by the previous run had mtime 20:28.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report and the exec file were regenerated in this round, and the input hashes did not change.
- Final run after latest test additions and production fixes: yes. HEAD 993c56bb includes the round 3 CR-1 production fix (0f65a245) and both tests from 993c56bb. No code changed in this round.

Values below use covered/total (percentage), from JaCoCo counters: INSTRUCTION for statements, then BRANCH, METHOD and LINE.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `calendar/agenda/CalendarModels.kt` | 1156/1180 (98.0%) | 108/116 (93.1%) | 63/64 (98.4%) | 134/134 (100.0%) |
| `calendar/agenda/CalendarOverlayMonth.kt` | 2074/2095 (99.0%) | 48/60 (80.0%) | 26/27 (96.3%) | 207/209 (99.0%) |
| `calendar/agenda/CalendarScreen.kt` | 6812/6895 (98.8%) | 274/304 (90.1%) | 122/134 (91.0%) | 550/554 (99.3%) |
| `calendar/agenda/CalendarViewModel.kt` | 2777/2801 (99.1%) | 217/227 (95.6%) | 60/60 (100.0%) | 268/268 (100.0%) |
| `calendar/agenda/TaskOverlay.kt` | 1054/1094 (96.3%) | 51/52 (98.1%) | 47/49 (95.9%) | 118/122 (96.7%) |
| **Aggregate (summed counters; all 5 files measured; no missing files; only the 2 string resource files excluded)** | 13873/14065 (98.6%) | 698/759 (92.0%) | 318/334 (95.2%) | 1277/1287 (99.2%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `TaskOverlay.kt:161-162` (`catch CancellationException -> throw`) | A cancelled load must not be reported as offline | Left uncovered. `CancellationException` is not caught by the later `IOException`/`IllegalArgumentException` handlers, so a test could not tell whether this catch is there. |
| `TaskOverlay.kt:169-170` (`catch IllegalArgumentException`) | A malformed answer becomes `Failed(null)` | Defensive code. Malformed answers fail as `SerializationException`, which is covered. |
| `TaskOverlay.kt:243` (one branch of `creativity?.let`), `:79`, `:101` | Snapshot mapping; comparator and `None` source lambdas | Trivial. The comparator order is tested through `TaskOverlayMarksTest`. Not material. |
| `CalendarViewModel.kt:202` (`tasksJob?.cancel()` with a null job), `:219` (`to <= from` true) | No-op cancel; an empty range | Not material. No view produces an empty range. |
| `CalendarViewModel.kt:226` (one of 6 guard branches: `from < it.from` true) | A late non-append answer for an earlier range | Not reachable in practice: a non-append load is cancelled when the range is replaced. Stale append answers are covered by two tests. |
| `CalendarViewModel.kt:373` (1 of 4 branches of `tasksFailed && sameRange`) | A tasks failure is cleared on a range change and kept on the same range | The behavior is tested both ways (see "Bugs found"). The missing branch is a short-circuit combination with no behavior of its own. |
| `CalendarViewModel.kt:33-37, 135, 147, 294, 335, 358, 460` | Default parameters and lines that existed before this task | Out of task scope or not material. |
| `CalendarOverlayMonth.kt:97, 131` (partial `clickable`), `123-124`, `249`, `366`, `355`/`376` (exhaustive `when`), `188` | Compose default-parameter masks, lambda variants, and the synthetic `else` of an exhaustive `when` | Not material. Every behavior here is checked by screen tests: task chip and agenda row taps, month day taps, task titles in month cells. |
| `CalendarOverlayMonth.kt:289, 292` (`hasBoundedHeight` false) | Month cell with unbounded height: fall back to `MONTH_TITLES + 1` lines | Left uncovered. The month grid always gives its cells a bounded height (`weight` plus `fillMaxHeight`). `CalendarMonthLayoutTest` covers the bounded path, including the line-height fallback from round 2. |
| `CalendarScreen.kt:121-126, 148` (`hiltViewModel()` default, `taskOpener?` null) | Hilt wiring; a missing `LocalTaskOpener` | Testing this needs a Hilt test. Not material. The route with a task opener is tested. |
| `CalendarScreen.kt:156-171` (`CalendarCallbacks` default lambdas), `300` (`onDismissRequest`), `495-520`, `623-628`, `761`, `809`, `822`, `936`, `948`, and the other partial branches | Default no-op callbacks, menu dismissal, and Compose or older code (agenda paging and positioning, time labels) | Not material for this task. The new toolbar overflow menu, segments, agenda task rows, all-day strip marks and the retry line are covered by screen tests. |
| `CalendarModels.kt:57, 132, 150` | Lines that existed before this task. The new `monthGrid` (213-225) is fully covered. | Out of task scope. |

## Unable to test

- AC4 `sh scripts/check.sh` as a whole: the server step needs Docker (Testcontainers), and the web step needs Node.js >= v22.22.3. This host has neither. This task does not cause the failures, and its own parts (`tfp`, `android`) pass. Follow-up for the parent worker: run it where both are available, or record the skip with `tasks.py verify --skip`.
- The 360dp toolbar layout was checked with Robolectric width assertions only, not rendered on a device. No follow-up is needed for this task.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. Both tests from 993c56bb (the day-view released-mark test and the next-day refresh with a `MovableClock`) are correct and pass, and they check the round 3 CR-1 behavior. I added no tests, because no meaningful gap remains. The Android check command passes (4584 tests). `.taskcheck` fails only in its server step (no Docker) and its web step (Node.js v20.9.0), and both are unrelated to this task.
