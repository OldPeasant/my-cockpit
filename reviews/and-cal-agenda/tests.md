# Test Report

Date: 2026-10-21

Task: and-cal-agenda - Android calendar agenda and day/week

Scope: `git diff 13770a5c0be6b453abb8cf6187b233354e8c0242..53493021 -- android/` as supplied by the parent worker (task commits da3eaf15, e71e8e0a, 36178a06, 33f6c93d). The test-writer was not allowed to run shell commands (git, Gradle), so it did not resolve the diff itself. It read every production and test file in the task's `touches` folders (`android/app/src/{main,test}/java/ch/sonensei/cockpit/calendar/agenda`), based on the parent's scope statement and the earlier review/fix reports. Uncommitted input: the new test file `CalendarAgendaInfiniteScrollTest.kt` (this report), nothing else known.

Requirements:
- `tasks/and-cal-agenda.md` acceptance criteria 1 and 2, and the recorded assumption (offline Room cache deferred to and-cal-cache)
- SPEC.md §9.2 item 4 (as cited by the task and the earlier review)
- `reviews/and-cal-agenda/review.md` (CR-1..CR-4) and `reviews/and-cal-agenda/fixes.md`
- AGENTS.md project notes (test data fictitious, lint is part of the contract)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarAgendaInfiniteScrollTest.kt` (new)

## Tests added or updated

Count: 2 (new; no existing test changed)

- `CalendarAgendaInfiniteScrollTest.reaching the end again after a range with many events loads the next days again`
- `CalendarAgendaInfiniteScrollTest.reaching the end again after a range with few events loads the next days again`

Both host `CalendarContent` with a state that grows like the view model's (each `loadMore` appends the next 30 days) and scroll to the agenda footer twice with `performScrollToKey("footer")`. The assertion is behavioral only: reaching the end of the newly loaded days asks for later days again (`loads > loadsAfterFirstEnd`). It does not prescribe how many loads happen in between.

## Requirement coverage

Existing tests were inspected against the criteria. 88 tests in 9 classes already exist; the parent ran them (results below).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Agenda list, range loading (30 days from today, grows by range, guards, failures, supersession, de-duplication across ranges, new day resets) | `CalendarViewModelTest` (`the agenda loads 30 days from today and grows by range`, ...), `CalendarViewModelEdgeCasesTest` (26 tests), `CalendarRepositoryTest`, `CalendarRepositoryEdgeCasesTest` (range in UTC from local midnight, 366/367-day split, failing part fails whole range, cancelled dropped) | parent: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' createDebugUnitTestCoverageReport` | Pass |
| AC1 Agenda infinite scroll in the UI, first range end | `CalendarScreensTest.the agenda shows the days with events, ...`, `CalendarScreensStatesTest.an agenda that does not fill the screen does not load later days by itself` | same parent command | Pass |
| AC1 Agenda infinite scroll in the UI, ends of later ranges | `CalendarAgendaInfiniteScrollTest` (both tests) | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.CalendarAgendaInfiniteScrollTest'` | Not run: the test-writer cannot run Gradle; the parent must run it. Expected by code analysis: "many events" passes, "few events" fails (TW-1) |
| AC1 Day and week views (ranges, move by day/week, locale first day, today, open day, grid layout, all-day strip, now line) | `CalendarViewModelTest.day and week views ...`, `CalendarViewModelEdgeCasesTest` (week/day tests), `AgendaGroupingTest`, `AgendaGroupingEdgeCasesTest` (layout lanes, minimum height), `CalendarScreensTest` (day/week grid tests, now line), `CalendarScreensStatesTest` (arrows, empty day/week) | parent command | Pass |
| AC1 Calendar visibility (PATCH with only `visible` and If-Match, optimistic toggle, refused/offline undo, pending write disables the chip, stale lists do not undo) | `CalendarRepositoryTest.visibility sends only the flag, ...`, `CalendarViewModelTest` (hide/refused), `CalendarViewModelEdgeCasesTest` (10 visibility tests), `CalendarScreensTest` (chip toggle, pending chip disabled) | parent command | Pass |
| AC2 Grouping by day (local zone, all-day first, multi-day cut to range, exclusive midnight end, DST day, zero-length/no end/backwards, ties) | `AgendaGroupingTest` (7), `AgendaGroupingEdgeCasesTest` (14) | parent command | Pass |
| AC2 Robolectric/Compose UI tests for screens | `CalendarScreensTest` (9), `CalendarScreensStatesTest` (14), `CalendarScreenInAppTest` (1, Hilt `MainActivity`) | parent command | Pass |
| AC2 `testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*'` passes | all of the above | parent command, on HEAD 53493021 before `CalendarAgendaInfiniteScrollTest` was added | Pass at 53493021 (88 tests, 0 failures/errors). With the new class it is expected to fail until TW-1 is fixed |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.CalendarAgendaInfiniteScrollTest'`

