# Test Report

Date: 2026-11-08

Task: and-project-tasks - Android: add tasks directly to a project and reorder them

Scope: base 05855c3ae0705c9237c32572055329581de3bf1b, task commits ad642d981 and fc78e00c9 (`git diff 05855c3ae..HEAD -- android`), HEAD fc78e00c9 on main. Before this report the working tree was clean. Task ownership comes from the commit list the parent supplied and from the task's `touches`.

Requirements:
- tasks/and-project-tasks.md (acceptance criteria 1-5 and the two recorded assumptions)
- AGENTS.md project notes, docs/ARCHITECTURE.md (Android test commands)
- reviews/and-project-tasks/review.md (CR-1 was used to choose the cancelled-drag regression tests)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectWorkRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectTaskListTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreenTest.kt`

## Tests added or updated

Count: 27 added by the test-writer, none changed or removed. These come on top of the parent's task tests.

Test counts per file (`@Test` annotations): ProjectsViewModelTest 73 -> 81 (+8), ProjectWorkRepositoryTest 29 -> 32 (+3), ProjectTaskListTest 10 -> 20 (+10), ProjectsScreenTest 57 -> 63 (+6).

Added by the test-writer:

ProjectsViewModelTest
- `adding a task without an open project does nothing`
- `two tasks added with the same title are told apart, so the field clears for each` (seq 1, then 2)
- `an order being saved for one project does not reorder another project's tasks` (the fake serves the same tasks for every project, so a missing project filter would fail this test)
- `a load that answers while the order is being saved keeps the new order, tasks not in it go last`
- `a failed load after saving keeps the saved order`
- `an older save that fails while a newer one runs does not undo the newer order` (CompletableDeferred controls the answer order)
- `an older save answering after a newer one was saved does not undo the newer order`
- `a move that cannot be saved offline goes back and says which task it was`

ProjectWorkRepositoryTest
- `a saved order reloads only the projects, offline it reloads nothing`
- `adding a task offline creates nothing and reloads nothing`
- `a task created just before the server became unreachable is still reported, without a next step`

ProjectTaskListTest (Compose, Robolectric)
- `a blank title adds nothing`
- `the first task of a project without tasks is added from the field`
- `the typed text belongs to the open project`
- `a single task can neither be dragged nor moved`
- `a task dragged back to its place saves nothing`
- `a dropped order stays in the list, and a refused one goes back` (row positions and move actions after the drop, then after the state has the order, then after a refusal)
- `a drag interrupted by a change of the tasks is not saved` (regression for TW-1 / CR-1, expected to FAIL on the current code)
- `a drag interrupted by going read-only is not saved and the list keeps the state's order` (regression for TW-1 / CR-1, expected to FAIL on the current code)
- `the add field and the move actions are labelled in English`
- `the add field and the move actions are labelled in German` (`@Config(qualifiers = "de")`)

ProjectsScreenTest
- `a task added on the detail is confirmed in a snackbar, then dismissed`
- `a moved task says where it went`
- `an order that could not be saved offline says so`
- `an order refused because the project changed elsewhere says it was reloaded`
- `an added task is confirmed in German`
- `a moved task says where it went in German`

## Requirement coverage

