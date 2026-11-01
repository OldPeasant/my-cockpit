# Test Report

Date: 2026-11-01

Task: and-capture-button - Android: in-app capture button on every screen

Scope: final code after the review fixes. `git diff 9047beca89b6183436af3f2932918d8bd9fa150f..a3e3ef1590de6c69351820031225ddacd84ad565 -- android`. The base is `git merge-base HEAD origin/main`. The task commits since then are a5a00aa2b (implementation), 7ba2dc2b7 (tests), 36b172c01 (review), 30a395e1e (CR-1/CR-2 fixes), and a3e3ef159 (FAB clearance for list bottoms, fixer report). Branch `main`, HEAD a3e3ef1590de6c69351820031225ddacd84ad565. Uncommitted inputs: only `FabClearanceTest.kt`, extended by this run. The working tree has no production changes. `reviews/and-capture-button/review.md` was also modified in the working tree, by another helper, not by the test-writer.

This report replaces the earlier one, which described the pre-fix revision 0d921566c (before rebase) and is stale.

Requirements:
- `tasks/and-capture-button.md`: the acceptance criteria and the recorded assumptions. The sheet is the existing `CaptureActivity`, which has no attachment picker. Placement follows `CockpitDestination.withOwnFab`. The pending count shows as a badge plus the content description. `LocalFabClearance` pads the GTD snackbars and lists.
- `SPEC.md` §2 (quick capture from every screen) and §9.3 (offline capture queue)
- `reviews/and-capture-button/review.md` (CR-1, CR-2) and `reviews/and-capture-button/fixes.md`
- `docs/ARCHITECTURE.md` "Coverage" and "Code conventions" (EN/DE strings), and `AGENTS.md`

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/ui/FabClearanceTest.kt` (extended in this run)

Already committed in the task and re-run unchanged: `android/app/src/test/java/ch/sonensei/cockpit/capture/entry/CaptureButtonTest.kt` and `android/app/src/test/java/ch/sonensei/cockpit/capture/entry/CaptureButtonFlowTest.kt`.

## Tests added or updated

Count: 5 added in this run, all in `FabClearanceTest`. No existing test was changed.

Gap that was found: the fixer's real-screen regression test only covered Someday (`ListUi`). The FAB-clearance changes in Today, Next, the Projects list, and the project detail were not exercised through their real content, either for the snackbar position or for the list's bottom padding. New tests:
- `on Today in the shell, the Undo action and the last row lie above the FAB`: real `TodayContent` with 30 due tasks and an Undo snackbar.
- `on Next in the shell, the Undo action and the last row lie above the FAB`: real `NextContent` with 30 tasks and an Undo snackbar.
- `on Projects in the shell, the snackbar and the last project lie above the FAB`: real `ProjectsContent` list with 30 projects and an `ActionSnackbar` (conflict message).
- `on a project's detail in the shell, the last task's make-next-step button lies above the FAB`: real project detail with 30 tasks. The trailing flag button sits exactly where the FAB is.
- `outside the shell a screen gets no FAB clearance`: `LocalFabClearance` defaults to 0 dp, so screens outside the shell get no extra padding.

Each test renders the real screen inside the real shell frame (`CockpitShellFrame`, with the real capture FAB). It checks that the snackbar text ends above the FAB's top edge. It then scrolls the list to its last item and checks that the whole last row, or its trailing control, ends above the FAB's top edge (tolerance 1 px).

These checks catch a regression from the geometry. Without the clearance, the snackbar sits at the content bottom, and the Scaffold places the FAB 16 dp above the content bottom with a height of 56 dp. The last item of an unpadded list ends at the content bottom. In both cases the checked bounds would end below the FAB's top. I did not run a negative check here, because it would need a temporary production edit, which the test-writer may not make. The fixer's earlier negative check on `ListUi` (padding removed → test failed) shows that this kind of assertion detects the regression.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. A capture button on every shell screen. Notes, Files, Mail, and Calendar get the app-bar action instead, so there are never two FABs | `CaptureButtonTest`: `every screen has exactly one capture button and screens with their own FAB get it in the app bar`, `both placements call the click handler`, `the app-bar action on a screen with its own FAB opens the capture sheet in the app's task`. `FabClearanceTest`: `screens under the shell FAB get its clearance and screens with their own FAB get none`. `CaptureButtonFlowTest`: `cancelling the sheet opened from a screen's app-bar action returns to that screen without queueing` | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --continue` | Pass |
| 1 (follow-up, CR-1). The new FAB does not cover the screens' own Undo/snackbar actions or the last row's controls (Today, Next, Waiting/Someday, Projects, project detail) | `FabClearanceTest`: `a screen's own snackbar padded by the clearance sits above the shell FAB`, `on a real GTD list in the shell, neither the Undo action nor the last row's action lies under the FAB` (Someday/`ListUi`), and the 4 new real-screen tests for Today, Next, Projects, and project detail, plus `outside the shell a screen gets no FAB clearance` | same | Pass |
| 2. The button opens the capture sheet. Saving writes to the offline queue and works in airplane mode. The pending count updates | `CaptureButtonTest`: `tapping the button opens the capture sheet in the app's task`, `the view model follows the queue's pending count`, `an unreadable queue shows no count instead of failing`, `the badge shows the pending count and the description says it`, `one pending capture ...`, `a large count keeps the exact number ...`, `a large count is capped in the badge`. `CaptureButtonFlowTest`: `the badge on the shell's button follows the offline queue`, `saving in the sheet opened from the FAB queues offline, closes the sheet and counts it as pending` | same | Pass under Robolectric only. Offline is simulated: there is no sync worker or server (`RecordingScheduler`), and the capture stays pending in the real Room queue. A real device in airplane mode was not used. |
| 2 (CR-2). A quick double tap does not stack two sheets | `CaptureButtonTest`: both intent tests assert `FLAG_ACTIVITY_SINGLE_TOP` and no `FLAG_ACTIVITY_NEW_TASK` | same | Pass for the intent flags. Robolectric does not apply launch-mode/flag semantics to a real task, so the "one sheet on a real device" behavior was not run. |
| 3. Closing the sheet returns to the screen the user was on | `CaptureButtonFlowTest`: `saving ... closes the sheet ...` (the shell stays on Today) and `cancelling the sheet opened from a screen's app-bar action returns to that screen without queueing` (the shell stays on Notes). The intent has no `FLAG_ACTIVITY_NEW_TASK` | same | Pass under Robolectric only. Robolectric does not model the real task/back stack, so "returns to" is shown in three ways: the sheet finishes, the shell activity is not finishing and still shows the same screen, and the intent targets the app's own task. The real back stack on a device was not run. |
| 4. New UI strings in English and German | `CaptureButtonTest`: `the button is described in German` (singular and plural from `values-de`); English descriptions in several tests; `lintDebug` (MissingTranslation) | same | Pass |
| 5. Unit tests (ViewModel + Compose UI) cover the criteria; `ktlintCheck testDebugUnitTest lintDebug` passes | all of the above | same | Pass (BUILD SUCCESSFUL in 2m 11s; 219 test classes, 2268 tests, 0 failures, 0 errors, 0 skipped) |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.ui.FabClearanceTest'`

