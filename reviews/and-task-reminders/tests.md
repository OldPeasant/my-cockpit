# Test Report

Date: 2026-11-09

Task: and-task-reminders - Reminders on tasks (Android)

Scope: `git diff 362ff9a5303b6e31a9dc200c705e7e8b4b0d437c..1a1937346a88c80ef27b87f9345726fd84f6d36b -- android` (base and final head from the parent worker). This is a re-run on the final code, after the fixer fixed TW-1 (`reviews/and-task-reminders/fixes.md`). The measurement started at HEAD 1a1937346. While it ran, HEAD moved to e829d745e425c1636dfc60c8f9ad0e59ab7d15d0, which changes only `reviews/and-task-reminders/review.md`, so no production or test code changed. Branch main.

Tested revision (final delta): 4be818e65e48e61f0a11a60151fa4619b7478c23. This report first covered abb292178. The only code change between abb292178 and 4be818e65 (`git diff abb292178..4be818e65 -- android`) is a lint fix in one test file: `TaskRemindersContentTest.kt` now builds `TaskRemindersViewModel(source)` before `composeRule.setContent` rather than inside the composable (Android lint ViewModelConstructorInComposable). The test checks the same things as before. No production code changed, so the coverage below still applies. The only other file changed in that range is `reviews/and-task-reminders/review.md`.

Requirements:
- `tasks/and-task-reminders.md` acceptance criteria
- AGENTS.md project notes, docs/ARCHITECTURE.md "Coverage"
- parent worker instructions (add a test for the TW-1 guard, run the given Gradle command)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskRemindersTest.kt` (added in commit 2232208de: `TaskReminderPresetsTest`, `TaskRemindersViewModelTest`, `TaskReminderRepositoryTest`)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskRemindersContentTest.kt` (added in 2232208de. This re-run adds 1 test, uncommitted.)
- `android/app/src/test/java/ch/sonensei/cockpit/reminders/OpenItemRequestTaskTest.kt` (added in 2232208de)
- `android/app/src/test/java/ch/sonensei/cockpit/reminders/ReminderUiTest.kt` (updated in 2232208de)

About the `ReminderUiTest` update: two existing tests checked that tapping a TASK notification leaves the user on the Next screen. Acceptance criterion 2 ("opens the task") changes that on purpose. The tests were updated, not weakened. The generic "opens the item's screen" and "same screen stacks once" checks now use a PROJECT reminder, which the task does not change. A new test, `a tapped task notification opens the task's detail on top of Next`, checks the detail route with the task id and that Back returns to Next.

## Tests added or updated

Count: 51 (48 new tests, 1 of them added in this re-run; 1 new test in ReminderUiTest; 2 existing ReminderUiTest tests updated)

New in this re-run: `TaskRemindersContentTest` `without a view model outside a Hilt activity the section renders nothing`. It uses the compose rule's plain ComponentActivity, the same setup as the task detail route tests, and checks that no "Reminders" heading, section tag or custom-time chip appears (TW-1 guard).

## Requirement coverage

