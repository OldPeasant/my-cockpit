# Test Report

Date: 2026-11-08

Task: and-task-detail-dates - Android task detail: scheduled, tickler, recurrence, all lists

Scope: task base `4bf9400f551f553883daf617a00cd12a9ddfcdfc` (claim commit, supplied by the parent worker) to measured HEAD `8b638e6ee45a4c107ffd1cbc4644f4e71b6c95ab` on `main`, task commits `git log 4bf9400f..HEAD` (66f442cf, d420d596, af2605f1, e0eca3bf, 58535e82, 284d2621, merge 3681a1dd, 8b638e6e), plus the uncommitted test additions of this run (listed below). Production paths limited to the task's `touches` under `android/app/src/main/java/ch/sonensei/cockpit/gtd/task` and the two `strings_task_detail.xml` files.

Requirements:
- `tasks/and-task-detail-dates.md` acceptance criteria and the recorded assumptions (scheduled/list coupling, move-then-PATCH save, recurrence presets, checklist reorder)
- `AGENTS.md` project notes, `docs/ARCHITECTURE.md` "Coverage"
- Existing test conventions in `android/app/src/test/java/ch/sonensei/cockpit/gtd/task` (JUnit4, Robolectric Compose rule, `FakeTaskDetailSource`, coroutine test dispatchers, `CompletableDeferred` gates for ordering)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailViewModelRelistTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailListFieldsTest.kt`

## Tests added or updated

Count: 4 (added; no existing test changed)

- `TaskDetailViewModelRelistTest`: `the missing scheduled date problem clears when another list is chosen`
- `TaskDetailViewModelRelistTest`: `the missing person problem clears when the task is no longer moved to Waiting`
- `TaskDetailViewModelRelistTest`: `saving again while the move is still being sent sends nothing more` (double submit, ordering controlled with a `CompletableDeferred` gate on `relist`)
- `TaskDetailListFieldsTest`: `a waiting task without a waiting-since day can get one, the picker opening on today`

The task's commits already contained extensive tests (TaskRepeatTest 17, TaskDraftListTest 25, TaskDetailListFieldsTest 36, TaskDetailViewModelRelistTest 26, plus new cases in TaskDetailRepositoryTest). The additions above close the meaningful gaps found in the coverage report.

## Requirement coverage

Focused command for all rows: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.*' createDebugUnitTestCoverageReport` (217 tests, 0 failures).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Scheduled date and tickler date can be set and cleared (date picker, quick chips) | TaskDetailListFieldsTest: `a scheduled quick chip puts the task on Scheduled, removing the date takes it to Next`, `choosing another list drops the scheduled date`, `Scheduled without a date shows the problem at the date, a chip clears it and saves the move`, `the date picker opens on the field and confirming keeps the day`, `a tickler chip sets the day, the list stays, and clearing it removes it`, `a past tickler resent by a list change is shown as a problem at the tickler`; TaskDraftListTest: `picking a scheduled date puts the task on Scheduled`, `clearing the scheduled date takes a scheduled task to Next, other lists stay`, `a tickler sent with a move must be after today`, `only a changed tickler moves the task to its own list, resending it`, `a new scheduled date on a task staying on Scheduled is patched`; TaskDetailViewModelRelistTest: `a move alone sends no PATCH`, `clearing the tickler of a Someday task resends the move without it`, `choosing Scheduled without a date is refused at the scheduled field, a date clears it`, `the missing scheduled date problem clears when another list is chosen` (new); TaskDetailRepositoryTest: `relisting to Scheduled sends the list, project, scheduled date and tickler with If-Match`, `relisting to Inbox with a tickler keeps it, ...` | focused command above | Pass |
| 2. List can be changed to Inbox/Next/Waiting/Scheduled/Someday; waiting-since editable | TaskDetailListFieldsTest: `every list is offered with the task's own selected, choosing one saves a move`, `Waiting shows the person field and needs it, waiting-since only for a task already waiting`, `a waiting task shows its waiting-since day and can change the person`, `the waiting-since picker opens and can be cancelled`, `confirming the waiting-since picker keeps the day it opened on`, `a waiting task without a waiting-since day can get one, the picker opening on today` (new); TaskDraftListTest: `every list can be chosen`, `moving to Waiting needs the person, ...`, `the person and the day of a waiting task are patched, ...`; TaskDetailViewModelRelistTest: `a new list and title move the task first, then patch the moved version`, `moving to Waiting sends the person with the move ...`, `a waiting task's person and day are patched without a move`, move refusal/offline/conflict tests, `the missing person problem clears when the task is no longer moved to Waiting` (new), `saving again while the move is still being sent sends nothing more` (new) | focused command above | Pass |
| 3. Recurrence editor: presets, interval, FIXED vs AFTER_COMPLETION, custom RRULE kept as text | TaskRepeatTest (17 tests incl. `any other rule is kept as custom text exactly as stored` with `INTERVAL=1000`, `the interval must be a whole number from 1 to 999`, `a custom rule is required and needs a FREQ part`, round-trip); TaskDraftListTest repeat patch tests; TaskDetailListFieldsTest: `a preset with an interval and a mode is saved as rule and mode`, `the interval takes only up to three digits, zero is refused at the field`, `an existing custom rule is shown as text and can be edited`, `choosing Custom starts from the preset's rule, an empty custom rule is required`, `choosing no repeat removes the rule and hides the mode`, read-only repeat text tests; TaskDetailViewModelRelistTest: `a change without a move only patches`, `removing the repeat shows no repeat afterwards`, `an unchanged custom rule closes the form without a request` | focused command above | Pass |
| 4. Checklist reorder (long-press drag + accessibility actions) | TaskDetailListFieldsTest: `items offer Move up and Move down where they can go`, `the accessibility actions move an item by one`, `long-pressing an item and dragging it down two rows moves it there`, `dragging less than half a row does not move the item`, drag past top/bottom, after-add cases, read-only cases; TaskDetailViewModelRelistTest: `moving an item saves the positions that changed as 0 to n`, gaps renumbered, refused position, `a second move while the first is still saving is ignored`, offline, `reordered moves an item and clamps the index` | focused command above | Pass (drag tested with Robolectric touch injection, not on a device) |
| 5. New strings in English and German | Android lint `MissingTranslation` via `lintDebug`; TaskDetailStringsTest (existing) | `cd android && ./gradlew lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.AppStartRoutingTest'` | Pass |
| 6. Unit/Compose tests cover new behavior; `ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass | all of the above | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`; `sh scripts/check.sh` | See "Full test execution" and ".taskcheck" |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.*' createDebugUnitTestCoverageReport`

