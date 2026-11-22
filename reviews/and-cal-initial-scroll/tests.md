# Test Report

Date: 2026-11-22

Task: and-cal-initial-scroll - Android calendar: day/week grid opens scrolled to the earliest entry

Scope: base dca69126a29356e109ff02d28ca028f9c90da221 .. task commit e778a5aa737dbedf38bcca55b2ca39e35e8ae2c4 (one task-owned commit, supplied by the parent worker), plus the uncommitted test additions of this run. Production change: `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt` (`GridPeriod`, `firstVisibleHour`, `TimeGrid` scroll positioning, `CalendarTags.GRID_SCROLL`). Test change: `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarGridScrollTest.kt`.

Requirements:
- `tasks/and-cal-initial-scroll.md` acceptance criteria 1-5
- `AGENTS.md`, `docs/ARCHITECTURE.md` "Coverage"
- Production code read for context: `CalendarViewModel.kt` (`show`, `load`, `shown`, `cachedAt`): a period change clears `loaded`/`cachedAt` for a new range, the offline cache sets `cachedAt` with `loaded = false`, a later successful load of the same range sets `loaded = true`.

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarGridScrollTest.kt`

## Tests added or updated

Count: 2 tests added (the class now has 10 tests); the `state()` helper got an optional `cachedAt` parameter. Existing tests were not changed.

Added:
- `the week ignores an entry continuing from the previous week`: a Sunday 22:00 to Monday 03:00 entry, shown from 00:00 on the week's first day, does not pull the week grid to 00:00; the Thursday 11:15 entry positions it at 11:00 (criterion 2 for the week view, which the existing tests only covered for the day view).
- `Today positions the grid on cached data, the network data of the same day then does not move it`: from another day, Today clears the range (grid waits), then offline cached data with only an all-day entry is shown (`cachedAt` set, `loaded = false`) and the grid goes to the current hour; a later successful load of the same day with an earlier timed entry does not move it (criteria 3 and 4: Today, cached first data, refresh of the same period).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Top is the full hour at or before the earliest timed start (day and week), clamped near midnight | `the day opens at the full hour before its earliest timed entry`, `the week opens at its earliest timed entry on any day`, `an entry near midnight scrolls to the end of the grid` | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests '*CalendarGridScrollTest*'` | Pass |
| 2. Entries continuing from an earlier day and all-day entries are ignored | `entries continuing from an earlier day and all-day entries are ignored`, `the week ignores an entry continuing from the previous week` (added), `the first visible hour follows the rules` | same | Pass |
| 3. No timed entry: current hour if today is shown, else 07:00 | `without a timed entry today opens at the current hour`, `without a timed entry another day opens at seven`, `the first visible hour follows the rules`, `Today positions the grid on cached data, ...` (added) | same | Pass |
| 4. Applied on open and every period change once the first data (cache or network) is there; a refresh of the same period does not move it | `a period change positions the grid once its data is there, a reload of the same period does not move it` (arrow-style day change, user scroll then refresh, view switch), `Today positions the grid on cached data, the network data of the same day then does not move it` (added: Today, cached first data, network reload) | same | Pass |
| 4. "later swipe" | none | n/a | Not testable: swipe navigation does not exist yet (later task); it would go through the same `GridPeriod` change path |
| 5. Unit/Compose tests cover the behavior; `ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass | the 10 tests above | `./gradlew ktlintCheck testDebugUnitTest --tests '*CalendarGridScrollTest*'` | Pass for ktlint and the focused tests; the full `testDebugUnitTest`, `lintDebug` and `sh scripts/check.sh` were Not run here (session time budget), left to the parent worker |

Note: the tests drive `CalendarContent` with hand-built `CalendarUiState` sequences that mirror what `CalendarViewModel.show`/`load` produce (read from the code); they do not run the ViewModel and the screen together.

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests '*CalendarGridScrollTest*' -q`

Result:

Pass (exit 0; `TEST-ch.sonensei.cockpit.calendar.agenda.CalendarGridScrollTest.xml`: tests=10, failures=0, errors=0, skipped=0, timestamp 2026-11-22T08:18:33Z). ktlintCheck passed on the edited test file.

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest lintDebug`

Result:

Not run (short session budget; the full Android unit suite takes several minutes). The parent worker runs it and `sh scripts/check.sh` before `done`.

## .taskcheck

Result:

Not run (same reason; `sh scripts/check.sh` is run by the parent worker).

## Bugs found

None.

## Coverage

Status: verified for the selected test class only (focused run, not the full suite).

Metric: whole-file coverage of task-changed production files, from a run of `CalendarGridScrollTest` alone, so the whole-file numbers reflect only this class and are lower than the full suite would give.
Changed-line coverage: measured by intersecting the JaCoCo XML per-line entries of `CalendarScreen.kt` with the added/modified executable lines of the task diff (current lines 724-727 `GridPeriod`, 740-748 `firstVisibleHour`, 787-803 `TimeGrid` positioning): lines 27/27 (100%); branches on those lines 21/24 (87.5%).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-cal-initial-scroll; base dca69126a29356e109ff02d28ca028f9c90da221; HEAD e778a5aa737dbedf38bcca55b2ca39e35e8ae2c4; task-owned commit and file list supplied by the parent and confirmed with `git diff dca69126a e778a5aa7 --stat` (2 files).
- Source/test snapshot identifier (including uncommitted inputs): HEAD e778a5aa7 plus uncommitted `CalendarGridScrollTest.kt` sha256 a835fd952534d58ec696799e020ddf435a0cda556fcc0c0dd391f3f1b8ae5824; `CalendarScreen.kt` (unchanged from HEAD) sha256 c15be680358c0cced917344d2e1f028ad76b3baa6c4ff7fc05c14492f704e911.
- Exact included files; renamed/deleted/excluded files and reasons: included `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt`; excluded the test file (test code). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (AGP 9.4.1); `./gradlew testDebugUnitTest --tests '*CalendarGridScrollTest*' createDebugUnitTestCoverageReport -q` in `android/`; selection: `CalendarGridScrollTest` only.
- Run start/completion time and exit status: test execution 2026-11-22T08:18:33Z (exec data written 08:18:41Z); report task 08:18:49Z to 08:18:52Z; exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` sha256 74a125510d57f2989061a473c477f59b7b7c0445a1fd04c27b9161be8160ea0a (from `android/app/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec`).
- Evidence outputs are fresh and inputs stayed unchanged: the exec file comes from the focused test run above on the final test file (the coverage invocation found the test task up to date with the same inputs and selection), the report was regenerated at 08:18:52Z; no source or test file was edited after that run.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt` (whole file, this class only) | instructions 3489/7189 (48.5%) | 108/328 (32.9%) | methods 47/142 (33.1%) | 316/578 (54.7%) |

No aggregate beyond the one file; no scoped file is missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalendarScreen.kt:793` (3 of 6 branches) | Compose-generated key comparisons of `LaunchedEffect(period, state.shown, entriesByDay)` | Not material: compiler-generated changed-key branches, not behavior of the task |
| `CalendarScreen.kt:725-727` `GridPeriod` getters | generated accessors, never called directly | Not material |
| Rest of `CalendarScreen.kt` (agenda, month grid, toolbar, dialogs) | pre-existing code outside this task | Covered by other test classes of the full suite, not part of this focused run |

## Unable to test

- "later swipe" period change (criterion 4): swipe navigation is a later task; nothing to drive yet. Follow-up belongs to that task.
- Full `testDebugUnitTest`, `lintDebug` and `sh scripts/check.sh` (criterion 5): not run in this session for time; the parent worker must run them.

## Summary

Tests added and the focused validation passed (10/10, ktlint clean); task-scoped changed-line coverage is complete for lines; no production defects found. The full Android suite, `lintDebug` and `.taskcheck` remain for the parent worker.
