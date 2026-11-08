# Test Report

Date: 2026-11-08

Task: and-cal-overlay-month - Android calendar: task overlay and month view

Scope: `git diff 3b4cb2ec530cb038373b5db388e09ea546ed9fdd...591648b32d6896ea0691d219d76cd4a255a6ffe7 -- android/`. Task commits e7f8bb88, 44225ae4, 9e7957bb, 9d543564 and 8272f99a, merged in 591648b3, on branch `main`. The parent worker supplied the base and the commit list. The diff matches the task's `touches` and acceptance criteria. Uncommitted input: this report's one test-file change (below).

Requirements:
- `tasks/and-cal-overlay-month.md`: acceptance criteria 1-4 and the recorded assumptions (all task marks go in the all-day strip; scheduled tasks come from `list=SCHEDULED` plus released-today tasks from `scheduled=today`; the toggle is remembered per device and off by default; the month view shows up to 3 titles plus "+n"; the toggles are in an overflow menu)
- `SPEC.md` §6.3, `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- `reviews/and-cal-overlay-month/fixes.md` (CR-1..CR-3)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarViewModelOverlayTest.kt`

## Tests added or updated

Count: 2 (added)

- `appended tasks that answer after the overlay is switched off are not shown`: the agenda's `loadMore` tasks load is an append job, and `setOverlay(false)` does not cancel it. The test checks that its late answer is dropped by the `!overlay` guard and that no tasks or marks come back.
- `appended tasks that cannot be loaded keep the tasks shown so far and set the flag`: when the tasks of the appended days fail, `tasksFailed` is set and the tasks loaded earlier stay.

The existing tests already covered the criteria well (5 task test classes, 70+ tests). I added only these two behaviors, which no test exercised before.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: day/week/agenda show due and scheduled tasks and ticklers as an overlay | `TaskOverlayMarksTest` (all), `TaskOverlayRepositoryTest` (all), `CalendarOverlayMonthScreensTest`: `the agenda lists the tasks under their day...`, `the day view shows the tasks in the all-day strip...`, `the week view shows each day's tasks...`, `a task released to Next today shows as scheduled on today...`; `CalendarViewModelOverlayTest`: `a task released from Scheduled to Next today is marked scheduled on today` | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` | Pass |
| AC1: toggle (off by default, remembered, switches the load on and off) | `TaskOverlaySettingTest` (both), `CalendarViewModelOverlayTest`: `the overlay is off by default and loads nothing`, `switching the overlay on loads ... both are remembered`, `a remembered overlay is on from the start...`, `tasks still loading when the overlay is switched off are not shown`, `appended tasks that answer after the overlay is switched off are not shown` (new); screens: `the toggle shows the overlay state and asks to switch it`, `a switched on toggle asks to switch the overlay off`, `with the overlay off no task is shown...` | same | Pass |
| AC1: tap opens the task | screens: agenda/day/week tap tests, `the route opens a tapped task with its snapshot through the task opener`; repository: `the task carries a snapshot for the detail screen` | same | Pass |
| AC1: load failures and stale answers | VM: `tasks that cannot be loaded set a flag...`, `another range shown while tasks load drops the old range's tasks`, `appended tasks that answer after another view is shown are dropped`, `appended tasks that cannot be loaded keep the tasks shown so far and set the flag` (new); repository: offline/failed mapping tests; screens: `tasks that could not be loaded are reported...`, `the events message replaces the tasks line` | same | Pass |
| AC2: month view (compact titles, +n, tap a day opens the day view, moves by a month) | `TaskOverlayMarksTest`: two month-grid tests; VM: `the month view loads its whole weeks and moves by a month`, `moving the month from the 31st...`, `the month view loads the overlay's tasks of its whole weeks...`, `Today in the month view returns to today's month...`; screens: `the month view lists up to three titles per day...`, `the month view describes each day...`, `tapping a task title in the month view opens its day, not the task`, `the month view lists no task titles while the overlay is off`, `a released-today task is listed in today's month cell`, the 360dp segment-width tests (EN, DE) | same | Pass |
| AC3: new strings in English and German | screens: `the new strings exist in English and German`, `the month view and the overlay are shown in German`, `the day view labels a task's kind and time in German`; Android lint `MissingTranslation` in `lintDebug` | same | Pass |
| AC4: `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes | full Android suite | `android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug` (2026-11-08 18:54-19:00 +01:00) | Pass: 4263 tests, 0 failures, 0 errors, 0 skipped (209 tests in 25 `calendar.agenda` classes) |
| AC4: `sh scripts/check.sh` passes | `.taskcheck` | `sh scripts/check.sh` | Fail, not caused by this task: the `web` step stops with "The Angular CLI requires a minimum Node.js version of v22.22.3 ... Node.js version v20.9.0 detected" in this shell. The tfp and server steps passed before that. The Android step was not reached, so the Android command above stands in for it. |

## Focused test execution

Command:

`android/gradlew -p android ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.CalendarViewModelOverlayTest' -q`

