# Test Report

Date: 2026-10-21

Task: and-cal-agenda - Android calendar agenda and day/week

Scope: `git diff 13770a5c0be6b453abb8cf6187b233354e8c0242..90ea2896406ebe8d7b9dd6294eefe6aab0140082 -- android/`. Task commits: da3eaf15, e71e8e0a, 36178a06, 33f6c93d, 53493021 (merge of `origin/wip/and-cal-agenda`), 0181c227, 91805bb3, 90ea2896. 04e836da (new task and-cal-cache) touches only `tasks/` and is excluded. All changed production files are in the task's `touches` folder `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda`, plus `android/app/src/main/res/values/strings_calendar_agenda.xml`. The commit list and HEAD come from the parent. The test-writer had no shell this round, so it could not re-check them with git. It did read the production change and the new test at HEAD.

This report replaces the one written at 91805bb3.

Change since the previous report (90ea2896, code-reviewer CR-1, P2): `Agenda` in `CalendarScreen.kt:369-380` now scrolls to the top only once for each `from`. It keeps that `from` in a `rememberSaveable` `positionedFrom`, so after a rotation or a tab switch the agenda keeps its scroll position. The parent added `CalendarAgendaPositionTest` (`StateRestorationTester`) and committed the test-writer's `CalendarAgendaStartTest`.

Requirements:
- `tasks/and-cal-agenda.md` acceptance criteria 1 and 2, and the recorded assumption (offline Room cache deferred to and-cal-cache)
- SPEC.md §9.2 item 4 (as cited by the task)
- `reviews/and-cal-agenda/review.md` and `reviews/and-cal-agenda/fixes.md` (CR-1 resume staleness, CR-1 scroll position, TW-1)
- AGENTS.md project notes (test data fictitious, lint is part of the contract)

## Test files changed

None by the test-writer in this round.

Re-inspected for this round (committed at 90ea2896):
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarAgendaPositionTest.kt` (parent, CR-1 scroll position)
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda/CalendarAgendaStartTest.kt` (test-writer, previous round)

## Tests added or updated

Count: 0

Why none were needed: the new fix has two behaviors, and each has a test that would fail without it:
1. Restored state with the same `from` keeps the position. `CalendarAgendaPositionTest.an agenda shown again with its days keeps where it was scrolled to` scrolls 25 days down to `day:2026-11-05`, restores the saved instance state, and asserts that this day is displayed and today's header is not. Without `positionedFrom`, the restored composition starts `LaunchedEffect(state.from, hasItems)` again and scrolls back to item 0, so the test would fail. It is a real regression test.
2. A new `from`, or days arriving into an empty list, still scroll to the top. `CalendarAgendaStartTest` (2 tests: days arriving after an empty agenda, and reloading from a later day while scrolled down) still covers this. Both pass at 90ea2896.