Result:

Pass (exit 0, 2026-11-08 12:23:45 to 12:24:28 +01:00). 12 suites, 217 tests, 0 failures, 0 skipped: TaskDetailListFieldsTest 37, TaskDetailViewModelRelistTest 29, TaskDetailViewModelEdgeTest 28, TaskDetailRepositoryTest 26, TaskDraftListTest 25, TaskDraftTest 22, TaskDetailViewModelTest 17, TaskRepeatTest 17, TaskDetailContentTest 10, TaskDetailScreenTest 7, TaskDetailStringsTest 3, TaskDetailUserLabelsTest 2.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Fail on an unrelated flaky test, then Pass on rerun. The full run (12:24:46 to 12:28:24) completed 3170 tests with 1 failure: `ch.sonensei.cockpit.auth.AppStartRoutingTest > logging out from settings clears the cached files` (`ComposeTimeoutException` after 5000 ms at AppStartRoutingTest.kt:167). That test is outside the task scope (auth package, untouched by this task). Rerunning it with lint, `./gradlew lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.AppStartRoutingTest'`, passed (exit 0, 9 tests, 0 failures, lintDebug passed). It looks like a timing flake under full-suite load, like the known-flaky `SessionCacheTest`. The parent should rerun the full suite or `.taskcheck` and record the result.