Result:

Pass: 19 tests, 0 failures (JUnit XML `TEST-ch.sonensei.cockpit.calendar.agenda.CalendarViewModelOverlayTest.xml`, 2026-11-08T17:53:48Z)

## Full test execution

Command:

`android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug -q`

Result:

Pass (exit 0): 4263 tests, 0 failures, 0 errors, 0 skipped

## .taskcheck

Result:

Fail, caused by the environment and not by this task. `sh scripts/check.sh` stops in the web step because Node.js v20.9.0 is older than the minimum the Angular CLI requires (v22.22.3). This task changes no web files. The Android part ran separately and passed (above). The parent worker must run `.taskcheck` in an environment with the required Node version.

## Bugs found

None. No test failed because of production behavior.

Observation, not filed as a bug: `show()` clears `tasks` when the range changes but leaves `tasksFailed` as it was. After a failed load, the "The tasks could not be loaded." line stays visible for the new range until that range's tasks load finishes. When they load, the flag is cleared. No acceptance criterion covers this case. The KDoc ("could not be loaded for the shown range") suggests clearing the flag on a range change. Low impact; the parent worker can decide whether this is in scope.

## Coverage

Status: verified (Android part; JaCoCo report regenerated after the latest test change)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. The project's JaCoCo setup has no changed-line report, and per-file line data intersected by hand with the diff would include Compose-generated lines. The uncovered lines are mapped to the diff in the gap table below.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-cal-overlay-month`. Base 3b4cb2ec530cb038373b5db388e09ea546ed9fdd, HEAD 591648b32d6896ea0691d219d76cd4a255a6ffe7. Commits e7f8bb88, 44225ae4, 9e7957bb, 9d543564 and 8272f99a come from the parent worker and were checked against `git log 3b4cb2ec..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 591648b3, plus the uncommitted `CalendarViewModelOverlayTest.kt` with sha256 `ad8dade4dc2808f1c87243efa35e10166362f986ceecc327b910f3095e8b7638`. The hash was the same before and after the run. No other changes.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/{CalendarModels.kt, CalendarOverlayMonth.kt, CalendarScreen.kt, CalendarViewModel.kt, TaskOverlay.kt}`. Excluded: `res/values{,-de}/strings_calendar_agenda.xml`, because resources have no executable code; they are covered by the string tests and lint. No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo through AGP `enableUnitTestCoverage` (the `jacoco` plugin in `android/app/build.gradle.kts`), Gradle 9.8.1, JVM 21.0.7. Command `android/gradlew -p android createDebugUnitTestCoverageReport -q`, run from the clone root, over the whole debug unit test suite.
- Run start/completion time and exit status: baseline 2026-11-08 18:46:46-18:52:23 +01:00, exit 0. Final run 19:00:46-19:01:04 +01:00, exit 0. In the final run, Gradle reused the `testDebugUnitTest` execution data from the full check run just before it (18:54-19:00), which ran on the same snapshot with the new tests.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`. Final run: mtime 2026-11-08 19:01:03 +01:00, sha256 `05c545b9ca792b01a873896b5fb37f3e5a51725a7aaba71af10e7a099cb47036`. Baseline: sha256 `41fffc3516748705030d71e18a49eb1eaea06b75082afebebb7c2cbc455b9c74`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report was regenerated (new mtime and hash), and the test file hash was the same before and after.
- Final run after latest test additions and production fixes: yes. The run is on 591648b3, which includes the CR-1..CR-3 fixes, and includes both new tests.

Values below use covered/total (percentage). The numbers come from JaCoCo counters: INSTRUCTION for statements, BRANCH, METHOD and LINE.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `calendar/agenda/CalendarModels.kt` | 1156/1180 (98.0%) | 108/116 (93.1%) | 63/64 (98.4%) | 134/134 (100.0%) |
| `calendar/agenda/CalendarOverlayMonth.kt` | 1754/1771 (99.0%) | 36/42 (85.7%) | 19/19 (100.0%) | 179/180 (99.4%) |
| `calendar/agenda/CalendarScreen.kt` | 6504/6582 (98.8%) | 205/226 (90.7%) | 121/132 (91.7%) | 548/552 (99.3%) |
| `calendar/agenda/CalendarViewModel.kt` | 2756/2778 (99.2%) | 214/223 (96.0%) | 59/59 (100.0%) | 266/266 (100.0%) |
| `calendar/agenda/TaskOverlay.kt` | 1054/1094 (96.3%) | 51/52 (98.1%) | 47/49 (95.9%) | 118/122 (96.7%) |
| **Aggregate (summed counters, all 5 files; no missing files; only the 2 string resource files excluded)** | 13224/13405 (98.6%) | 614/659 (93.2%) | 309/323 (95.7%) | 1245/1254 (99.3%) |

The new tests raised `CalendarViewModel.kt` branches from 213/223 to 214/223: the `!overlay` guard in `loadTasks` is now reached by a late append answer. The other numbers did not change.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `TaskOverlay.kt:161-162` (`catch CancellationException -> throw`) | Cancelling the load must not be reported as offline | Left uncovered. `CancellationException` is not an `IOException` or `IllegalArgumentException`, so it would propagate even without this catch. A test could not tell the difference. |
| `TaskOverlay.kt:169-170` (`catch IllegalArgumentException`) | A malformed answer becomes `Failed(null)` | Defensive code: the generated models' enum parsing fails as `SerializationException` (covered). `TaskList.valueOf(list.name)` cannot fail for a generated enum value. |
| `TaskOverlay.kt:243` (one branch of `creativity?.let`) | Snapshot mapping of a set creativity | Trivial mapping, like `energy`, which is tested. Not material. |
| `CalendarViewModel.kt:202, 216` (`tasksJob?.cancel()` with a null job) | No-op cancel | Not material. |
| `CalendarViewModel.kt:223` (one remaining guard operand: `from < it.from` true) | A non-append answer for an earlier range | Not reachable in practice: a non-append load is cancelled when the range is replaced. The append path is covered by both stale-answer tests. |
| `CalendarOverlayMonth.kt:93, 120, 127, 215, 302, 312` | Compose-generated branches (default parameter masks, `clickable` lambdas, `remember` keys) and the closing brace of an exhaustive `when` | Not material. Every behavior here (task chip and agenda row taps, month day taps, task titles in month cells) is checked by screen tests. |
| `CalendarScreen.kt:121-126, 147` (`CalendarRoute` default `hiltViewModel()`, `taskOpener?` null) | Hilt wiring and a missing `LocalTaskOpener` | Needs a Hilt test; not material. The route with a task opener is tested (`the route opens a tapped task...`). |
| `CalendarScreen.kt:244, 400, 498` and the other partial branches | Mostly lines that existed before this task, and Compose-generated branches | Not material for this task. The new toolbar menu, segments, agenda task rows and all-day strip are covered by the screen tests. |
| `CalendarModels.kt:57, 150` | Lines that existed before this task. The new `monthGrid` (213-225) is fully covered. | Out of task scope. |

## Unable to test

- AC4 `sh scripts/check.sh`: could not pass here because Node.js v20.9.0 is older than the Angular CLI minimum (v22.22.3). This is not caused by this task. Follow-up for the parent worker: run it where the required Node version is available.
- The 360dp toolbar layout was checked with Robolectric width assertions only, not rendered on a device. No follow-up is needed for this task.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. I added 2 tests for the agenda's appended tasks loads: an answer that arrives after the overlay is switched off, and a failed load. The Android check command passes. `.taskcheck` fails in its web step because of the host's Node.js version, which is unrelated to this task.