Result:

Pass (`FabClearanceTest` 8/8). `CaptureButtonTest` (11) and `CaptureButtonFlowTest` (3) passed in the full run below. After the focused run, `ktlintTestSourceSetCheck` flagged chained calls in the new test code (`Expected newline before '.'`). I fixed them in the test file (no assertion changed), and `./gradlew ktlintTestSourceSetCheck` then passed.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --continue`

Result:

Pass. Started 2026-11-01T14:53:15+01:00, BUILD SUCCESSFUL in 2m 11s, exit 0. `testDebugUnitTest` re-executed: the JUnit XML timestamps run from 13:53:24Z to 13:55:22Z, with 2268 tests, 0 failures, 0 errors, and 0 skipped. `ktlintCheck` and `lintDebug` passed.

An earlier run of the same command (started 14:49:51+01:00) failed only on the ktlint finding above in my new test code. In that run the unit tests did not run, and its coverage report was discarded.

## .taskcheck

Result:

Not run by the test-writer. `sh scripts/check.sh` also runs the server and web checks, and this task touches neither. The Android part of the check (`ktlintCheck testDebugUnitTest lintDebug`) passed as shown above. The parent worker runs `.taskcheck` before `done`. `fixes.md` reports that earlier `.taskcheck` runs failed on load-induced web vitest timeouts unrelated to this task.

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured by intersecting the JaCoCo per-line entries of `report.xml` (lines with instructions) with the added/modified new-side lines of `git diff -U0 9047beca8..a3e3ef159 -- android/app/src/main/java`. Result: 100/101 changed executable lines covered (99.0 %); branches on changed lines 56/79 (70.9 %). Every missed branch on a changed line is compiler-generated (see the table below).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: task and-capture-button. Base 9047beca89b6183436af3f2932918d8bd9fa150f (`git merge-base HEAD origin/main`). Measured HEAD a3e3ef1590de6c69351820031225ddacd84ad565. Task-owned commits a5a00aa2b, 7ba2dc2b7, 36b172c01, 30a395e1e, and a3e3ef159, as given by the parent and confirmed with `git log`. The gtd/ files are outside the task's `touches`, but the task's recorded assumptions cover them.
- Source/test snapshot identifier (including uncommitted inputs): HEAD a3e3ef159, plus uncommitted `FabClearanceTest.kt` with sha256 5d435e25d881368176c94ace48b5f82b1c5afa7c11cb5e500f1ccd79f77384cc. The hash was taken before the run and was identical after it. The committed tests are `CaptureButtonTest.kt` (sha256 c957fb01821cd6808ffc7109ee65842c7d4d7850b5e6f8426368c1433fcb231d) and `CaptureButtonFlowTest.kt` (sha256 ee00341995bd9ed7914de47f4e57c20dbe319dedbae2a2ed774deb679a3913a1). The working tree had no production changes before or after the run.
- Exact included files; renamed/deleted/excluded files and reasons: included `capture/entry/CaptureButton.kt`, `ui/CockpitShell.kt`, `ui/FabClearance.kt`, `ui/navigation/CockpitDestination.kt`, `gtd/lists/ListUi.kt`, `gtd/next/NextScreen.kt`, `gtd/projects/ProjectsScreen.kt`, and `gtd/today/TodayScreen.kt`, all under `android/app/src/main/java/ch/sonensei/cockpit/`. Excluded: `res/values/strings_capture_entry.xml` and `res/values-de/strings_capture_entry.xml`, because resources are not instrumentable; the tests and lint verify them instead. No renames or deletions. No scoped file is missing from the report.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`, Gradle 9.8.1. Command `./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --continue`, run in `android/`. Test selection: all debug unit tests.
- Run start/completion time and exit status: started 2026-11-01T14:53:15+01:00, finished about 14:55:30+01:00, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, mtime 2026-11-01 14:55:28.750 +0100, sha256 27448a16c1c2d3eba8ae6a96a3bd5d4d875efd1fa729e9a4bbc2920c62a697ea. The numbers were extracted with `.tmp/cov.py` (Git-ignored scratch).
- Evidence outputs are fresh and inputs stayed unchanged: yes. `testDebugUnitTest` and `createDebugUnitTestCoverageReport` both executed in this run, and the report was rewritten at 14:55:28. The test-file hash and `git status` were the same before and after the run.
- Final run after latest test additions and production fixes: yes. The run covers HEAD a3e3ef159, which includes the CR-1/CR-2 fixes and the fixer's list padding, plus the tests added here.

