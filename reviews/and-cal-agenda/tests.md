# Test Report

Date: 2026-10-21

Task: and-cal-agenda - Android calendar agenda and day/week

Scope: `git diff 13770a5c0be6b453abb8cf6187b233354e8c0242..da1e945755bd2eb609bf748950175f5d3a6a1f88 -- android/`. Task commits: da3eaf15, e71e8e0a, 36178a06, 33f6c93d, 53493021 (merge of `origin/wip/and-cal-agenda`), 0181c227, 91805bb3, 90ea2896, da1e9457. 04e836da (new task and-cal-cache) touches only `tasks/` and is excluded. All changed production files are in the task's `touches` folder `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda`, plus `android/app/src/main/res/values/strings_calendar_agenda.xml`. The commit list and HEAD come from the parent. The test-writer had no shell this round, so it could not re-check them with git. It did read the production change and the new test at HEAD.

This report replaces the one written at 90ea2896.

Change since the previous report (da1e9457, code-reviewer P2): `Agenda` in `CalendarScreen.kt:374-384` now sets the saveable `positionedFrom` back to `null` while the agenda has no days. Before this fix, a process death restored `positionedFrom` but not the days. When the days arrived, `positionedFrom == from` skipped the scroll to the top, and the list kept the restored footer in view. The parent added a second test to `CalendarAgendaPositionTest`.

Requirements:
- `tasks/and-cal-agenda.md` acceptance criteria 1 and 2, and the recorded assumption (offline Room cache deferred to and-cal-cache)
- SPEC.md §9.2 item 4 (as cited by the task)
- `reviews/and-cal-agenda/review.md` and `reviews/and-cal-agenda/fixes.md` (CR-1 resume staleness, CR-1 scroll position, P2 restored-but-empty agenda, TW-1)
- AGENTS.md project notes (test data fictitious, lint is part of the contract)

## Test files changed

None by the test-writer in this round.