`CalendarAgendaStartTest` and `CalendarAgendaPositionTest` together cover both branches of `hasItems && positionedFrom != from`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Agenda list, range loading (30 days from today, grows by range, guards, failures, supersession, de-duplication across ranges, new day resets) | `CalendarViewModelTest`, `CalendarViewModelEdgeCasesTest`, `CalendarRepositoryTest`, `CalendarRepositoryEdgeCasesTest` | parent on 90ea2896: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' lintDebug createDebugUnitTestCoverageReport` | Pass |
| AC1 Agenda infinite scroll in the UI: first end, later ends with many/few events, empty range does not load by itself, answer arriving later, end reached while reloading (TW-1) | `CalendarAgendaInfiniteScrollTest` (5), `CalendarScreensTest.the agenda shows the days with events, ...`, `CalendarScreensStatesTest.an agenda that does not fill the screen does not load later days by itself` | same parent command | Pass |
| AC1 Agenda reloads when the app comes back, also on a later day (CR-1 resume) | `CalendarRouteResumeTest` (1) | same parent command | Pass |
| AC1 Agenda opens at its first day (fix in 91805bb3) | `CalendarAgendaStartTest` (2) | same parent command | Pass (2/2) |
| AC1 Agenda keeps its scroll position after rotation/tab switch (CR-1 P2, fix in 90ea2896) | `CalendarAgendaPositionTest` (1) | same parent command | Pass |
| AC1 Day and week views (ranges, move by day/week, locale first day, today, open day, grid layout, all-day strip, now line) | `CalendarViewModelTest`, `CalendarViewModelEdgeCasesTest`, `AgendaGroupingTest`, `AgendaGroupingEdgeCasesTest`, `CalendarScreensTest`, `CalendarScreensStatesTest` | same parent command | Pass |
| AC1 Calendar visibility (PATCH with only `visible` and If-Match, optimistic toggle, refused/offline undo, pending write disables the chip, stale lists do not undo) | `CalendarRepositoryTest`, `CalendarViewModelTest`, `CalendarViewModelEdgeCasesTest`, `CalendarScreensTest` | same parent command | Pass |
| AC2 Grouping by day (local zone, all-day first, multi-day cut to range, exclusive midnight end, DST day, zero-length/no end/backwards, ties) | `AgendaGroupingTest` (7), `AgendaGroupingEdgeCasesTest` (14) | same parent command | Pass |
| AC2 Robolectric/Compose UI tests for screens | `CalendarScreensTest`, `CalendarScreensStatesTest`, `CalendarScreenInAppTest` (Hilt `MainActivity`), `CalendarAgendaInfiniteScrollTest`, `CalendarRouteResumeTest`, `CalendarAgendaStartTest`, `CalendarAgendaPositionTest` | same parent command | Pass |
| AC2 `testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*'` passes | all of the above | same parent command on 90ea2896 | Pass: BUILD SUCCESSFUL, 97 tests, 0 failures |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' lintDebug createDebugUnitTestCoverageReport`

Result:

Pass (run by the parent on 90ea2896; Gradle is denied to the test-writer): BUILD SUCCESSFUL, 97 tests, 0 failures, including `CalendarAgendaStartTest` 2/2 and `CalendarAgendaPositionTest`. The test-writer did not re-read the generated test reports.

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

Fixed in 91805bb3 and unchanged in 90ea2896. The trigger emits `rangeStart to totalItemsCount` near the end when not busy, de-duplicated with `distinctUntilChanged`. The test passes in the parent's run on 90ea2896.

Severity:

Low.

Fix required:

`No — resolved in 91805bb3; the test passes in the parent's run on 90ea2896.`

## Coverage

Status: verified (per the parent's run on the final revision 90ea2896, which includes every test in the task).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. All 4 files are new in this task (apart from one changed line in `CalendarScreen.kt`'s placeholder entry point), so whole-file coverage is close to changed-line coverage here.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-cal-agenda; base 13770a5c0be6b453abb8cf6187b233354e8c0242; measured HEAD 90ea2896406ebe8d7b9dd6294eefe6aab0140082; task commits listed under Scope (from the parent and the earlier git check at 91805bb3); file scope = the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 90ea2896 with no uncommitted source or test inputs (per the parent; the test-writer changed no test files this round).
- Exact included files; renamed/deleted/excluded files and reasons: `CalendarRepository.kt`, `CalendarModels.kt`, `CalendarScreen.kt`, `CalendarViewModel.kt` in `android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda/`. Excluded: Hilt-generated classes (generated code; the parent reported them as negligible) and `strings_calendar_agenda.xml` (resources, not executable). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo (version from the Android Gradle config; not read). `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' lintDebug createDebugUnitTestCoverageReport` in `android/`.
- Run start/completion time and exit status: BUILD SUCCESSFUL, 97 tests, 0 failures (per the parent; times not supplied).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (not hashed: the test-writer had no shell for that).
- Evidence outputs are fresh and inputs stayed unchanged: per the parent only; the test-writer could not check this independently.
- Final run after latest test additions and production fixes: yes (90ea2896 is the final code, and it contains every task test).