Values below use covered/total (percentage). JaCoCo counters: Statements = INSTRUCTION, Functions = METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/capture/entry/CaptureButton.kt` | 571/582 (98.1 %) | 14/17 (82.4 %) | 22/22 (100 %) | 57/58 (98.3 %) |
| `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt` | 1189/1216 (97.8 %) | 52/59 (88.1 %) | 33/33 (100 %) | 98/99 (99.0 %) |
| `android/app/src/main/java/ch/sonensei/cockpit/ui/FabClearance.kt` | 21/21 (100 %) | N/A (0/0) | 4/4 (100 %) | 2/2 (100 %) |
| `android/app/src/main/java/ch/sonensei/cockpit/ui/navigation/CockpitDestination.kt` | 341/341 (100 %) | 9/9 (100 %) | 13/13 (100 %) | 34/34 (100 %) |
| `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/ListUi.kt` | 1372/1395 (98.4 %) | 52/64 (81.2 %) | 30/35 (85.7 %) | 121/121 (100 %) |
| `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/NextScreen.kt` | 2016/2038 (98.9 %) | 67/70 (95.7 %) | 48/57 (84.2 %) | 164/164 (100 %) |
| `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreen.kt` | 4002/4060 (98.6 %) | 160/182 (87.9 %) | 69/79 (87.3 %) | 334/336 (99.4 %) |
| `android/app/src/main/java/ch/sonensei/cockpit/gtd/today/TodayScreen.kt` | 1088/1101 (98.8 %) | 59/71 (83.1 %) | 17/21 (81.0 %) | 79/79 (100 %) |
| Aggregate (summed counters; 8 measured files; 2 resource files excluded; none unmeasured) | 10600/10754 (98.6 %) | 413/472 (87.5 %) | 236/264 (89.4 %) | 889/893 (99.6 %) |

The four gtd/ files are whole-file numbers. Most of their code predates this task: only the snackbar padding and `LazyColumn` content padding lines changed, and all 20 of those changed executable lines are covered.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CaptureButton.kt:61` (1 of 2 branches) | Rethrow of `CancellationException` in the queue-status `catch` | Defensive coroutine hygiene that a test of the button cannot observe. Justified gap. |
| `CaptureButton.kt:82` (1 of 2 branches) | Compiler-generated default-argument branch for `modifier` of `CaptureButton` | The shell never passes a modifier, so there is no behavior here. Justified gap. |
| `CaptureButton.kt:121`, `:132` (1 branch, 1 line) | Synthetic `NoWhenBranchMatchedException` path of the exhaustive `when (placement)` | Unreachable. Both real placements are tested. Justified gap. |
| `CockpitShell.kt:65` (3 of 6 branches) | Compose `$changed`/`$default` bits of the new one-line `CockpitShell` wrapper | Compiler-generated. Both the defaulted call (`CockpitShell()`) and the call with an explicit `navController` are exercised. Justified gap. |
| `ListUi.kt:93`, `NextScreen.kt:158`, `TodayScreen.kt:103`, `ProjectsScreen.kt:210`, `:360` (2-6 branches each) | Compose `$changed`/`$default` bits of the `LazyColumn(...)` calls whose `contentPadding` changed | Compiler-generated. Real-screen tests in `FabClearanceTest` cover the padding behavior on each of these lists (added in this run for Today, Next, Projects, and project detail). Justified gap. |
| `CockpitShell.kt:62`, `:82`, `:179`, `:198`; other gtd/ partial branches (e.g. `ProjectsScreen.kt:313`, `:485`) | Pre-existing code (reminder-request handling, navigation, project status/list labels) that this task did not change | Outside the task's changed lines. No action. |

## Unable to test

- **Real airplane mode and the real Android task/back stack after the sheet finishes.** Robolectric models neither radio state nor real task switching, nor how launch flags such as `FLAG_ACTIVITY_SINGLE_TOP` behave on a real task. Under Robolectric, the tests cover this as closely as possible: no network path is called, and captures stay pending in the real Room queue; the sheet finishes and the shell stays open on the same screen; the intent carries SINGLE_TOP and stays in the app's task. Follow-up only if the parent wants manual or instrumented (`androidTest`) evidence on a device.
- **Attachment in the in-app sheet.** Per the recorded assumption, the widget's sheet (`CaptureActivity`) has no attachment picker, so this path has nothing to test. The existing share tests cover attachment queuing.
- **Negative check of the new real-screen FAB-clearance tests.** Running them without the padding needs a temporary production edit, which the test-writer may not make. The geometry argument and the fixer's earlier negative check on `ListUi` are above.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.
