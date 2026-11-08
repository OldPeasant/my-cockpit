# Test Report

Date: 2026-11-08

Task: and-project-tasks - Android: add tasks directly to a project and reorder them

Scope: second round, on the final code after the fixer. The parent ran the tests and coverage on e14b996c31857a89305922ae7702f3f578137dc7. The branch was then rebased onto main 5e8c4aee28679493fe015f6727c7a98c97ad52a4, which adds only task-board commits. The current HEAD is 458703132cd2b8e094af8b9612acb21909663a26. `git diff e14b996c3 HEAD -- android` is empty, so the measured Android code and tests are the same as at HEAD. The original base was dbf4324fbbac3cdfc1292715157fa189f61bc96a. The task diff measured here is `git diff 5e8c4aee2..HEAD -- android`, and these are the task commits on top of the current base:
- ff5e896d7, f871e8fa8: implementation;
- 19d81e4dc: test-writer round 1;
- c2d669721: fixer for CR-1, CR-2 and CR-3;
- c6ed804cc: round-1 reports;
- 458703132: this round's tests and review.

Task ownership comes from the commit list the parent supplied and from the task's `touches`. When this report was updated, the working tree was clean apart from this report.

Requirements:
- tasks/and-project-tasks.md (acceptance criteria 1-5 and the recorded assumptions)
- AGENTS.md project notes, docs/ARCHITECTURE.md ("Coverage", Android test commands)
- reviews/and-project-tasks/review.md (CR-1, CR-2, CR-3) and reviews/and-project-tasks/fixes.md (what the fixer changed)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectTaskListTest.kt` (this round, committed in 458703132)

The task as a whole, including round 1 and the fixer, changed `ProjectTaskListTest.kt`, `ProjectsViewModelTest.kt`, `ProjectWorkRepositoryTest.kt` and `ProjectsScreenTest.kt`. All are in `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/`.

## Tests added or updated

Count: 2 added this round, none changed or removed.

This round focused on the fixes.

CR-1 (a drag is saved only on drop):
- The drop path is covered by the existing drag tests.
- Two ways of cancelling a drag were already covered by the round-1 regression tests: the tasks change, or the screen goes read-only.
- Two things had no test. The system cancelling the gesture arrives as an up that is already consumed, and the new `lifted = !change.isConsumed` check handles it. No test showed that `onCancel` leaves the drag state clean for the next gesture. This round's two tests are about these.

CR-2 (move actions on the focused row):
- The fixer moved the `actions`/`action` helpers to `TaskListTags.row` and added assertions: the row has OnClick, and the item no longer has CustomActions.
- No gap is left that a Robolectric test can show.

CR-3 (order saves sent one at a time):
- The fixer added `a newer save sends its order only after the older one finished, so the server applies the last order last`.
- Together with the two overlapping-save tests, it covers the saves going out in order, an older save failing, and the order shown while saving.

Added this round (ProjectTaskListTest, Compose on Robolectric):
- `a drag the system cancels is not saved, and the next drag starts from the state's order`. It picks a row up, drags it one row down, then sends `cancel()` (ACTION_CANCEL). Nothing is saved and the list shows the state's order. A new drag afterwards saves exactly `[Compare hotels, Book the flights, Visa from the embassy]`.
- `after a drag interrupted by a change of the tasks, the next drag saves an order of the new tasks`. The project's tasks change to 2 tasks during the drag. The next drag saves `[Compare hotels, Book the flights]`, with nothing left from the interrupted drag.

Test counts per file (`@Test` annotations): ProjectTaskListTest 22, ProjectsViewModelTest 82, ProjectWorkRepositoryTest 32, ProjectsScreenTest 63.

## Requirement coverage