Re-inspected for this round (committed at da1e9457):
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarAgendaPositionTest.kt` (parent; new test `after a restart that kept the saved state but not the days, the days open the agenda at today`)

## Tests added or updated

Count: 0

Why none were needed: the new test is a real regression test for the fix. It scrolls to `day:2026-11-05`, then restores the saved instance state with a state that has the same `from` but no occurrences (`loaded = false, loading = true`), and then lets the days arrive. It asserts that today's header is displayed and the `MORE` footer is not. Without the `!hasItems -> positionedFrom = null` branch, the restored `positionedFrom` equals `from`, so nothing scrolls. The list would keep the footer it had while empty, and both assertions would fail. With this round, each of the three outcomes of the effect has a test:
1. No days: clear `positionedFrom`. Tested by the new `CalendarAgendaPositionTest` test, and also run by `CalendarAgendaStartTest` (days arriving after an empty agenda).
2. Days with a new `from`: scroll to the top. Tested by `CalendarAgendaStartTest` (2 tests).
3. Days with the same `from` after a restore: keep the position. Tested by `CalendarAgendaPositionTest.an agenda shown again with its days keeps where it was scrolled to`.

No further real gap remains, so no classes were added.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Agenda list, range loading (30 days from today, grows by range, guards, failures, supersession, de-duplication across ranges, new day resets) | `CalendarViewModelTest`, `CalendarViewModelEdgeCasesTest`, `CalendarRepositoryTest`, `CalendarRepositoryEdgeCasesTest` | parent on da1e9457: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' lintDebug createDebugUnitTestCoverageReport` | Pass |
| AC1 Agenda infinite scroll in the UI: first end, later ends with many/few events, empty range does not load by itself, answer arriving later, end reached while reloading (TW-1) | `CalendarAgendaInfiniteScrollTest` (5), `CalendarScreensTest.the agenda shows the days with events, ...`, `CalendarScreensStatesTest.an agenda that does not fill the screen does not load later days by itself` | same parent command | Pass |
| AC1 Agenda reloads when the app comes back, also on a later day (CR-1 resume) | `CalendarRouteResumeTest` (1) | same parent command | Pass |
| AC1 Agenda opens at its first day (fix in 91805bb3) | `CalendarAgendaStartTest` (2) | same parent command | Pass (2/2) |
| AC1 Agenda keeps its scroll position after rotation/tab switch (CR-1 P2, fix in 90ea2896) | `CalendarAgendaPositionTest.an agenda shown again with its days keeps where it was scrolled to` | same parent command | Pass |
| AC1 Agenda restored without its days opens at today when they arrive (review P2, fix in da1e9457) | `CalendarAgendaPositionTest.after a restart that kept the saved state but not the days, the days open the agenda at today` | same parent command | Pass |
| AC1 Day and week views (ranges, move by day/week, locale first day, today, open day, grid layout, all-day strip, now line) | `CalendarViewModelTest`, `CalendarViewModelEdgeCasesTest`, `AgendaGroupingTest`, `AgendaGroupingEdgeCasesTest`, `CalendarScreensTest`, `CalendarScreensStatesTest` | same parent command | Pass |
| AC1 Calendar visibility (PATCH with only `visible` and If-Match, optimistic toggle, refused/offline undo, pending write disables the chip, stale lists do not undo) | `CalendarRepositoryTest`, `CalendarViewModelTest`, `CalendarViewModelEdgeCasesTest`, `CalendarScreensTest` | same parent command | Pass |
| AC2 Grouping by day (local zone, all-day first, multi-day cut to range, exclusive midnight end, DST day, zero-length/no end/backwards, ties) | `AgendaGroupingTest` (7), `AgendaGroupingEdgeCasesTest` (14) | same parent command | Pass |
| AC2 Robolectric/Compose UI tests for screens | `CalendarScreensTest`, `CalendarScreensStatesTest`, `CalendarScreenInAppTest` (Hilt `MainActivity`), `CalendarAgendaInfiniteScrollTest`, `CalendarRouteResumeTest`, `CalendarAgendaStartTest`, `CalendarAgendaPositionTest` | same parent command | Pass |
| AC2 `testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*'` passes | all of the above | same parent command on da1e9457 | Pass: BUILD SUCCESSFUL, 98 tests, 0 failures |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' lintDebug createDebugUnitTestCoverageReport`

Result:

Pass (run by the parent on da1e9457; Gradle is denied to the test-writer): BUILD SUCCESSFUL, 98 tests, 0 failures, including `CalendarAgendaPositionTest` 2/2. The test-writer did not re-read the generated test reports.

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

Fixed in 91805bb3 and unchanged since. The trigger emits `rangeStart to totalItemsCount` near the end when not busy, de-duplicated with `distinctUntilChanged`. The test passes in the parent's run on da1e9457.

Severity:

Low.

Fix required:

`No — resolved in 91805bb3; the test passes in the parent's run on da1e9457.`

## Coverage

Status: verified (per the parent's run on the final revision da1e9457, which includes every test in the task).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. All 4 files are new in this task (apart from one changed line in `CalendarScreen.kt`'s placeholder entry point), so whole-file coverage is close to changed-line coverage here.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-cal-agenda; base 13770a5c0be6b453abb8cf6187b233354e8c0242; measured HEAD da1e945755bd2eb609bf748950175f5d3a6a1f88; task commits listed under Scope (from the parent and the earlier git check at 91805bb3); file scope = the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD da1e9457 with no uncommitted source or test inputs (per the parent; the test-writer changed no test files this round).
- Exact included files; renamed/deleted/excluded files and reasons: `CalendarRepository.kt`, `CalendarModels.kt`, `CalendarScreen.kt`, `CalendarViewModel.kt` in `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/`. Excluded: Hilt-generated classes (generated code; the parent reported them as negligible) and `strings_calendar_agenda.xml` (resources, not executable). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo (version from the Android Gradle config; not read). `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' lintDebug createDebugUnitTestCoverageReport` in `android/`.
- Run start/completion time and exit status: BUILD SUCCESSFUL, 98 tests, 0 failures (per the parent; times not supplied).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (not hashed: the test-writer had no shell for that).
- Evidence outputs are fresh and inputs stayed unchanged: per the parent only; the test-writer could not check this independently.
- Final run after latest test additions and production fixes: yes (da1e9457 is the final code, and it contains every task test).