Result:

Not run by the test-writer (shell/Gradle denied). The parent must run it. Expected: 1 pass, 1 fail (TW-1).

Earlier run of the existing suite, by the parent on HEAD 53493021:
`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' createDebugUnitTestCoverageReport` -> BUILD SUCCESSFUL, 88 tests, 0 failures, 0 skipped. Confirmed by the test-writer from `android/app/build/reports/tests/testDebugUnitTest/index.html` (generated Oct 21, 2026, 4:51:58 PM): AgendaGroupingEdgeCasesTest 14, AgendaGroupingTest 7, CalendarRepositoryEdgeCasesTest 7, CalendarRepositoryTest 5, CalendarScreenInAppTest 1, CalendarScreensStatesTest 14, CalendarScreensTest 9, CalendarViewModelEdgeCasesTest 26, CalendarViewModelTest 5, all 100%.

## Full test execution

Command:

`sh scripts/check.sh`

Result:

Not run by the test-writer (shell denied). fixes.md records a pass of the full check on an earlier revision; that is not evidence for the current state.

## .taskcheck

Result:

Not run (shell denied; the parent runs it before `done`).

## Bugs found

### Agenda stops loading by itself after a range that added only a few entries

Finding ID:

`TW-1`

Test:

`CalendarAgendaInfiniteScrollTest.reaching the end again after a range with few events loads the next days again`

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt:348-357` (the `snapshotFlow { ... }.distinctUntilChanged().filter { it }.collect { loadMore() }` in `Agenda`)

Requirement:

AC1 "Agenda list (infinite scroll by range)". The code comment says: "near the end of a list that can scroll, the next range loads by itself."

Expected behavior:

Each time the user scrolls to the end of the loaded days of a scrollable agenda, the next range is asked for, as long as the previous load added something.

Actual behavior (from code analysis; the parent must confirm by running the test):

The trigger fires only when the "near the end" flag changes from false to true (`distinctUntilChanged().filter { it }`). If the appended range adds fewer than about `LOAD_AHEAD_ITEMS` (5) list items, the user is still within 5 items of the end after the load. The flag then stays `true`, so reaching the new end never fires again. The agenda stops loading by itself until the user scrolls back up more than 5 items, or taps the "More" button in the footer. The same thing happens when the guard in `CalendarViewModel.loadMore` drops a trigger, for example while `loading` is true or after a failed load. The flag stays true, so that trigger is used up. This is likely with sparse calendars (fewer than about 3 days with events per 30 days). The footer button is a manual fallback, so nothing is unreachable.

Severity:

Low (the footer "More" button still works). The new test fails until this is fixed, though, so it blocks the AC2 command from passing.

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

When fixing, keep `CalendarScreensStatesTest.an agenda that does not fill the screen does not load later days by itself` passing, and do not make an empty appended range load range after range on its own.

## Coverage

Status: partial-failed. The parent measured on HEAD 53493021 with the 88 existing tests. It was not re-measured after `CalendarAgendaInfiniteScrollTest` was added, because the test-writer cannot run Gradle.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-cal-agenda; base 13770a5c0be6b453abb8cf6187b233354e8c0242; measured HEAD 53493021 (short hash as supplied); task commits da3eaf15, e71e8e0a, 36178a06, 33f6c93d. Parent supplied all of these; the test-writer did not verify them with git.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 53493021 without uncommitted inputs (per the parent). The new test file is not in that measurement.
- Exact included files; renamed/deleted/excluded files and reasons: `CalendarRepository.kt`, `CalendarModels.kt`, `CalendarScreen.kt`, `CalendarViewModel.kt` in `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/`. Hilt-generated classes were excluded as generated code; the parent reported them as negligible. `strings_calendar_agenda.xml` (resources, not executable) was excluded.
- Provider/version, command, working directory, and test selection: JaCoCo (version from the Android Gradle config; not read). `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' createDebugUnitTestCoverageReport` in `android/`.
- Run start/completion time and exit status: test report generated Oct 21, 2026, 4:51:58 PM; exit BUILD SUCCESSFUL (per the parent).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (not hashed: the test-writer has no shell, and the file is too large to read).
- Evidence outputs are fresh and inputs stayed unchanged: per the parent only. The test-writer could not check this.
- Final run after latest test additions and production fixes: no. It must be re-run after TW-1 is fixed and with `CalendarAgendaInfiniteScrollTest` included.

