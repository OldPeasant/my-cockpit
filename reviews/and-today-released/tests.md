# Test Report

Date: 2026-10-26

Task: and-today-released - Android Today: show tasks released to Next today under 'Scheduled for today' (releasedOn / scheduled=today)

Scope: base 8a5b73fa2048571afb147f3dd61df1852be170f8; implementation commit 0ff2cfc2b (`git diff 8a5b73fa2..0ff2cfc2b -- android`); final reviewed revision 8a3a65622f523d6b9b97bc27619df013c488bbba (0ff2cfc2b plus the tests in this report, committed by the parent). Production files in scope, under `android/app/src/main/java/ch/sonensei/cockpit/gtd/`: `today/TodayViewModel.kt`, `data/GtdModels.kt`, `data/GtdMappers.kt`, `data/GtdCacheDatabase.kt`, `lists/TaskLists.kt`. The parent supplied this file list and confirmed the paths. The test-writer could not run git itself (Bash was denied in this session), so it read the production sources directly.

Requirements:
- `tasks/and-today-released.md` acceptance criterion 1 and its recorded assumption (releasedOn cached in a new column, Room v2 with destructive fallback)
- SPEC.md §5.3 (Today view), as quoted in the `TodayViewModel` KDoc
- Parent worker instructions

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/today/TodayViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/TaskRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/TaskListsRepositoryTest.kt`

## Tests added or updated

Count: 3 (added; no existing test changed)

- `TodayViewModelTest`: `released today still yields to overdue, and an earlier release does not hide a schedule for today`. Checks section precedence: overdue wins over released today; released today with a future due date is SCHEDULED; a release on an earlier day does not hide a scheduled date of today; a releasedOn after today is not for today.
- `TaskRepositoryTest`: `a task the server did not release has no release day in the cache`. A response without `releasedOn` is cached and maps back to `null` (the cache round trip of a missing field).
- `TaskListsRepositoryTest`: `a loaded task keeps the day the server released it to Next`. `TaskResponse.toTask()` in `TaskLists.kt` maps `releasedOn`, both present and absent, for the live (uncached) lists.

The implementation already added these tests; the test-writer reviewed and kept them: `TodayViewModelTest` `a task released to Next today is scheduled for today, one released earlier is not` and `the Scheduled for today section shows the Next tasks the server released today`; `TaskRepositoryTest` `refresh stores the loaded tasks with all fields` (asserts `releasedOn`).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Today 'Scheduled for today' shows tasks released to Next today (releasedOn == today) | `a task released to Next today is scheduled for today, one released earlier is not`; `the Scheduled for today section shows the Next tasks the server released today`; `released today still yields to overdue, and an earlier release does not hide a schedule for today` | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` on 8a3a65622 (run by the parent) | Pass (TodayViewModelTest 28/28) |
| releasedOn is read from the API and kept through the Room cache | `refresh stores the loaded tasks with all fields`; `a task the server did not release has no release day in the cache` | same | Pass (TaskRepositoryTest 19/19) |
| releasedOn mapped for live lists (`TaskLists.kt`) | `a loaded task keeps the day the server released it to Next` | same | Pass (TaskListsRepositoryTest 7/7) |
| ktlintCheck testDebugUnitTest lintDebug pass | whole Android suite | same | Pass (BUILD SUCCESSFUL, 0 failures) |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` on 8a3a65622 (the parent ran it; it includes the three affected test classes)

Result:

Pass: TaskRepositoryTest 19/19, TodayViewModelTest 28/28, TaskListsRepositoryTest 7/7, 0 failures.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` on 8a3a65622 (run by the parent)

Result:

Pass (BUILD SUCCESSFUL).

## .taskcheck

Result:

Not run by the test-writer (no shell access). The parent runs `.taskcheck` before `done`.

## Bugs found

None. Reading `sectionOf` against the criterion found no defect. A task with releasedOn == today maps to SCHEDULED after the overdue and due checks, which matches the precedence of the existing date rules and the `scheduled=today` semantics described in the KDoc.

## Coverage

Status: verified. The parent measured it on the final revision 8a3a65622, after the tests in this report were added. The test-writer could not run JaCoCo itself (no shell access); the figures below are the parent's.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-today-released; base 8a5b73fa2048571afb147f3dd61df1852be170f8; measured revision 8a3a65622f523d6b9b97bc27619df013c488bbba; task-owned commits 0ff2cfc2b (implementation) and 8a3a65622 (tests); file scope from the parent's diff and the task's recorded assumption.
- Source/test snapshot identifier (including uncommitted inputs): commit 8a3a65622f523d6b9b97bc27619df013c488bbba; the parent committed the added tests there, and no uncommitted inputs were reported.
- Exact included files; renamed/deleted/excluded files and reasons: the five files listed in Scope; no renames or deletions; test files excluded.
- Provider/version, command, working directory, and test selection: JaCoCo via `./gradlew createDebugUnitTestCoverageReport` in `android/` (report.xml), full debug unit test suite. The parent did not report the provider version.
- Run start/completion time and exit status: the parent reported a successful run on 8a3a65622; it did not report times.
- Generated report paths and report hashes/run identifier: report.xml under the Android build output; the parent did not report the path or hash.
- Evidence outputs are fresh and inputs stayed unchanged: as reported by the parent for 8a3a65622.
- Final run after latest test additions and production fixes: yes (no production fixes were needed).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `gtd/today/TodayViewModel.kt` | N/A (not reported) | 40/40 (100%) | N/A (not reported) | 55/55 (100%) |
| `gtd/data/GtdMappers.kt` | N/A (not reported) | 46/46 (100%) | N/A (not reported) | 80/80 (100%) |
| `gtd/data/GtdCacheDatabase.kt` | N/A (not reported) | N/A (not reported) | N/A (not reported) | 66/66 (100%) |
| `gtd/data/GtdModels.kt` | N/A (not reported) | N/A (not reported) | N/A (not reported) | 54/54 (100%) |
| `gtd/lists/TaskLists.kt` | N/A (not reported) | 26/28 (92.9%) | N/A (not reported) | 90/90 (100%) |

Aggregate (summed counters): lines 345/345 (100%) over all five files; branches 112/114 (98.2%) over the three files with reported branch counters (TodayViewModel.kt, GtdMappers.kt, TaskLists.kt). No scoped file is missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `gtd/lists/TaskLists.kt`: 2 of 28 branches (exact locations not reported) | Not part of this task's change. The `releasedOn = releasedOn` mapping has no branch; the remaining branches are null-safe calls in pre-existing code (such as `list?.let`, `energy?.let`, `creativity?.let`) | No test added for the gap; `a loaded task keeps the day the server released it to Next` now asserts the task's mapping directly |

## Unable to test

- Room v1 to v2 destructive migration: no test runs it on a real upgraded database. The database is a cache by design (recorded assumption), and the in-memory harness always creates v2. Low risk; no follow-up needed.
- The test-writer could not run commands itself; all execution and coverage results above come from the parent's runs on 8a3a65622.

## Summary

Tests added and all validation passed: the Android lint, unit tests and Android lint checks pass on 8a3a65622, and task-scoped coverage is sufficient. No production defects were found.