Values below are covered/total (percentage) from the parent's JaCoCo report. Metrics the parent did not supply are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/.../calendar/agenda/CalendarRepository.kt` | N/A (not supplied) | 8/8 (100%) | N/A (not supplied) | 49/51 (96.1%) |
| `android/.../calendar/agenda/CalendarModels.kt` | N/A (not supplied) | 96/104 (92.3%) | N/A (not supplied) | 127/127 (100%) |
| `android/.../calendar/agenda/CalendarScreen.kt` | N/A (not supplied) | 203/244 (83.2%) | N/A (not supplied) | 474/477 (99.4%) |
| `android/.../calendar/agenda/CalendarViewModel.kt` | N/A (not supplied) | 100/104 (96.2%) | N/A (not supplied) | 176/176 (100%) |
| Aggregate (summed, 4 files; Hilt-generated and resources excluded) | N/A | 407/460 (88.5%) | N/A | 826/831 (99.4%) |

The `CalendarScreen.kt` line total grew from 475 to 477 with the `!hasItems` branch, and both new lines are covered. The branch total stayed at 244 with 203 covered. Most of the 41 missed branches in this file are synthetic branches from the Compose compiler (`$changed`/`$default` bitmasks, `remember`/`rememberSaveable` keys).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalendarRepository.kt`, 2 lines (exact lines not supplied; most likely the Hilt `@Provides` bodies in `CalendarAgendaModule`) | DI wiring | Non-material. The wiring runs in `CalendarScreenInAppTest` through Hilt-generated factories |
| `CalendarModels.kt`, 8 branches (locations not supplied) | Probably Kotlin null-safe chains in `Occurrence.days`/`entryOn` and data-class/comparator branches | Every documented case is tested in `AgendaGroupingEdgeCasesTest` (no end, end before start, missing start, missing end date). No test added |
| `CalendarScreen.kt`, 3 lines / 41 branches (locations not supplied) | Mostly Compose compiler bitmask branches and presentational conditions (colors, today highlight, now line). The behavioral ones are covered: the infinite-scroll trigger and the three outcomes of the positioning effect (clear / scroll to top / keep) | Infinite scroll: 5 tests. Positioning: `CalendarAgendaStartTest` (2) and `CalendarAgendaPositionTest` (2). The rest is presentational or compiler-generated. No test added |
| `CalendarViewModel.kt`, 4 branches (locations not supplied) | Probably the `computeIfPresent` version comparison in visibility handling, or Elvis fallbacks | Covered behaviorally by the visibility edge-case tests. No test added |

The parent did not supply the exact uncovered locations, so the classification above comes from reading the code.

## Unable to test

- The test-writer could not run Gradle, git, `scripts/check.sh` or `.taskcheck` in this round. Every result above comes from the parent's run on da1e9457. The parent runs `.taskcheck` before `done`.
- Nobody ran the new `CalendarAgendaPositionTest` test against the code before da1e9457 to see it fail. Reading the code says it would: the restored `positionedFrom` equals `from`, so the effect skips `scrollToItem(0)`, and the list keeps the footer that was its only item while it had no days.
- A real device rotation or process death was not exercised. `StateRestorationTester` emulates the saved-instance-state round trip under Robolectric, which is the mechanism `rememberSaveable` relies on. The process-death test shows the restarted state by swapping in a state without days; it does not start a new process.
- Agenda offline Room cache (SPEC §9.3): out of scope by the recorded assumption (and-cal-cache).

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. On the final revision da1e9457 the task suite passes (98 tests, 0 failures, parent's run). The P2 fix has a real regression test (the new `CalendarAgendaPositionTest` test), and all three outcomes of the positioning effect now have tests. Task-scoped coverage: lines 826/831 (99.4%), branches 407/460 (88.5%). The remaining gaps are DI wiring, compiler-generated branches, or presentational code. No tests were added in this round.