Values below use covered/total (percentage) from the parent's JaCoCo XML; zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/.../calendar/agenda/CalendarRepository.kt` | N/A (not supplied) | 8/8 (100%) | N/A (not supplied) | 49/51 (96.1%) |
| `android/.../calendar/agenda/CalendarModels.kt` | N/A (not supplied) | 96/104 (92.3%) | N/A (not supplied) | 127/127 (100%) |
| `android/.../calendar/agenda/CalendarScreen.kt` | N/A (not supplied) | 135/154 (87.7%) | N/A (not supplied) | 458/461 (99.3%) |
| `android/.../calendar/agenda/CalendarViewModel.kt` | N/A (not supplied) | 100/104 (96.2%) | N/A (not supplied) | 176/176 (100%) |
| Aggregate (summed, 4 files; Hilt-generated excluded) | N/A | 339/370 (91.6%) | N/A | 810/815 (99.4%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalendarRepository.kt` 2 lines (exact lines not supplied; likely the `CalendarAgendaModule` Hilt `@Provides` bodies at 45/48) | DI wiring | Non-material; the wiring is exercised by `CalendarScreenInAppTest` through Hilt-generated factories |
| `CalendarModels.kt` 8 branches (locations not supplied) | Probably Kotlin null-safe chains in `Occurrence.days`/`entryOn`, and data-class/comparator branches | Every documented branch is tested in `AgendaGroupingEdgeCasesTest` (no end, end before start, missing start, missing end date). No test added |
| `CalendarScreen.kt` 3 lines / 19 branches (locations not supplied) | Composable branches (colors/today highlight, `remember` keys, NowLine loop) and the infinite-scroll trigger | New tests in `CalendarAgendaInfiniteScrollTest` cover the repeated scroll trigger (TW-1). The remaining branches are presentational |
| `CalendarViewModel.kt` 4 branches (locations not supplied) | Probably the `withCalendars` `computeIfPresent` version comparison, or Elvis fallbacks | Covered behaviorally by the visibility edge-case tests. No test added |

The parent did not supply exact uncovered locations, and the test-writer could not parse the 1.1 MB single-line XML report with the tools it had, so the classification above is by inspection.

## Unable to test

- The test-writer could not run anything (Bash denied): not the new tests, coverage, `scripts/check.sh` or `.taskcheck`, and it could not verify the git scope. The parent must run `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' createDebugUnitTestCoverageReport` again with the new class.
- Agenda offline Room cache (SPEC §9.3): out of scope by the recorded assumption (and-cal-cache).

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion.

The existing 88 tests cover the acceptance criteria thoroughly: range loading, grouping by day, day/week views, visibility and Compose/Robolectric screens. One real gap was found: infinite scroll was tested only for the first end of the list. `CalendarAgendaInfiniteScrollTest` fills it, and its "few events" case is expected to fail because of TW-1.