Command: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.*' --tests 'ch.sonensei.cockpit.reminders.*' --tests 'ch.sonensei.cockpit.ui.*' createDebugUnitTestCoverageReport` (called C below)

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 add reminders (presets relative to the due or scheduled date, exact date/time) | `TaskReminderPresetsTest`. `TaskRemindersViewModelTest` add tests: busy, sorted insert, past or current time refused, double add ignored, rejected add. `TaskRemindersContentTest`: preset click, custom date then time, cancel, no dates, section with a view model. | C | Pass |
| AC1 remove reminders | `TaskRemindersViewModelTest`: remove succeeds, remove fails, remove while busy. `TaskRemindersContentTest`: remove click, disabled while busy and when read-only. `TaskReminderRepositoryTest`: DELETE path, 404 counts as removed, a failure does not sync. | C | Pass |
| AC1 existing reminders listed | `TaskReminderRepositoryTest`: list GET with itemType and itemId, sorted, firedAt read. `TaskRemindersContentTest`: "(sent)" marker, empty state, error, offline, invalid, failed and past-time messages, retry. `TaskRemindersViewModelTest`: load, retry, open another task. | C | Pass |
| AC1 local alarms through the existing reminder sync | `TaskReminderRepositoryTest`: `ReminderSync.sync()` runs once after a successful POST or DELETE (404 included). It never runs after a list or a failed or offline call. | C | Pass |
| AC1 panel wired into task detail with editability | `TaskRemindersContentTest` `the task detail shows the reminders panel with the task and whether it can be changed` | C | Pass |
| AC1 route integration and TW-1 guard (`TaskDetailRoute` hosts `TaskRemindersSection`; renders without Hilt) | existing `TaskDetailConvertRouteTest` (3 tests); new `without a view model outside a Hilt activity the section renders nothing` | C | Pass |
| AC2 a reminder notification opens the task | `OpenItemRequestTaskTest`; `ReminderUiTest` `a tapped task notification opens the task's detail on top of Next` | C | Pass at unit level. Firing a real notification on a device: Not run, because this environment has no device or emulator. |
| AC3 strings en + de | No German assertion. Robolectric tests check the English texts. The existing string tests in `gtd.task` ran as part of C. | C | Pass for English. German not asserted. |
| AC4 unit tests + `sh scripts/check.sh` | all of the above | C | Pass for the unit tests and ktlint in scope. `scripts/check.sh` not run by the test-writer; the parent runs it. |

## Focused test execution

Command:

Command C (above). It is focused on the task's packages.

Result:

Pass: BUILD SUCCESSFUL in 42s; 45 suites, 527 tests, 0 failures, 0 errors, 0 skipped; ktlintCheck passed, including the edited test source set.

Final-delta re-run at 4be818e65, run in `android/`:

`./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.TaskReminders*'`

Result: Pass. BUILD SUCCESSFUL in 27s. `TaskRemindersContentTest` ran 14 tests and `TaskRemindersViewModelTest` ran 12 tests: 0 failures, 0 errors, 0 skipped (from the JUnit XML in `app/build/test-results/testDebugUnitTest/`). The filter matches only these two classes. The lint fix touched `TaskRemindersContentTest`, so the edited test is included.

## Full test execution

Command:

Full Android suite: not run, because of the session budget (about 8 min). Command C covers the whole `gtd.task`, `reminders` and `ui` packages.

Result:

Not run

## .taskcheck

Result:

Pass at 4be818e65. The parent worker ran `sh scripts/check.sh` (lint and unit tests of all parts, including Android lint, which reported the ViewModelConstructorInComposable error that is now fixed) and it exited 0. The test-writer did not run it.

## Bugs found

None open. TW-1 from the earlier report (TaskRemindersSection called `hiltViewModel()` without a guard, which broke `TaskDetailConvertRouteTest`) is fixed in 1a1937346. The 3 `TaskDetailConvertRouteTest` tests now pass, and the new guard test passes.

## Coverage

Status: verified (Command C, exit 0, whole-file metric, fresh report).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, base, measured HEAD and file-scope source: task and-task-reminders. Base 362ff9a5303b6e31a9dc200c705e7e8b4b0d437c. Final head 1a1937346a88c80ef27b87f9345726fd84f6d36b, from the parent. Scope from `git diff --stat 362ff9a53..1a1937346 -- android`. HEAD e829d745e differs only in review.md.
- Source/test snapshot: production code at 1a1937346. One uncommitted test file: `TaskRemindersContentTest.kt` sha256 531639cc518c288fa686b79f4720ba51e5a41860ace962b02a9a169be2e4a1a2.
- Included files, all under `android/app/src/main/java/ch/sonensei/cockpit/`: `gtd/task/TaskReminders.kt`, `gtd/task/TaskRemindersSection.kt`, `gtd/task/TaskDetailScreen.kt`, `reminders/ReminderOpenRequests.kt`, `ui/CockpitShell.kt`. Excluded: `res/values{,-de}/strings_task_detail.xml`, because resources are not instrumented. No renames or deletions.
- Provider, command, working directory and test selection: JaCoCo through AGP `enableUnitTestCoverage` (`createDebugUnitTestCoverageReport`). Command C, run in `android/`, with the 3 test package filters shown.
- Run time and exit status: report written at 2026-11-09T00:15:52 local time. The previous report was written at 00:11:20. Exit 0, BUILD SUCCESSFUL.
- Report path and hash: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 prefix 2fef176a0340ff2d.
- Freshness: the report was regenerated by this run (new mtime and hash). No production or test file was edited during the run.
- Final run after the latest test additions and production fixes: yes. It includes the TW-1 fix and the new guard test. Coverage was not measured again at 4be818e65. Since 1a1937346, only test and review files changed (the guard test, committed in abb292178, and the one-line lint fix in 4be818e65). Production code is the same, so these whole-file numbers still describe the final production code.

