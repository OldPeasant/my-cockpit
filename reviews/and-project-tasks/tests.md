# Test Report

Date: 2026-11-08

Task: and-project-tasks - Android: add tasks directly to a project and reorder them

Scope: second round, on the final code after the fixer. Base dbf4324fbbac3cdfc1292715157fa189f61bc96a, HEAD f2a6d38200dca6d2783e0a9922598dd63cbf06cd on main (`git diff dbf4324fb..HEAD -- android`). Task commits, as supplied by the parent: 04a480ed8 and 1a625cac1 (implementation), d55eefc66 (test-writer round 1), 6a521fbac (fixer: CR-1, CR-2, CR-3), f2a6d3820 (reports only). Before this round the working tree was clean. The only uncommitted change is this round's edit to `ProjectTaskListTest.kt`.

Requirements:
- tasks/and-project-tasks.md (acceptance criteria 1-5 and the recorded assumptions)
- AGENTS.md project notes, docs/ARCHITECTURE.md ("Coverage", Android test commands)
- reviews/and-project-tasks/review.md (CR-1, CR-2, CR-3) and reviews/and-project-tasks/fixes.md (what the fixer changed)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectTaskListTest.kt` (this round)

Changed by the task as a whole, including round 1 and the fixer: `ProjectTaskListTest.kt`, `ProjectsViewModelTest.kt`, `ProjectWorkRepositoryTest.kt`, `ProjectsScreenTest.kt` (all in `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/`).

## Tests added or updated

Count: 2 added this round, none changed or removed.

This round focused on the fixes. The fixer's changes and the gaps that remained:

- CR-1 (drag saved only on drop). The drop path is covered by the existing drag tests. Two cancel paths are covered by the round-1 regression tests: the tasks change, and the screen goes read-only. One path had no test: the system cancels the gesture. That arrives as an up that is already consumed, and the new `lifted = !change.isConsumed` check decides it. No test showed either that `onCancel` leaves the drag state clean for the next gesture. Both new tests are about this.
- CR-2 (move actions on the focused row). The fixer moved the `actions`/`action` helpers to `TaskListTags.row` and added assertions: the row has OnClick, and the item no longer has CustomActions. Every move-action test now checks the focused node. No gap left that a Robolectric test can show.
- CR-3 (order saves serialized). The fixer added `a newer save sends its order only after the older one finished, so the server applies the last order last`. Together with the two overlapping-save tests, it covers FIFO sending, a failing older save, and the pending order. No new test was needed.

Added this round (ProjectTaskListTest, Compose on Robolectric):
- `a drag the system cancels is not saved, and the next drag starts from the state's order`: pick up, drag one row, then `cancel()` (ACTION_CANCEL). Expected: no reorder call and the state's order on screen. A fresh drag afterwards saves exactly `[Compare hotels, Book the flights, Visa from the embassy]`.
- `after a drag interrupted by a change of the tasks, the next drag saves an order of the new tasks`: the drag is interrupted by a task list of 2 tasks. The next drag must save `[Compare hotels, Book the flights]`, without the stale ids of the interrupted drag.

Test counts per file (`@Test` annotations) in the working tree: ProjectTaskListTest 22, ProjectsViewModelTest 82, ProjectWorkRepositoryTest 32, ProjectsScreenTest 63.

## Requirement coverage

