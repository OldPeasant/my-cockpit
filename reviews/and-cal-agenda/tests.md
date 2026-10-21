# Test Report

Date: 2026-10-21

Task: and-cal-agenda - Android calendar agenda and day/week

Scope: `git diff 13770a5c0be6b453abb8cf6187b233354e8c0242..91805bb36aa9b58e71e3d065f8cd8a81ad054110 -- android/`. Task commits: da3eaf15, e71e8e0a, 36178a06, 33f6c93d, 53493021 (merge of `origin/wip/and-cal-agenda`), 0181c227, 91805bb3. 04e836da (new task and-cal-cache) touches only `tasks/` and is excluded. The test-writer checked HEAD, the commit list and the changed files with `git -C ... rev-parse/log/diff --stat`. All changed production files are in the task's `touches` folder `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda`, plus `android/app/src/main/res/values/strings_calendar_agenda.xml`. Uncommitted at report time: `reviews/and-cal-agenda/fixes.md` (parent's report, not a test input), this report, and the new test file `CalendarAgendaStartTest.kt` (see below).

This report replaces the one written at 53493021.

Requirements:
- `tasks/and-cal-agenda.md` acceptance criteria 1 and 2, and the recorded assumption (offline Room cache deferred to and-cal-cache)
- SPEC.md §9.2 item 4 (as cited by the task)
- `reviews/and-cal-agenda/review.md` and `reviews/and-cal-agenda/fixes.md` (CR-1 resume staleness, TW-1)
- AGENTS.md project notes (test data fictitious, lint is part of the contract)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarAgendaStartTest.kt` (new in this round, uncommitted)

Already committed in the task, and re-inspected for this round:
- `CalendarAgendaInfiniteScrollTest.kt` (test-writer's 2 tests from the previous round, plus 3 the parent added with the TW-1 fix)
- `CalendarRouteResumeTest.kt` (parent, CR-1)

## Tests added or updated

Count: 2 (new in this round; no existing test changed)

- `CalendarAgendaStartTest.days that arrive after the agenda was shown empty open it at today`
- `CalendarAgendaStartTest.an agenda reloaded from a later day while scrolled down opens at its new first day`

Why they were added: the parent fixed "agenda opened scrolled past today" (the `LazyColumn` kept the `footer` key as its first visible item when the days were inserted above it) with `LaunchedEffect(state.from, hasItems) { if (hasItems) listState.scrollToItem(0) }`. The only test cited for it is `CalendarRouteResumeTest`. Its agenda has 2 events, so the whole list fits on the screen. A lazy list whose content fits the viewport is scrolled back to fill it, so that test would most likely pass without the fix too. It is not a regression test for this bug. The new tests use agendas longer than the screen (20 days with an event each):
1. A list first shown with only its footer (not loaded yet), then filled: today's header must be displayed. This is the reported bug.
2. A list scrolled to its footer, then reloaded with `from` one day later (the resume-on-a-later-day path of CR-1): the new first day must be displayed. This covers the `state.from` key of the same effect.

Both use `CalendarContent` directly with a mutable state, the same pattern as `CalendarAgendaInfiniteScrollTest`. They assert only what the user sees (`assertIsDisplayed` on the day header).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Agenda list, range loading (30 days from today, grows by range, guards, failures, supersession, de-duplication across ranges, new day resets) | `CalendarViewModelTest`, `CalendarViewModelEdgeCasesTest`, `CalendarRepositoryTest`, `CalendarRepositoryEdgeCasesTest` | parent on 91805bb3: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' lintDebug createDebugUnitTestCoverageReport` | Pass |
| AC1 Agenda infinite scroll in the UI: first end, later ends with many/few events, empty range does not load by itself, answer arriving later, end reached while reloading (TW-1) | `CalendarAgendaInfiniteScrollTest` (5), `CalendarScreensTest.the agenda shows the days with events, ...`, `CalendarScreensStatesTest.an agenda that does not fill the screen does not load later days by itself` | same parent command | Pass |
| AC1 Agenda reloads when the app comes back, also on a later day (CR-1) | `CalendarRouteResumeTest` (1) | same parent command | Pass |
| AC1 Agenda opens at its first day (parent's fix in 91805bb3) | `CalendarAgendaStartTest` (2) | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.CalendarAgendaStartTest'` | Not run: the test-writer cannot run Gradle; the parent must run it. Expected by code analysis: both pass |
| AC1 Day and week views (ranges, move by day/week, locale first day, today, open day, grid layout, all-day strip, now line) | `CalendarViewModelTest`, `CalendarViewModelEdgeCasesTest`, `AgendaGroupingTest`, `AgendaGroupingEdgeCasesTest`, `CalendarScreensTest`, `CalendarScreensStatesTest` | parent command | Pass |
| AC1 Calendar visibility (PATCH with only `visible` and If-Match, optimistic toggle, refused/offline undo, pending write disables the chip, stale lists do not undo) | `CalendarRepositoryTest`, `CalendarViewModelTest`, `CalendarViewModelEdgeCasesTest`, `CalendarScreensTest` | parent command | Pass |
| AC2 Grouping by day (local zone, all-day first, multi-day cut to range, exclusive midnight end, DST day, zero-length/no end/backwards, ties) | `AgendaGroupingTest` (7), `AgendaGroupingEdgeCasesTest` (14) | parent command | Pass |
| AC2 Robolectric/Compose UI tests for screens | `CalendarScreensTest`, `CalendarScreensStatesTest`, `CalendarScreenInAppTest` (Hilt `MainActivity`), `CalendarAgendaInfiniteScrollTest`, `CalendarRouteResumeTest`, `CalendarAgendaStartTest` | parent command | Pass (94 tests); `CalendarAgendaStartTest` not run yet |
| AC2 `testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*'` passes | all of the above | parent command on 91805bb3 | Pass at 91805bb3: 94 tests, 0 failures (without `CalendarAgendaStartTest`). Must be re-run with it |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.CalendarAgendaStartTest'`

Result:

Not run by the test-writer (Gradle denied). The parent must run it. Expected: 2 pass.

Run of the task suite by the parent on 91805bb3 (committed code, without `CalendarAgendaStartTest`):
`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' lintDebug createDebugUnitTestCoverageReport` -> BUILD SUCCESSFUL, 94 tests, 0 failures. This result is as reported by the parent; the test-writer did not re-read the generated report this time.

## Full test execution

Command:

`sh scripts/check.sh`

Result:

Not run by the test-writer (shell denied). The parent runs it before `done`.

## .taskcheck

Result:

Not run (shell denied; the parent runs it before `done`).

## Bugs found

No open production defects.

### TW-1 (resolved): Agenda stopped loading by itself after a range that added only a few entries

Finding ID:

`TW-1`

Test:

`CalendarAgendaInfiniteScrollTest.reaching the end again after a range with few events loads the next days again`

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/CalendarScreen.kt:358-368`

Requirement:

AC1 "Agenda list (infinite scroll by range)".

Expected behavior:

Each time the user scrolls to the end of the loaded days of a scrollable agenda, the next range is asked for.

Actual behavior:

Fixed in 91805bb3. The trigger now emits `rangeStart to totalItemsCount` only when the list is near its end and not busy, de-duplicated with `distinctUntilChanged`. So it fires once for each list length (and range start), not only on a false-to-true change of "near the end". A range without events (same length) does not load range after range by itself. A trigger that came while busy is not used up. The test-writer checked the fix by reading the code. The parent's run shows the test passing, together with 3 more cases the parent added (empty range, answer arriving later, end reached while reloading).

Severity:

Low.

Fix required:

`No — resolved in 91805bb3; the test passes in the parent's run.`

## Coverage

Status: verified for the committed code at 91805bb3 (per the parent's run). It does not include the 2 new tests in `CalendarAgendaStartTest`. Those cover lines that are already covered (`CalendarScreen.kt:372-374`), so line counts should not change.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. All 4 files are new in this task (the diff adds whole files, apart from one changed line in `CalendarScreen.kt`'s placeholder entry point), so whole-file coverage is close to changed-line coverage here.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-cal-agenda; base 13770a5c0be6b453abb8cf6187b233354e8c0242; measured HEAD 91805bb36aa9b58e71e3d065f8cd8a81ad054110; task commits listed under Scope. The test-writer checked these with `git log`/`git diff --stat`; file scope = the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 91805bb3 with no uncommitted source or test inputs (per the parent). `CalendarAgendaStartTest.kt` was added after this run and is not in it.
- Exact included files; renamed/deleted/excluded files and reasons: `CalendarRepository.kt`, `CalendarModels.kt`, `CalendarScreen.kt`, `CalendarViewModel.kt` in `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/`. Excluded: Hilt-generated classes (generated code; the parent reported them as negligible), `strings_calendar_agenda.xml` (resources, not executable). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo (version from the Android Gradle config; not read). `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' lintDebug createDebugUnitTestCoverageReport` in `android/`.
- Run start/completion time and exit status: BUILD SUCCESSFUL, 94 tests, 0 failures (per the parent; times not supplied).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (not hashed: the test-writer has no shell for that).
- Evidence outputs are fresh and inputs stayed unchanged: per the parent only. The test-writer could not check this.
- Final run after latest test additions and production fixes: yes for production code (91805bb3 is the final code). No for tests: `CalendarAgendaStartTest` came after. The parent should include it in the final run.

Values below use covered/total (percentage) from the parent's JaCoCo report; zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/.../calendar/agenda/CalendarRepository.kt` | N/A (not supplied) | 8/8 (100%) | N/A (not supplied) | 49/51 (96.1%) |
| `android/.../calendar/agenda/CalendarModels.kt` | N/A (not supplied) | 96/104 (92.3%) | N/A (not supplied) | 127/127 (100%) |
| `android/.../calendar/agenda/CalendarScreen.kt` | N/A (not supplied) | 198/236 (83.9%) | N/A (not supplied) | 468/471 (99.4%) |
| `android/.../calendar/agenda/CalendarViewModel.kt` | N/A (not supplied) | 100/104 (96.2%) | N/A (not supplied) | 176/176 (100%) |
| Aggregate (summed, 4 files; Hilt-generated and resources excluded) | N/A | 402/452 (88.9%) | N/A | 820/825 (99.4%) |

The `CalendarScreen.kt` branch total grew from 154 to 236 because of the new trigger, resume effect and scroll-to-start code. JaCoCo counts many synthetic branches in Compose-compiled code (`$changed`/`$default` bitmasks, `remember` keys), so most of the 38 missed branches are compiler-generated.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalendarRepository.kt`, 2 lines (exact lines not supplied; most likely the Hilt `@Provides` bodies in `CalendarAgendaModule`) | DI wiring | Non-material. The wiring runs in `CalendarScreenInAppTest` through Hilt-generated factories |
| `CalendarModels.kt`, 8 branches (locations not supplied) | Probably Kotlin null-safe chains in `Occurrence.days`/`entryOn` and data-class/comparator branches | Every documented case is tested in `AgendaGroupingEdgeCasesTest` (no end, end before start, missing start, missing end date). No test added |
| `CalendarScreen.kt`, 3 lines / 38 branches (locations not supplied) | Mostly Compose compiler bitmask branches and presentational conditions (colors, today highlight, now line). Behavioral ones: infinite-scroll trigger (now covered by 5 tests) and the scroll-to-start effect | `CalendarAgendaStartTest` added for the scroll-to-start effect, which had no real regression test (see "Tests added"). The rest is presentational or compiler-generated |
| `CalendarViewModel.kt`, 4 branches (locations not supplied) | Probably the `computeIfPresent` version comparison in visibility handling, or Elvis fallbacks | Covered behaviorally by the visibility edge-case tests. No test added |

The parent did not supply exact uncovered locations, and the test-writer cannot read the single-line XML report with its tools, so the classification above is by inspection.

## Unable to test

- The test-writer could not run Gradle, `scripts/check.sh` or `.taskcheck`. The parent must run `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*'` again with `CalendarAgendaStartTest` included.
- Whether `CalendarAgendaStartTest` fails without the scroll-to-start fix was not checked (that needs running against the code before 91805bb3). The test-writer expects test 1 to fail without it, based on how a lazy list keeps its first visible key when items are inserted above it.
- Agenda offline Room cache (SPEC §9.3): out of scope by the recorded assumption (and-cal-cache).

## Summary

Tests added; no open production defects. TW-1 is resolved in 91805bb3 and its test passes in the parent's run (94 tests, 0 failures). Task-scoped coverage on the final code: lines 820/825 (99.4%), branches 402/452 (88.9%). One real gap remained: the "open the agenda at its first day" fix had no regression test that could fail, because the only test cited for it uses a list that fits on the screen. `CalendarAgendaStartTest` (2 tests) fills that gap. The parent must run it before `done`.