Values below use covered/total (percentage). JaCoCo instructions stand in for statements.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `gtd/task/TaskReminders.kt` | 1151/1175 (98.0%) | 76/82 (92.7%) | 52/54 (96.3%) | 127/130 (97.7%) |
| `gtd/task/TaskRemindersSection.kt` | 1967/2029 (96.9%) | 66/88 (75.0%) | 36/39 (92.3%) | 130/131 (99.2%) |
| `gtd/task/TaskDetailScreen.kt` | 9184/11430 (80.3%) | 292/416 (70.2%) | 193/284 (68.0%) | 720/838 (85.9%) |
| `reminders/ReminderOpenRequests.kt` | 128/134 (95.5%) | 23/23 (100.0%) | 7/9 (77.8%) | 25/25 (100.0%) |
| `ui/CockpitShell.kt` | 2053/2171 (94.6%) | 110/133 (82.7%) | 47/54 (87.0%) | 176/178 (98.9%) |
| Aggregate (5 files, summed counters) | 14483/16939 (85.5%) | 567/742 (76.4%) | 335/440 (76.1%) | 1178/1302 (90.5%) |

Every scoped file appears in the report. Most of TaskDetailScreen.kt is code that existed before the task. Its route, including `remindersPanel` at lines 157-159, is covered now because `TaskDetailConvertRouteTest` passes.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `TaskRemindersSection.kt:71-72` (partial branches) | The Hilt branch: no view model passed, inside a Hilt activity, falls back to `hiltViewModel()` | Not testable in a plain Robolectric unit test without a Hilt test activity. The other branches (guard returns early, explicit view model) are covered by `without a view model outside a Hilt activity the section renders nothing` and `the section loads the task's reminders through its view model and adds through it`. |
| `TaskRemindersSection.kt:185` | AT_SCHEDULED label | Not material. `TaskReminderPresetsTest` covers the preset itself. |
| `TaskRemindersSection.kt` other partial branches (82-117, 158, 181, 213) | Compose-generated branches and when-arms of the dialogs | Not material. The behavior is covered by the content tests. |
| `TaskReminders.kt:110-111` | A CancellationException is rethrown | Defensive cancellation code. Not tested. |
| `TaskReminders.kt:128` | Hilt `@Binds` module | Generated wiring. Not meaningful to test in a unit test. |
| `TaskDetailScreen.kt:137, 162-174` | Default `hiltViewModel()` and link navigation in the route | Code from before the task, outside this task's change. |
| `CockpitShell.kt:264, 339` | Not in the task diff (existed before) | Out of scope. |

## Unable to test

- A real notification firing on a device and opening the task (AC2). There is no device or emulator here. The unit tests cover it through `OpenItemRequest.taskId` and the shell navigation in `ReminderUiTest`. It needs a manual check or a skip with a reason.
- German strings (AC3). No test asserts them.
- The `hiltViewModel()` fallback inside a real Hilt activity. It needs a Hilt test activity and is covered only by the app at runtime.
- List responses for a previously opened task arriving after `open(otherTask)`. The view model does not drop stale list responses, but the section's view model belongs to one task's nav entry, so the app cannot reach this case. Not tested; no finding.

## Summary

Tests added and all validation passed. TW-1 is fixed, and a test now covers the guard. The focused suite (527 tests) and ktlint pass. Task-scoped coverage was measured fresh on the final production code. At the final revision 4be818e65, which only adds the lint fix in `TaskRemindersContentTest.kt`, the focused `TaskReminders*` tests pass (26 tests). `sh scripts/check.sh` (`.taskcheck`) passed there too, run by the parent (exit 0).