The test-writer cannot run Gradle in this environment. All execution evidence below comes from the parent's runs on e14b996c3, whose Android code is the same as at HEAD 458703132:
- Run A: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests '*gtd.projects*'`. ktlint passed, and 228 tests in gtd.projects ran with 0 failures, including the 2 tests added this round.
- Run B: `cd android && ./gradlew ktlintCheck lintDebug createDebugUnitTestCoverageReport`. Exit 0. Across all test-results XML, 3314 tests ran with 0 failures and 0 errors. ktlint and lintDebug passed.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. The "Add task" field creates a Next task in the project, the field stays open, and the task can optionally become the next step | Repository: adding a task creates a next action, as next step, stalled project, next step not set, offline, server unreachable after create. ViewModel: trims, next step failed, another project's field, once at a time, without an open project, same title twice. Compose: button and keyboard, field clears, no choice without next step, read-only or busy, blank title, first task of an empty project, text belongs to the open project. Screen: added task confirmed (en, de) | Run A and run B | Pass |
| 2. Long-press drag reorders the tasks, with Move up/Move down accessibility actions; the order is saved like on web | Repository: reordering sends the task ids, a saved order reloads. ViewModel: dragged order shows at once, refused order goes back, unchanged order, move up/down, other project, load during save, failed load, newer save sent only after older (CR-3), two overlapping-save tests, offline move. Compose: actions offered on the focused row and not read-only (CR-2), long press and drag, several frames, long press without moving, read-only, single task, dragged back, dropped order stays, interrupted by tasks change, interrupted by read-only (CR-1). Screen: moved message (en, de), offline, conflict | Run A and run B | Pass |
| 2 (CR-1: the system cancels a drag, and the drag state after a cancel) | `a drag the system cancels is not saved, and the next drag starts from the state's order`, `after a drag interrupted by a change of the tasks, the next drag saves an order of the new tasks` | Run A (228 tests, these 2 included) | Pass |
| 2 (TalkBack really lists the actions) | none | none | Not testable here: Robolectric semantics do not show what a screen reader focuses. Needs a device |
| 3. A task row opens task detail; other lists keep their list label | Compose: a task row opens task detail, and tasks of other lists keep their list label. Screen: list-label test | Run A and run B | Pass |
| 4. New strings in English and German | Compose: labelled in English / in German. Screen: added and moved messages in English and German | Run A and run B | Pass |
| 5. Unit/Compose tests cover the new behavior; `ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass | all of the above | Run B covers ktlintCheck, lintDebug and all unit tests. `sh scripts/check.sh` is run by the parent | ktlintCheck, testDebugUnitTest (3314 tests, 0 failures/errors) and lintDebug: Pass. `sh scripts/check.sh`: run by the parent, result not recorded here |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests '*gtd.projects*'` (run by the parent on e14b996c3)

Result:

Pass. ktlint passed, 228 tests, 0 failures. This includes the fixer's tests, the round-1 regression tests for CR-1 (they failed before the fix, as reported in round 1) and the 2 tests added this round.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck lintDebug createDebugUnitTestCoverageReport` (run by the parent on e14b996c3; the coverage task runs the whole debug unit test suite)

Result:

Pass. Exit 0. Across all test-results XML: 3314 tests, 0 failures, 0 errors. ktlint and lintDebug passed.

## .taskcheck

Result:

Run by the parent (`sh scripts/check.sh`). It was still to be run when this report was written, so it is not recorded here.

## Bugs found

None in this round. TW-1 from round 1 (same as CR-1) was fixed in c2d669721. Its two regression tests and the 2 new cancel tests pass (run A).

## Coverage

Status: verified (whole-file and changed-line). The parent ran coverage on e14b996c3. The test-writer read the resulting JaCoCo report.

Metric: whole-file coverage of task-changed production files. Whole files are measured, including old code in them and hits from tests that existed before the task.

Changed-line coverage: 298/299 lines (99.7%), 174/190 branches (91.6%). The test-writer computed it by reading `git diff -U0 5e8c4aee2 HEAD -- android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/` and keeping the added or modified lines that JaCoCo lists as executable in report.xml. A line counts as covered when its covered-instruction count (`ci`) is above 0. The branches are the JaCoCo branch counters on those lines. Removed lines and lines that are not executable are not counted. Per file:
- ProjectForm.kt: 4/4 lines, no branches.
- ProjectTaskList.kt: 199/200 lines, 91/104 branches. The whole file is new.
- ProjectWorkRepository.kt: 15/15 lines, 6/6 branches.
- ProjectsScreen.kt: 8/8 lines, 4/4 branches.
- ProjectsViewModel.kt: 72/72 lines, 73/76 branches.

Evidence:
- Task id, base, measured HEAD and task-owned commits:
  - Task: and-project-tasks. Original base dbf4324fbbac3cdfc1292715157fa189f61bc96a; current base after the rebase 5e8c4aee28679493fe015f6727c7a98c97ad52a4.
  - Measured revision: e14b996c31857a89305922ae7702f3f578137dc7. Current HEAD: 458703132cd2b8e094af8b9612acb21909663a26. `git diff e14b996c3 HEAD -- android` is empty.
  - Task commits: listed under Scope, as supplied by the parent.
