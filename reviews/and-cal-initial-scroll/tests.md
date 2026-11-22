# Test Report

Date: 2026-11-22

Task: and-cal-initial-scroll - Android calendar: day/week grid opens scrolled to the earliest entry

Scope: base dca69126a29356e109ff02d28ca028f9c90da221 .. final commit c915f691b1f84b5d33d4c1dba101e6bfea10767f (task commits 68aae48ec implementation, 065c27383 round-1 tests and reports, c915f691b CR-1 fix; supplied by the parent worker). Round 2 on the final code. Production change: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt` (`GridPeriod` with its saveable `key`, `firstVisibleHour`, `TimeGrid` scroll positioning with a `rememberSaveable` `positionedFor`, `CalendarTags.GRID_SCROLL`). Test change: `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarGridScrollTest.kt`. No uncommitted source or test changes in this round.

Requirements:
- `tasks/and-cal-initial-scroll.md` acceptance criteria 1-5
- `AGENTS.md`, `docs/ARCHITECTURE.md` "Coverage"
- `reviews/and-cal-initial-scroll/review.md` finding CR-1 and `reviews/and-cal-initial-scroll/fixes.md`
- Production code read for context: `CalendarViewModel.kt` (`show`, `load`, `shown`, `cachedAt`)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarGridScrollTest.kt` (in the task commits; this round changed no test file)

## Tests added or updated

Count: 0 in this round. The class has 11 tests: 8 from the implementation, 2 added in round 1, 1 added by the fixer for CR-1 (`a grid recreated for the same period (rotation, back to the tab) keeps where the user scrolled to`).

No further test was needed: the CR-1 regression test covers the restore path, and it was checked to fail without the fix (see below). The other criteria were already covered in round 1 and the fix does not change their logic (the key is now a String built from the same view and dates).

### Regression check of the CR-1 test

The `positionedFor` line in `TimeGrid` was temporarily changed back from `rememberSaveable` to plain `remember` (the only change), then
`cd android && ./gradlew testDebugUnitTest --tests '*CalendarGridScrollTest*' -q` was run:
11 tests, 1 failed: `a grid recreated for the same period (rotation, back to the tab) keeps where the user scrolled to` with `AssertionError: expected:795 but was:672` (after the restore the grid was positioned again at 14:00 = 672 px instead of keeping the user position). The line was then restored to `rememberSaveable`; `git status` showed `CalendarScreen.kt` unchanged against HEAD afterwards (sha256 173097b2422717412be10223cc9458a5dd63ff216b1e0439f1d8c3be495ab656). So the test detects the bug the fix addresses.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Top is the full hour at or before the earliest timed start (day and week), clamped near midnight | `the day opens at the full hour before its earliest timed entry`, `the week opens at its earliest timed entry on any day`, `an entry near midnight scrolls to the end of the grid` | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests '*CalendarGridScrollTest*' --tests '*CalendarScreensTest*' createDebugUnitTestCoverageReport -q` | Pass |
| 2. Entries continuing from an earlier day and all-day entries are ignored | `entries continuing from an earlier day and all-day entries are ignored`, `the week ignores an entry continuing from the previous week`, `the first visible hour follows the rules` | same | Pass |
| 3. No timed entry: current hour if today is shown, else 07:00 | `without a timed entry today opens at the current hour`, `without a timed entry another day opens at seven`, `the first visible hour follows the rules`, `Today positions the grid on cached data, the network data of the same day then does not move it` | same | Pass |
| 4. Applied on open and every period change once the first data (cache or network) is there; a refresh of the same period does not move it | `a period change positions the grid once its data is there, a reload of the same period does not move it`, `Today positions the grid on cached data, the network data of the same day then does not move it`, `a grid recreated for the same period (rotation, back to the tab) keeps where the user scrolled to` (CR-1: the same period shown again after a saved-state restore keeps the user position; fails without the fix) | same | Pass |
| 4. "later swipe" | none | n/a | Not testable: swipe navigation does not exist yet (later task); it would go through the same `GridPeriod` change path |
| 5. Unit/Compose tests cover the behavior; `ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass | the 11 tests above, plus `CalendarScreensTest` (9) as neighbouring regression | same | Pass for ktlint and the focused classes; the full `testDebugUnitTest`, `lintDebug` and `sh scripts/check.sh` were Not run here (session time budget), left to the parent worker |