Execution evidence: the parent ran `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests '*gtd.projects*'` on HEAD f2a6d3820. Result: ktlint passed, 226 tests in 8 gtd.projects classes, 0 failures. The test-writer cannot run Gradle in this environment. The 2 tests added this round were not part of that run. The parent still has to run them (command under "Focused test execution").

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Add task field creates a Next task in the project, quick entry stays open, optional make it the next step | Repository: adding a task creates a next action, as next step, stalled project, next step not set, offline, server unreachable after create. ViewModel: trims, next step failed, another project's field, once at a time, without an open project, same title twice. Compose: button and keyboard, field clears, no choice without next step, read-only or busy, blank title, first task of an empty project, text belongs to the open project. Screen: added task confirmed (en, de) | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests '*gtd.projects*'` (parent, HEAD f2a6d3820) | Pass |
| 2. Long-press drag reorder, Move up/down accessibility actions, order saved like on web | Repository: reordering sends the task ids, a saved order reloads. ViewModel: dragged order shows at once, refused order goes back, unchanged order, move up/down, other project, load during save, failed load, newer save sent only after older (CR-3), two overlapping-save tests, offline move. Compose: actions offered on the focused row and not read-only (CR-2), long press and drag, several frames, long press without moving, read-only, single task, dragged back, dropped order stays, interrupted by tasks change, interrupted by read-only (CR-1). Screen: moved message (en, de), offline, conflict | same parent run | Pass |
| 2 (CR-1, system cancel and state after a cancel) | `a drag the system cancels is not saved, and the next drag starts from the state's order`, `after a drag interrupted by a change of the tasks, the next drag saves an order of the new tasks` | `cd android && ./gradlew testDebugUnitTest --tests '*ProjectTaskListTest'` (for the parent) | Not run: added after the parent's run, and the test-writer cannot run Gradle |
| 2 (TalkBack actually lists the actions) | none | none | Not testable here: Robolectric semantics do not show what a screen reader focuses. Needs a device |
| 3. A task row opens task detail; other lists keep their list label | Compose: a task row opens task detail, and tasks of other lists keep their list label. Screen: list-label test | same parent run | Pass |
| 4. New strings in English and German | Compose: labelled in English / in German. Screen: added and moved messages in English and German | same parent run | Pass |
| 5. Unit/Compose tests cover the new behavior; `ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass | all of the above | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`, `sh scripts/check.sh` | Not run by the test-writer. The parent ran ktlint and the gtd.projects tests (pass). The parent must run lintDebug, the full testDebugUnitTest and `sh scripts/check.sh` |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests '*gtd.projects*'` (run by the parent on HEAD f2a6d3820)

Result:

Pass: ktlint passed, 226 tests, 0 failures. That run includes the fixer's tests and the round-1 regression tests for CR-1 (they failed before the fix, as reported in round 1).

Still to run, because of this round's 2 new tests:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests '*ProjectTaskListTest'`

Result: Not run (the test-writer cannot run Gradle). The test code was written in the file's ktlint style, with no line over 140 characters, and needs no new imports (`cancel()` belongs to the Compose `TouchInjectionScope`).

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run by the test-writer (Gradle cannot run in a sub-agent). The parent runs it.

## .taskcheck

Result:

Not run by the test-writer (`sh scripts/check.sh` runs Gradle). The parent runs it.

## Bugs found

None in this round. TW-1 from round 1 (same as CR-1) was fixed in 6a521fbac. The parent's run shows that its two regression tests now pass. Reading the code, the two new tests should pass on the fixed code: `onCancel` calls `drag.stop()`, which clears `order` and `taskId`, and `dropped` is keyed on `ids`. If one of them fails, report it back. It would show a remaining CR-1 defect, or a mistake in the test.

## Coverage

Status: unavailable. The Android coverage task (`cd android && ./gradlew createDebugUnitTestCoverageReport`) runs Gradle, which sub-agents cannot do in this environment. There is an old `android/app/build/reports/coverage/test/debug/report.xml` from 2026-11-08 09:59:53 +0100. It predates the fixer commit (13:08:33 +0100) and this round's tests, so it is stale and was not used.