- Source/test snapshot: the committed tree at e14b996c3, which has the same `android/` tree as 458703132. The parent reported no uncommitted inputs. `ProjectTaskListTest.kt` sha256 is 39d77f806b8cb0a66a016847903237695005b83b03f5f64e5df00bae3164a04d, the same as the file this round wrote.
- Included files, all under `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/`: `ProjectWorkRepository.kt`, `ProjectsViewModel.kt`, `ProjectTaskList.kt`, `ProjectsScreen.kt`, `ProjectForm.kt`. All five have data in the report. The two `strings_projects_lists.xml` files are excluded because they are not executable. No file was renamed or deleted.
- Provider, command, working directory and test selection: JaCoCo through AGP `enableUnitTestCoverage`. The parent ran `./gradlew ktlintCheck lintDebug createDebugUnitTestCoverageReport` in `android/`, on the whole debug unit test suite (3314 tests).
- Run time and exit status: the run finished by 2026-11-08 13:18:19 +0100 (report.xml modification time). The source revision e14b996c3 was committed at 13:14:57 +0100. Exit 0.
- Report path and hash: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 9a3543b345b383407ca5176f5535309ed76694412302ce7d276020402ec7d079.
- Freshness:
  - The report was written after e14b996c3 was committed.
  - It replaced the stale 09:59 report that round 1 left unused.
  - Its counters for the five files match the parent's numbers.
- Final run after the latest test additions and production fixes: yes. The run includes the fixer's changes and the 2 tests added this round.

Values below use covered/total (percentage). "Statements" are JaCoCo instructions and "Functions" are JaCoCo methods.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `ProjectsViewModel.kt` | 3619/3656 (99.0%) | 307/332 (92.5%) | 147/150 (98.0%) | 407/407 (100.0%) |
| `ProjectForm.kt` | 3761/3824 (98.4%) | 108/127 (85.0%) | 78/84 (92.9%) | 282/285 (98.9%) |
| `ProjectTaskList.kt` | 2096/2128 (98.5%) | 91/104 (87.5%) | 57/57 (100.0%) | 199/200 (99.5%) |
| `ProjectWorkRepository.kt` | 901/901 (100.0%) | 24/24 (100.0%) | 36/36 (100.0%) | 93/93 (100.0%) |
| `ProjectsScreen.kt` | 3899/3990 (97.7%) | 153/166 (92.2%) | 75/101 (74.3%) | 332/333 (99.7%) |
| Aggregate of the 5 files (summed counters) | 14276/14499 (98.5%) | 683/753 (90.7%) | 393/428 (91.8%) | 1313/1318 (99.6%) |

The aggregate covers all 5 scoped files. No scoped file is unmeasured. The 2 string resource files are excluded because they are not executable.

Uncovered code that matters, from the report's line data:

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ProjectTaskList.kt:317` (the `?: break` side: the tracked pointer is missing from an event) | Defensive. It leads to `onCancel`, the same path the cancel tests cover | Not tested. Robolectric's single-pointer touch injection cannot leave out a pointer that is down |
| `ProjectTaskList.kt:189` (`drag.stop() ?: return@longPressDrag` in `onDrop`) | Defensive. A drop without a started drag cannot happen, because `onStart` always runs first | Justified gap |
| `ProjectTaskList.kt:248, 267, 275, 276, 278, 285, 293` (`DragState.by` early returns: stale layout, target not visible, first-visible-item scroll) | Drag robustness. Auto-scroll at the list edges is out of scope by assumption | The multi-frame drag tests cover the main paths. The rest depends on frame timing and viewport size, so no test was added |
| `ProjectTaskList.kt:384, 390` (`listLabel`: the missing branch and the uncovered line 390 are the `else` branch Kotlin generates for an exhaustive enum `when`) | None: no input can reach it | Not testable |
| `ProjectTaskList.kt:106` (`makeNext && offerNext`, with makeNext set while the project has no next step) | The checkbox is hidden for a stalled project, so the choice is ignored | Low risk, no test added |
| `ProjectsViewModel.kt:594` (`current == null` while an order save ends) | The order shown while saving was already cleared when the save answers | Low risk: the result is ignored, as in the `current !== pending` case, which is tested |
| `ProjectsScreen.kt:101-127` (default lambdas of `ProjectsCallbacks`) | None: placeholders that tests replace | Not material |
| `ProjectsScreen.kt:82-88` (`ProjectsScreen` outside a Hilt activity) and the other uncovered lines in `ProjectForm.kt`/`ProjectsScreen.kt` outside the task's changed lines | Code that was already there before this task | Out of task scope. The changed lines in these files are fully covered (see changed-line coverage) |

## Unable to test

- Whether TalkBack offers "Move up"/"Move down" on the row it focuses (CR-2). The tests check that the actions sit on the merged node with OnClick, and no longer on the item. Whether a screen reader really lists them needs a device or an emulator. Follow-up: the parent records this as a skip or an assumption.
- `sh scripts/check.sh` is run by the parent and is not recorded in this report.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.
- The fixes for CR-1, CR-2 and CR-3 are covered. The parent's runs on e14b996c3 passed: 228 gtd.projects tests and 3314 tests in total, with no failures, plus ktlint and lintDebug.
- Changed-line coverage is 298/299 lines and 174/190 branches.
- The remaining gaps are defensive or out of scope, and are listed above.
- `sh scripts/check.sh` is still the parent's step.