## .taskcheck

Result:

See the parent's final message. `sh scripts/check.sh` (tfp + server + web + android) was started at 12:30 and was still running when this report was written; the test-writer's final message gives the outcome.

## Bugs found

None. No added or existing test in scope failed because of production behavior.

## Coverage

Status: verified (focused selection: the `ch.sonensei.cockpit.gtd.task.*` tests; coverage numbers come from those tests only).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. Method: added lines from `git diff -U0 4bf9400f..HEAD -- android/app/src/main` intersected with JaCoCo `<line>` entries (executable lines only) of the same report. A line counts as covered when `ci > 0`. Branch counts are the `cb`/`mb` counters on those lines. Result: lines 490/490 (100.0%), branches on changed lines 412/442 (93.2%).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-task-detail-dates`; base `4bf9400f551f553883daf617a00cd12a9ddfcdfc` (claim commit, from the parent); HEAD `8b638e6ee45a4c107ffd1cbc4644f4e71b6c95ab`; commits `git log 4bf9400f..HEAD`, checked against the task's `touches`. All changed production files are inside `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `8b638e6e` plus two modified test files: `TaskDetailViewModelRelistTest.kt` sha256 `2b49a4521dfb424563963b2907fe51b4587d67d0311354fb71cfbf7696ea16fd`, `TaskDetailListFieldsTest.kt` sha256 `59f47b3d271dfd023c0a8f6fe30d06ee462c6072ab6bcf183540dc754646a4f6`. No other uncommitted or untracked source files.
- Exact included files: `TaskDetailListFields.kt` (new), `TaskDetailRepository.kt`, `TaskDetailScreen.kt`, `TaskDetailViewModel.kt`, `TaskRepeat.kt` (new), all under `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/`. Excluded: `res/values/strings_task_detail.xml` and `res/values-de/strings_task_detail.xml` (resources, no executable code; checked by lint). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (`createDebugUnitTestCoverageReport`); `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.*' createDebugUnitTestCoverageReport`, run in `android/`; selection `ch.sonensei.cockpit.gtd.task.*`.
- Run start/completion time and exit status: 2026-11-08 12:23:45 to 12:24:28 +01:00, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, mtime 2026-11-08 12:24:28.52 +01:00, sha256 `65ab4737a05acdd1d9fc36b8a77deb457ff8f6c7aecf8cc50deb446c6c5440e8`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The test inputs changed since the previous run, so Gradle re-executed the tests (suite timestamps 11:23:57Z to 11:24:24Z) and rewrote the report. The report hash differs from the pre-addition run (`e194a466...`). The test-file hashes were taken after the run and the files were not edited afterwards.
- Final run after latest test additions and production fixes: yes (no production change during this run).

Values below use covered/total (percentage).

| Changed file | Instructions (statements) | Branches | Methods (functions) | Lines |
|---|---:|---:|---:|---:|
| `TaskDetailListFields.kt` | 3007/3043 (98.8%) | 104/118 (88.1%) | 71/71 (100.0%) | 233/233 (100.0%) |
| `TaskDetailRepository.kt` | 1784/1839 (97.0%) | 122/132 (92.4%) | 98/106 (92.5%) | 166/171 (97.1%) |
| `TaskDetailScreen.kt` | 8065/10778 (74.8%) | 250/400 (62.5%) | 160/253 (63.2%) | 639/788 (81.1%) |
| `TaskDetailViewModel.kt` | 4101/4243 (96.7%) | 361/419 (86.2%) | 98/104 (94.2%) | 388/396 (98.0%) |
| `TaskRepeat.kt` | 699/701 (99.7%) | 97/98 (99.0%) | 18/18 (100.0%) | 58/58 (100.0%) |
| **Aggregate (summed counters, 5 files, no unmeasured files)** | 17656/20604 (85.7%) | 934/1167 (80.0%) | 445/552 (80.6%) | 1484/1646 (90.2%) |

