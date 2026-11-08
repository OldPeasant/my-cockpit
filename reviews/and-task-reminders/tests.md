# Test Report

Date: 2026-11-09

Task: and-task-reminders - Reminders on tasks (Android)

Scope: `git diff 362ff9a5303b6e31a9dc200c705e7e8b4b0d437c..2acf601a765284925585d4e3846c55366da77748 -- android` (base and head given by the parent worker). Measured at HEAD da486d2229bd383047bfd1e44b53f5decabae86d, which differs from 2acf601a7 only in `reviews/and-task-reminders/review.md` and `tasks/and-task-reminders.md` (no production change). Branch main.

Requirements:
- `tasks/and-task-reminders.md` acceptance criteria
- AGENTS.md project notes, docs/ARCHITECTURE.md "Coverage"
- parent worker instructions

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskRemindersTest.kt` (new: `TaskReminderPresetsTest`, `TaskRemindersViewModelTest`, `TaskReminderRepositoryTest`)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskRemindersContentTest.kt` (new, Robolectric Compose)
- `android/app/src/test/java/ch/sonensei/cockpit/reminders/OpenItemRequestTaskTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/reminders/ReminderUiTest.kt` (updated, see below)

`ReminderUiTest` update: two existing tests asserted that a tapped TASK notification leaves the user on the Next screen. Acceptance criterion 2 ("opens the task") deliberately changes that: the shell now pushes the task detail on top of Next, so both tests failed at HEAD. They were updated, not weakened: the generic "opens the item's screen" and "same screen stacks once, Back returns" checks now use a PROJECT reminder (behavior unchanged by the task), and a new test `a tapped task notification opens the task's detail on top of Next` asserts the detail route with the task id and that Back returns to Next.

## Tests added or updated

Count: 50 (47 new tests, 1 new test in ReminderUiTest, 2 existing ReminderUiTest tests updated)

## Requirement coverage

Command A: `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.TaskReminder*' --tests 'ch.sonensei.cockpit.gtd.task.TaskDetailScreenTest' --tests 'ch.sonensei.cockpit.gtd.task.TaskDetailContentTest' --tests 'ch.sonensei.cockpit.reminders.*' --tests 'ch.sonensei.cockpit.ui.*' createDebugUnitTestCoverageReport ktlintCheck`

Command B: `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.*' --tests 'ch.sonensei.cockpit.reminders.*' --tests 'ch.sonensei.cockpit.ui.*' createDebugUnitTestCoverageReport ktlintCheck`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 add reminders (presets relative to due/scheduled date, exact date/time) | `TaskReminderPresetsTest` (7 tests: due time, date-only at 09:00, no hour-before without time, scheduled, DST day-before, no dates, only future presets incl. exactly-now boundary, `reminderAnchors` incl. unreadable due time); `TaskRemindersViewModelTest` add tests (busy, sorted insert, past/now refused, double add ignored, rejected add); `TaskRemindersContentTest` preset click, custom date then time, cancel, no-dates, section with view model | A | Pass |
| AC1 remove reminders | `TaskRemindersViewModelTest` remove success/failure/while busy; `TaskRemindersContentTest` remove click, disabled while busy and read-only; `TaskReminderRepositoryTest` DELETE path, 404 counts as removed, failure does not sync | A | Pass |
| AC1 existing reminders listed | `TaskReminderRepositoryTest` list GET with itemType=TASK and itemId, sorted, firedAt read; `TaskRemindersContentTest` listing with "(sent)" marker, empty state, error/offline/invalid/failed/past-time messages, retry; `TaskRemindersViewModelTest` load, retry, open another task | A | Pass |
| AC1 local alarms through the existing reminder sync | `TaskReminderRepositoryTest`: `ReminderSync.sync()` called exactly once after a successful POST and DELETE (also 404), never after list or failed/offline calls (ReminderSync mocked with MockK; the sync itself is covered by existing `ReminderSyncTest`) | A | Pass |
| AC1 panel wired into task detail with editability | `TaskRemindersContentTest` `the task detail shows the reminders panel with the task and whether it can be changed` | A | Pass |
| AC1 route integration (`TaskDetailRoute` hosts `TaskRemindersSection`) | existing `TaskDetailConvertRouteTest` | B | Fail: TW-1 |
| AC2 reminder notification opens the task | `OpenItemRequestTaskTest` (taskId for TASK, null for bad id, other types, lowercase type); `ReminderUiTest` `a tapped task notification opens the task's detail on top of Next` | A | Pass (unit level; firing a real notification on a device is Not run: no device/emulator in this environment) |
| AC3 strings en + de | Indirect: Robolectric tests assert the English texts. German values not asserted. | A | Pass (English); German Not run: no new test, existing string tests not re-run |
| AC4 unit tests + `sh scripts/check.sh` | all of the above | B | Fail: TW-1 breaks the existing `TaskDetailConvertRouteTest`; `scripts/check.sh` not run (it will fail on the same test) |

## Focused test execution

Command:

Command A (above)

Result:

Pass: 245 tests, 0 failures; ktlintCheck pass.

## Full test execution

Command:

Command B (above, whole `gtd.task`, `reminders` and `ui` packages)

Result:

Fail: 526 tests, 3 failures, all in the existing `TaskDetailConvertRouteTest` (TW-1). The full Android suite and `sh scripts/check.sh` were not run within the session budget.

## .taskcheck

Result:

Not run (expected to fail because of TW-1).

## Bugs found

### TaskDetailRoute crashes without Hilt: TaskRemindersSection always calls hiltViewModel()

Finding ID:

`TW-1`

Test:

`ch.sonensei.cockpit.gtd.task.TaskDetailConvertRouteTest` (3 tests, e.g. `to reference from the menu converts once confirmed, and Open closes the task and opens the note`)

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskRemindersSection.kt:66` (default `viewModel = hiltViewModel()`), called from `TaskDetailScreen.kt:157-159` (`remindersPanel` in `TaskDetailRoute`)

Requirement:

AC4 "Unit tests cover the new behavior; `sh scripts/check.sh` passes" (existing tests must keep passing).

Expected behavior:

`TaskDetailRoute` hosted with an explicit `TaskDetailViewModel` in a non-Hilt activity (as the existing route tests do) renders; the links panel already handles this case (`LinkedItemsSection` returns early when `LocalActivity.current !is GeneratedComponentManagerHolder` and no view model is given, `core/links/LinkedItemsPanel.kt:136`).

Actual behavior:

`IllegalStateException: Given component holder class androidx.activity.ComponentActivity does not implement interface dagger.hilt.internal.GeneratedComponent` from `HiltViewModelFactory` via `TaskRemindersSection` during composition.

Severity:

High (breaks existing tests and therefore `.taskcheck`; in the real Hilt app it works).

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: verified for the files below with Command A (exit 0); whole-file metric. Command B failed (TW-1) and its coverage report was not used.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-task-reminders; base 362ff9a5303b6e31a9dc200c705e7e8b4b0d437c; task head 2acf601a765284925585d4e3846c55366da77748 (from parent); measured HEAD da486d2229bd383047bfd1e44b53f5decabae86d (only review/task files differ); scope from `git diff --stat 362ff9a53..2acf601a7 -- android`.
- Source/test snapshot identifier (including uncommitted inputs): production = HEAD; uncommitted test files sha256: TaskRemindersTest.kt 5ce19396a5d4..., TaskRemindersContentTest.kt 2121f2e97f84..., OpenItemRequestTaskTest.kt e45c4cd933cb..., ReminderUiTest.kt 19d397f41069...
- Exact included files: `gtd/task/TaskReminders.kt`, `gtd/task/TaskRemindersSection.kt`, `gtd/task/TaskDetailScreen.kt`, `reminders/ReminderOpenRequests.kt`, `ui/CockpitShell.kt` (under `android/app/src/main/java/ch/sonensei/cockpit/`). Excluded: `res/values{,-de}/strings_task_detail.xml` (resources, not instrumented). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (`createDebugUnitTestCoverageReport`), Command A, working directory `android/`.
- Run start/completion time and exit status: started 2026-11-09T00:10:43+01:00, report written 2026-11-09T00:11:20+01:00, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 prefix 3e9b6580c18385cb.
- Evidence outputs are fresh and inputs stayed unchanged: report timestamp after run start; no test or production file edited during the run.
- Final run after latest test additions and production fixes: yes for the test additions; no production fix has been made yet (TW-1 open), so coverage must be re-measured after the fix.

Values below use covered/total (percentage); JaCoCo instructions stand in for statements.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `gtd/task/TaskReminders.kt` | 1151/1175 (98.0%) | 76/82 (92.7%) | 52/54 (96.3%) | 127/130 (97.7%) |
| `gtd/task/TaskRemindersSection.kt` | 1852/1905 (97.2%) | 48/62 (77.4%) | 35/38 (92.1%) | 126/128 (98.4%) |
| `gtd/task/TaskDetailScreen.kt` | 7896/11426 (69.1%) | 222/416 (53.4%) | 154/284 (54.2%) | 624/838 (74.5%) |
| `reminders/ReminderOpenRequests.kt` | 128/134 (95.5%) | 23/23 (100%) | 7/9 (77.8%) | 25/25 (100%) |
| `ui/CockpitShell.kt` | 2028/2171 (93.4%) | 108/133 (81.2%) | 46/54 (85.2%) | 173/178 (97.2%) |
| Aggregate (5 files, summed counters) | 13055/16811 (77.7%) | 477/716 (66.6%) | 294/439 (67.0%) | 1075/1299 (82.8%) |

No scoped file is missing from the report. TaskDetailScreen.kt is mostly pre-existing code; its route lines are low because `TaskDetailConvertRouteTest` (which renders `TaskDetailRoute`) was left out of Command A since it fails (TW-1).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `TaskDetailScreen.kt:133-162` (route, incl. `remindersPanel` at 157-159) | Route hosts the reminders section | Covered by existing `TaskDetailConvertRouteTest`, which fails (TW-1); re-measure after the fix |
| `TaskReminders.kt:110-111` | CancellationException rethrown | Not tested: defensive, cooperative-cancellation plumbing |
| `TaskReminders.kt:128` | Hilt `@Binds` module | Generated wiring, not meaningfully testable in a unit test |
| `TaskRemindersSection.kt:66` | `hiltViewModel()` default | The TW-1 code path; the test passes a view model explicitly |
| `TaskRemindersSection.kt:171` | AT_SCHEDULED label | Not material; the preset itself is covered in `TaskReminderPresetsTest` |
| `CockpitShell.kt:127-129` | Not in task diff (pre-existing) | Out of scope |

## Unable to test

- A real notification firing and opening the task on a device (AC2): no device or emulator here; covered at unit level (OpenItemRequest.taskId and shell navigation through ReminderOpenRequests). Needs a manual check or a skip with reason.
- German strings (AC3): not asserted by a test; existing string-parity tests (if any, e.g. `TaskDetailStringsTest`) were part of Command B's `gtd.task` selection and passed there.
- Responses for a previously opened task arriving after `open(otherTask)`: the view model does not drop stale list responses, but the section's view model is scoped to one task's nav entry, so it is not reachable in the app; not tested, no finding.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion (TW-1: `TaskRemindersSection` needs the same no-Hilt guard as `LinkedItemsSection`, otherwise the existing `TaskDetailConvertRouteTest` and `.taskcheck` fail).