The test-writer could not run Gradle (sub-agents in this environment cannot), so no new test has been run by the test-writer. The parent runs `cd android && ./gradlew testDebugUnitTest` and records the results. The parent's earlier run of the parent-written tests passed: ProjectWorkRepositoryTest 29, ProjectsViewModelTest 73, ProjectTaskListTest 10, ProjectsScreenTest 57.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Add task field creates a Next task in the project, quick entry stays open, optional make it the next step | Repository: adding a task creates a next action..., adding a task as next step..., a task added to a stalled project becomes its next step, a task created whose next step could not be set..., adding a task offline..., a task created just before the server became unreachable... ViewModel: adding a task trims it..., a task added whose next step failed..., the field of another project..., adding a task runs once at a time..., adding a task without an open project..., two tasks added with the same title... Compose: the add field adds a task with the button and with the keyboard..., the field clears once the task exists..., a project without a next step does not offer the choice..., adding is off while read-only..., a blank title adds nothing, the first task of a project without tasks..., the typed text belongs to the open project. Screen: a task added on the detail is confirmed... | `cd android && ./gradlew testDebugUnitTest` (run by the parent) | Parent tests: Pass (parent run). Test-writer tests: Not run: Gradle cannot run in a sub-agent, the parent runs it |
| 2. Long-press drag reorder with Move up/down accessibility actions; order saved like on web | Repository: reordering sends the task ids..., a saved order reloads only the projects... ViewModel: a dragged order shows at once..., a refused order goes back..., an unchanged order..., moving a task up or down..., an order being saved for one project..., a load that answers while the order is being saved..., a failed load after saving..., two overlapping-save tests, a move that cannot be saved offline... Compose: the move actions are offered..., a long press and drag moves a task..., a drag over several frames..., a long press without moving..., while read-only a long press..., a single task can neither be dragged nor moved, a task dragged back to its place..., a dropped order stays in the list..., two interrupted-drag tests. Screen: a moved task says where it went, an order that could not be saved offline..., an order refused because... | `cd android && ./gradlew testDebugUnitTest` (run by the parent) | Parent tests: Pass (parent run). Test-writer tests: Not run (as above). The two interrupted-drag tests are expected to fail until TW-1 is fixed. TalkBack reaching the actions (CR-2) cannot be shown by a Robolectric semantics test: Not testable here |
| 3. A task row opens task detail; other lists keep their list label | Compose: a task row opens task detail, and tasks of other lists keep their list label. Screen: existing list-label test (the parent added a scroll so it still finds Scheduled) | `cd android && ./gradlew testDebugUnitTest` (run by the parent) | Pass (parent run) |
| 4. New strings in English and German | Compose: the add field and the move actions are labelled in English / in German. Screen: a task added on the detail is confirmed..., a moved task says where it went, an added task is confirmed in German, a moved task says where it went in German | `cd android && ./gradlew testDebugUnitTest` (run by the parent) | Not run: Gradle cannot run in a sub-agent, the parent runs it. All 8 new keys are present in `res/values/` and `res/values-de/` (checked by reading the diff) |
| 5. Unit/Compose tests cover the new behavior; `ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass | all of the above | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`, `sh scripts/check.sh` | Not run by the test-writer (Gradle cannot run in a sub-agent). The parent must run both. Because of TW-1, two tests are expected to fail until it is fixed |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.projects.*'` (for the parent to run)

Result:

Not run by the test-writer: sub-agents in this environment cannot run Gradle. The parent must run it. The two interrupted-drag tests are expected to fail (TW-1). Every other added test is expected to pass, unless the test-writer made a mistake: the tests were written without compiling them.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run by the test-writer (Gradle cannot run in a sub-agent). The parent runs it.

## .taskcheck

Result:

Not run by the test-writer (`sh scripts/check.sh` runs Gradle). The parent runs it.

## Bugs found

### A drag that is cancelled, not dropped, is still saved, or stays on screen as if it were saved

Finding ID:

`TW-1` (same defect as the code reviewer's CR-1)

Test:

`ProjectTaskListTest.a drag interrupted by a change of the tasks is not saved` and `ProjectTaskListTest.a drag interrupted by going read-only is not saved and the list keeps the state's order`

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectTaskList.kt` (`longPressDrag` calls `onEnd()` in `finally`; the `onEnd` lambda in `ProjectTaskList` sets `dropped` and calls `reorder`; `pointerInput` is keyed on `ids`; the `.then(if (canReorder) ...)` modifier)

Requirement:

Criterion 2: "the order is saved like on web". Web saves only when the user drops the row.

Expected behavior:

If the gesture is cancelled while the finger is still down, nothing is saved and the list shows the state's order. Two cases cancel it: the project's open task ids change, which restarts `pointerInput`, and the screen becomes read-only, which removes the drag modifier.

Actual behavior (from reading the code; not yet confirmed by a run):

The `finally` block runs `onEnd()` when the coroutine is cancelled. So `reorder` is called with the half-finished order, built from the old ids. In the read-only case `dropped` keeps the unsaved order, and `ids` has not changed, so the list keeps showing that order.

Severity:

Medium

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: unavailable. The test-writer did not measure coverage, because Gradle (and with it the Android coverage task) cannot run in a sub-agent in this environment. The parent runs `cd android && ./gradlew testDebugUnitTest`, and the coverage task from docs/ARCHITECTURE.md "Coverage" if it wants numbers.

Metric: whole-file coverage of task-changed production files (not measured).
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-project-tasks; base 05855c3ae0705c9237c32572055329581de3bf1b; HEAD fc78e00c9; commits ad642d981 and fc78e00c9 as supplied by the parent
- Source/test snapshot identifier (including uncommitted inputs): HEAD fc78e00c9 plus the uncommitted edits to the four test files listed above; no coverage run, so no snapshot was measured
- Exact included files: ProjectWorkRepository.kt, ProjectsViewModel.kt, ProjectTaskList.kt, ProjectsScreen.kt, ProjectForm.kt (all under `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/`). The two string resource files are excluded because they are not executable. No renamed or deleted files
- Provider/version, command, working directory, and test selection: none run
- Run start/completion time and exit status: none
- Generated report paths and report hashes/run identifier: none
- Evidence outputs are fresh and inputs stayed unchanged: not applicable, no run
- Final run after latest test additions and production fixes: no. Gradle cannot run in a sub-agent. After the TW-1 fix the parent must rerun the tests

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `ProjectWorkRepository.kt` | N/A (not measured) | N/A | N/A | N/A |
| `ProjectsViewModel.kt` | N/A (not measured) | N/A | N/A | N/A |
| `ProjectTaskList.kt` | N/A (not measured) | N/A | N/A | N/A |
| `ProjectsScreen.kt` | N/A (not measured) | N/A | N/A | N/A |
| `ProjectForm.kt` | N/A (not measured) | N/A | N/A | N/A |

Without numbers, here are the gaps found by reading the code:

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ProjectTaskList.kt` `longPressDrag` cancellation path (`finally` without a lift) | Criterion 2: a cancelled drag must not save | Added the two interrupted-drag tests (TW-1) |
| `ProjectTaskList.kt` `DragState.by` early returns (stale layout, target not visible, `requestScrollToItem` for the first visible item) | Drag robustness | Partly covered by the multi-frame drag tests (several rows, dragged back). Auto-scroll at the list edges is out of scope by assumption, so no test was added |
| `ProjectTaskList.kt` `listLabel` INBOX branch | Criterion 3 | Covered by the existing ProjectsScreenTest list-label test (Inbox, Someday, Scheduled) |
| `ProjectsViewModel.kt` `saveOrder` overlapping saves | CR-3: the server may apply the requests in another order | Local state is covered by the two overlapping-save tests. Serializing on the server is a design question, so no test was added |
| `ProjectForm.kt` `ProjectMessageEffect` FAILED outcome for MOVED and TASK_ADDED | Message shown | Uses the shared `tasks_message_failed` string, which existing tests already cover. Not added again |

## Unable to test

- TalkBack offering "Move up"/"Move down" on the row it focuses (CR-2). A Robolectric semantics test calls the custom action on any node, so it cannot show what a screen reader can reach. This needs a check on a device or emulator. Follow-up: the parent or fixer decides on CR-2.
- Running the tests and measuring coverage. Sub-agents cannot run Gradle here. Follow-up: the parent runs `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh`, and should report compile or ktlint problems in the added tests back to the test-writer.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion. TW-1 (same as CR-1): a cancelled drag is still saved. The two interrupted-drag tests in ProjectTaskListTest should fail until it is fixed. The test-writer has not run any of the 27 added tests (Gradle cannot run in a sub-agent), so the parent's Gradle run is the only execution evidence.