Before this run's additions (same command, report sha256 `e194a466152ecd5b59b3b2b22c3385e7b75bb68e4172f9b6604c1695428599e4`): aggregate branches 925/1167 (79.3%); changed-line branches 404/442.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `TaskDetailScreen.kt` (most missed lines: 119-208, 331-365, 423-446, 876-1080, 1135-1177) | Pre-existing parts of the file (screen scaffold, Activity/back wiring, delete/undo snackbar, older edit fields). Task-changed lines are all covered (66/66). | Outside task scope, no test added. Changed-line gaps: 647/666 (one instruction each in the drag gesture lambdas), 1109 (a `ProblemText` branch for a message type not produced by the new fields), 206 (default callback param) are non-material. |
| `TaskDetailViewModel.kt:675,677` `SCHEDULED`/`WAITING_FOR` problem clearing on a list change | Problems must clear when the user changes the relevant field or list | Added `the missing scheduled date problem clears when another list is chosen`, `the missing person problem clears when the task is no longer moved to Waiting`. 677 now fully covered; 675 has one residual branch (date changed with the same list, already covered by the date-clears test through `scheduledOn`, which also changes the list). |
| `TaskDetailViewModel.kt:418-419` save guards (`busy`/`readOnly`) | Double submit must not send a second move/PATCH | Added `saving again while the move is still being sent sends nothing more`. Remaining partial branches are the `detail == null` and `readOnly` guards (no form can be open in those states). |
| `TaskDetailViewModel.kt:160-165` `patch()` null-safe branches (`scheduledDate?.toString()` with null on SCHEDULED, `waitingForText?.ifBlank`) | A Scheduled task without a date is refused by `problems()` before `patch()`; blank-vs-null person is covered by `a waiting task without a person and the field left empty patches nothing` | Not reachable through save; left uncovered. |
| `TaskDetailViewModel.kt:96-102, 571, 576` | Kotlin default-argument synthetic code; `detail == null` guards in `moveChecklistItem` | Non-material. |
| `TaskDetailListFields.kt:125,129,136` waiting-since field | Waiting-since editable when the task has none | Added the Compose test `a waiting task without a waiting-since day can get one, the picker opening on today` (closed the 132/136 null branches). The rest are Kotlin `?.let` artifacts (no server problem at WAITING_SINCE rendered in the Compose tests; the VM test covers that problem). |
| `TaskDetailListFields.kt:75,94,103,116,184,200,227,262,271,313` | Single `?.let`/elvis branches (e.g. `selectedDateMillis == null` in the picker, which Material does not allow after opening with a date) | Non-material; no test added. |
| `TaskDetailRepository.kt:277,285,309,373,439-440` | Pre-existing code of the file (not changed by the task; task lines 22/22 and their branches 20/20 covered) | Outside task scope. |
| `TaskRepeat.kt:80` `rule.orEmpty()` null branch | Unreachable: a null rule returns earlier (`normalized.isEmpty()`) | Non-material. |

## Unable to test

- On-device long-press drag and TalkBack custom actions: tested with Robolectric (touch injection and `performCustomAccessibilityActionWithLabel`), not on a device or emulator. This is acceptable for criterion 4 as written ("Unit/Compose tests"). A manual device check is optional follow-up.
- Server-side validation of custom RRULEs (only the FREQ presence check happens client-side, as the assumption states) is server behavior and outside this Android task.

## Summary

Tests added. All task-scoped tests pass, and task-scoped coverage is sufficient (changed lines 490/490, changed-line branches 412/442). No production defects found. The full Android suite had one unrelated flaky failure (`AppStartRoutingTest`, auth), which passed on rerun. See the final message for the `.taskcheck` result.