Note: the tests drive `CalendarContent` with hand-built `CalendarUiState` sequences that mirror what `CalendarViewModel.show`/`load` produce; they do not run the ViewModel and the screen together. The restore test uses `StateRestorationTester`, which emulates saved-instance-state save and restore within one Robolectric process (not a real Activity recreation on a device).

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests '*CalendarGridScrollTest*' --tests '*CalendarScreensTest*' createDebugUnitTestCoverageReport -q`

Result:

Pass (exit 0, no output). `TEST-ch.sonensei.cockpit.calendar.agenda.CalendarGridScrollTest.xml`: tests=11, failures=0, errors=0, skipped=0, timestamp 2026-11-22T08:22:19Z. `TEST-ch.sonensei.cockpit.calendar.agenda.CalendarScreensTest.xml`: tests=9, failures=0, errors=0, skipped=0, timestamp 2026-11-22T08:22:25Z. ktlintCheck passed.

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest lintDebug`

Result:

Not run (short session budget; the full Android unit suite takes several minutes). The parent worker runs it and `sh scripts/check.sh` before `done`.

## .taskcheck

Result:

Not run (same reason; `sh scripts/check.sh` is run by the parent worker).

## Bugs found

None. CR-1 (from the code review) is fixed in c915f691b and its test fails without the fix.

## Coverage

Status: verified for the selected test classes only (`CalendarGridScrollTest` and `CalendarScreensTest`, not the full suite).

Metric: whole-file coverage of task-changed production files, from a run of the two classes above, so the whole-file numbers reflect only these classes (higher than round 1, which ran `CalendarGridScrollTest` alone).
Changed-line coverage: measured by intersecting the JaCoCo XML per-line entries of `CalendarScreen.kt` with the added/modified lines of `git diff -U0 dca69126a c915f691b` (new-file lines 177, 720, 723-753, 790-805; only lines that JaCoCo reports as executable count): lines 26/26 (100%); branches on those lines 21/24 (87.5%). The executable line count differs from round 1 (27) because the CR-1 fix changed the `GridPeriod`/`positionedFor` lines.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-cal-initial-scroll; base dca69126a29356e109ff02d28ca028f9c90da221; HEAD c915f691b1f84b5d33d4c1dba101e6bfea10767f; task commits and scope supplied by the parent and confirmed with `git diff -U0` of the base..HEAD range for `CalendarScreen.kt`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD c915f691b with no uncommitted source/test changes; `CalendarScreen.kt` sha256 173097b2422717412be10223cc9458a5dd63ff216b1e0439f1d8c3be495ab656, `CalendarGridScrollTest.kt` sha256 46357d3cbcc3ef0ce15ce28aa87af3dcb16cdf20e1f86b55d40b059391e854fe. (The working tree also had an uncommitted `reviews/and-cal-initial-scroll/review.md` change from the parallel code review, not a test input.)
- Exact included files; renamed/deleted/excluded files and reasons: included `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt`; excluded the test file (test code). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (AGP 9.4.1); `./gradlew ktlintCheck testDebugUnitTest --tests '*CalendarGridScrollTest*' --tests '*CalendarScreensTest*' createDebugUnitTestCoverageReport -q` in `android/`; selection: those two classes.
- Run start/completion time and exit status: tests 2026-11-22T08:22:19Z to about 08:22:27Z (exec file written 08:22:27Z), report written 08:22:29Z; exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` sha256 67d01291467934f2f706a537ecf65a1e847269ddc849fd37f959e8348d6fd1ea (from `android/app/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec`).
- Evidence outputs are fresh and inputs stayed unchanged: the run came after the temporary revert was undone (the failing negative-check run happened before it, at the reverted state, and its output was replaced by this run); report and exec timestamps match this run; the source hashes above were taken after the run and match HEAD.
- Final run after latest test additions and production fixes: yes (on c915f691b, which contains the CR-1 fix and its test).

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt` (whole file, these two classes only) | instructions 6014/7210 (83.4%) | 178/328 (54.3%) | methods 112/144 (77.8%) | 511/579 (88.3%) |

No aggregate beyond the one file; no scoped file is missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalendarScreen.kt:798` (3 of 6 branches) | Compose-generated key comparisons of `LaunchedEffect(period, state.shown, entriesByDay)` | Not material: compiler-generated changed-key branches, not behavior of the task |
| Rest of `CalendarScreen.kt` (agenda, month grid, toolbar, dialogs) | pre-existing code outside this task | Covered by other test classes of the full suite, not part of this focused run |

## Unable to test

- "later swipe" period change (criterion 4): swipe navigation is a later task; nothing to drive yet. Follow-up belongs to that task.
- A real Activity recreation (rotation on a device): only emulated with `StateRestorationTester` under Robolectric; no device or instrumented run here.
- Full `testDebugUnitTest`, `lintDebug` and `sh scripts/check.sh` (criterion 5): not run in this session for time; the parent worker must run them.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. No test was added in this round: the CR-1 restore test fails with the old plain `remember` and passes with the fix, the focused classes pass (11 + 9, ktlint clean), and changed-line coverage is 26/26 lines. The full Android suite, `lintDebug` and `.taskcheck` remain for the parent worker.