Metric: whole-file coverage of task-changed production files (not measured).
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-project-tasks; base dbf4324fbbac3cdfc1292715157fa189f61bc96a; HEAD f2a6d38200dca6d2783e0a9922598dd63cbf06cd; commits 04a480ed8, 1a625cac1, d55eefc66, 6a521fbac, f2a6d3820, as supplied by the parent and matching `git log dbf4324fb..HEAD`
- Source/test snapshot identifier (including uncommitted inputs): HEAD f2a6d3820 plus the uncommitted `ProjectTaskListTest.kt` with sha256 39d77f806b8cb0a66a016847903237695005b83b03f5f64e5df00bae3164a04d. No coverage run measured this snapshot
- Exact included files: `ProjectWorkRepository.kt`, `ProjectsViewModel.kt`, `ProjectTaskList.kt`, `ProjectsScreen.kt`, `ProjectForm.kt` (all under `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/`). The two `strings_projects_lists.xml` files are excluded because they are not executable. No renamed or deleted files
- Provider/version, command, working directory, and test selection: none run (JaCoCo via AGP would be the provider)
- Run start/completion time and exit status: none
- Generated report paths and report hashes/run identifier: none fresh. The existing report.xml is stale (see above)
- Evidence outputs are fresh and inputs stayed unchanged: not applicable, no run
- Final run after latest test additions and production fixes: no. Gradle cannot run in a sub-agent. If the parent wants numbers, it can run `cd android && ./gradlew createDebugUnitTestCoverageReport` and read the five files from `report.xml`

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `ProjectWorkRepository.kt` | N/A (not measured) | N/A | N/A | N/A |
| `ProjectsViewModel.kt` | N/A (not measured) | N/A | N/A | N/A |
| `ProjectTaskList.kt` | N/A (not measured) | N/A | N/A | N/A |
| `ProjectsScreen.kt` | N/A (not measured) | N/A | N/A | N/A |
| `ProjectForm.kt` | N/A (not measured) | N/A | N/A | N/A |

No aggregate: nothing was measured.

Without numbers, here are the gaps found by reading the final code:

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ProjectTaskList.kt` `longPressDrag`: consumed up (`lifted = !change.isConsumed` false branch) | Criterion 2 / CR-1: a gesture the system cancels must not save | Added `a drag the system cancels is not saved, and the next drag starts from the state's order` (not yet run) |
| `ProjectTaskList.kt` `onCancel` followed by a new gesture | Criterion 2: a cancel must not leave a stale drag order behind | Added `after a drag interrupted by a change of the tasks, the next drag saves an order of the new tasks`, and the second half of the system-cancel test (not yet run) |
| `ProjectTaskList.kt` `longPressDrag`: `?: break` when the tracked pointer is missing from an event | Defensive. Robolectric's single-pointer touch injection cannot leave out a pointer that is down | Not tested. It goes to `onCancel`, the same path the tests above cover |
| `ProjectTaskList.kt` `DragState.by` early returns (stale layout, target not visible, `requestScrollToItem`) | Drag robustness | Partly covered by the multi-frame drag tests. Auto-scroll at the list edges is out of scope by assumption |
| `ProjectsViewModel.kt` `saveOrder` under `orderSaves.withLock` | CR-3: FIFO sending | Covered by the fixer's new test and the two overlapping-save tests (parent run: pass) |
| `ProjectForm.kt` `ProjectMessageEffect` FAILED outcome for MOVED and TASK_ADDED | Message shown | Uses the shared `tasks_message_failed` string, which existing tests already cover |

## Unable to test

- TalkBack offering "Move up"/"Move down" on the row it focuses (CR-2). The tests check that the actions sit on the merged node with OnClick and no longer on the item. Whether a screen reader really lists them needs a device or emulator. Follow-up: the parent records this as a skip or assumption.
- Running this round's 2 tests, lintDebug, `sh scripts/check.sh` and coverage. Sub-agents cannot run Gradle here. Follow-up: the parent runs them and reports any compile or ktlint problem in the new tests back.

## Summary

Tests added and the parent's validation passed on HEAD f2a6d3820. The fixes for CR-1, CR-2 and CR-3 are covered: by the fixer's tests, by the round-1 regression tests (which now pass), and by 2 new ProjectTaskListTest tests for the system-cancel path and for the drag state after a cancel. The 2 new tests have not run yet: the parent must run them before sign-off. No production defects were found in this round. Coverage could not be measured (Gradle cannot run in a sub-agent).