Values below are covered/total (percentage) from the parent's JaCoCo report. Metrics the parent did not supply are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/.../calendar/agenda/CalendarRepository.kt` | N/A (not supplied) | 8/8 (100%) | N/A (not supplied) | 49/51 (96.1%) |
| `android/.../calendar/agenda/CalendarModels.kt` | N/A (not supplied) | 96/104 (92.3%) | N/A (not supplied) | 127/127 (100%) |
| `android/.../calendar/agenda/CalendarScreen.kt` | N/A (not supplied) | 203/244 (83.2%) | N/A (not supplied) | 472/475 (99.4%) |
| `android/.../calendar/agenda/CalendarViewModel.kt` | N/A (not supplied) | 100/104 (96.2%) | N/A (not supplied) | 176/176 (100%) |
| Aggregate (summed, 4 files; Hilt-generated and resources excluded) | N/A | 407/460 (88.5%) | N/A | 824/829 (99.4%) |

The `CalendarScreen.kt` totals grew from 471 to 475 lines and from 236 to 244 branches with the `positionedFrom` change. Of those 8 new branches, 5 are covered. The new lines are all covered. Most of the 41 missed branches in this file are synthetic branches from the Compose compiler (`$changed`/`$default` bitmasks, `remember`/`rememberSaveable` keys).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalendarRepository.kt`, 2 lines (exact lines not supplied; most likely the Hilt `@Provides` bodies in `CalendarAgendaModule`) | DI wiring | Non-material. The wiring runs in `CalendarScreenInAppTest` through Hilt-generated factories |
| `CalendarModels.kt`, 8 branches (locations not supplied) | Probably Kotlin null-safe chains in `Occurrence.days`/`entryOn` and data-class/comparator branches | Every documented case is tested in `AgendaGroupingEdgeCasesTest` (no end, end before start, missing start, missing end date). No test added |
| `CalendarScreen.kt`, 3 lines / 41 branches (locations not supplied) | Mostly Compose compiler bitmask branches and presentational conditions (colors, today highlight, now line). The behavioral ones, the infinite-scroll trigger and the scroll-to-start/keep-position effect, are covered | Infinite scroll: 5 tests. Scroll-to-start: `CalendarAgendaStartTest`. Keep position: `CalendarAgendaPositionTest`. Both outcomes of `hasItems && positionedFrom != from` are tested. The rest is presentational or compiler-generated. No test added |
| `CalendarViewModel.kt`, 4 branches (locations not supplied) | Probably the `computeIfPresent` version comparison in visibility handling, or Elvis fallbacks | Covered behaviorally by the visibility edge-case tests. No test added |

The parent did not supply the exact uncovered locations, so the classification above comes from reading the code.

## Unable to test

- The test-writer could not run Gradle, git, `scripts/check.sh` or `.taskcheck` in this round. Every result above comes from the parent's run on 90ea2896. The parent runs `.taskcheck` before `done`.
- Whether `CalendarAgendaStartTest` and `CalendarAgendaPositionTest` fail against the code before their fixes was not checked by running them. Reading the code says they would: without `positionedFrom`, the restored effect scrolls to item 0, and without the scroll-to-start effect, the list keeps the `footer` key in view.
- A real device rotation or process death was not exercised. `StateRestorationTester` emulates the saved-instance-state round trip under Robolectric, which is the mechanism `rememberSaveable` relies on.
- Agenda offline Room cache (SPEC §9.3): out of scope by the recorded assumption (and-cal-cache).

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. On the final revision 90ea2896 the task suite passes (97 tests, 0 failures, parent's run). The CR-1 scroll-position fix has a real regression test (`CalendarAgendaPositionTest`). Together with `CalendarAgendaStartTest`, it covers both outcomes of the new condition. Task-scoped coverage: lines 824/829 (99.4%), branches 407/460 (88.5%). The remaining gaps are DI wiring, compiler-generated branches, or presentational code. No tests were added in this round.
