# Test Report

Date: 2026-11-01

Task: and-capture-button - Android: in-app capture button on every screen

Scope: `git diff 3b902c15d5d5b7c30adc86f831f569be8be4aa72..0d921566cac88cdad800f424e9a9cfc196a0fceb -- android` (base and implementation commit given by the parent worker; the only task-owned implementation commit is 0d921566c). Branch `main`, HEAD 0d921566cac88cdad800f424e9a9cfc196a0fceb. Uncommitted inputs: only the test files listed below (no production changes in the working tree).

Requirements:
- `tasks/and-capture-button.md` acceptance criteria and recorded assumptions (reuse of `CaptureActivity`, which has no attachment picker; placement via `CockpitDestination.withOwnFab`; badge plus content description)
- `SPEC.md` §2 (quick capture from every screen), §9.3 (offline capture queue)
- `docs/ARCHITECTURE.md` "Coverage" and "Code conventions" (EN/DE strings), `AGENTS.md`

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/capture/entry/CaptureButtonTest.kt` (extended)
- `android/app/src/test/java/ch/sonensei/cockpit/capture/entry/CaptureButtonFlowTest.kt` (new)

## Tests added or updated

Count: 8 added (5 in `CaptureButtonTest`, 3 in `CaptureButtonFlowTest`); the 6 existing tests are unchanged.

Added:
- `CaptureButtonTest`: `one pending capture is described in the singular form and shown in the badge`, `a large count keeps the exact number in the description`, `both placements call the click handler`, `the button is described in German` (`@Config(qualifiers = "de")`), `the app-bar action on a screen with its own FAB opens the capture sheet in the app's task`.
- `CaptureButtonFlowTest` (`@HiltAndroidTest`, real `MainActivity` after a fictitious login, real Room-backed `CaptureQueue`, `RecordingScheduler` instead of WorkManager so nothing is uploaded, as in airplane mode): `the badge on the shell's button follows the offline queue`, `saving in the sheet opened from the FAB queues offline, closes the sheet and counts it as pending`, `cancelling the sheet opened from a screen's app-bar action returns to that screen without queueing`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Capture button on every shell screen; Notes, Files, Mail, Calendar get the app-bar action, never two FABs | `every screen has exactly one capture button and screens with their own FAB get it in the app bar` (existing), `both placements call the click handler`, `the app-bar action on a screen with its own FAB opens the capture sheet in the app's task`, flow test `cancelling ... app-bar action ...` | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug --continue` | Pass |
| 2. Button opens the capture sheet; saving writes to the offline queue (works without network); pending count updates | `tapping the button opens the capture sheet in the app's task` (existing), `the view model follows the queue's pending count` (existing), `an unreadable queue shows no count instead of failing` (existing), `the badge shows the pending count and the description says it` (existing), `one pending capture ...`, `a large count keeps the exact number ...`, `a large count is capped in the badge` (existing), flow tests `the badge on the shell's button follows the offline queue` and `saving in the sheet opened from the FAB queues offline, closes the sheet and counts it as pending` | same | Pass (offline: no sync worker or server, capture stays pending in the Room queue; a real device in airplane mode was not used) |
| 3. Closing the sheet returns to the screen the user was on | flow tests `saving ... closes the sheet ...` (shell stays open on Today) and `cancelling the sheet opened from a screen's app-bar action returns to that screen without queueing` (shell stays on Notes); intent has no `FLAG_ACTIVITY_NEW_TASK` (existing + new) | same | Pass under Robolectric. Note: Robolectric does not model the task/back stack, so "returns to" is shown as: sheet finishes, the shell activity is not finishing and still shows the same screen, and the intent targets the app's own task. The real back-stack behaviour on a device was not run. |
| 4. New UI strings in English and German | `the button is described in German` (singular and plural description in `values-de`), English descriptions in several tests; `lintDebug` (MissingTranslation) | same | Pass |
| 5. Unit tests (ViewModel + Compose UI) cover the criteria; `ktlintCheck testDebugUnitTest lintDebug` passes | all of the above | same | Pass (BUILD SUCCESSFUL, 218 test classes, 2260 tests, 0 failures, 0 errors, 0 skipped) |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.entry.CaptureButton*'`

Result:

Pass (`CaptureButtonTest` 11/11, `CaptureButtonFlowTest` 3/3). The first run had 3 failures caused by my own tests (they looked for the badge's number as text, but the implementation deliberately clears the badge text from semantics so the number is not read out twice); the assertions were corrected to check the badge tag and that the number is not exposed as a separate text node.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug --continue`

Result:

Pass (BUILD SUCCESSFUL in 3m 11s, finished 2026-11-01T14:14:44+01:00; 2260 tests, 0 failures). An earlier run failed only on a ktlint finding in the new test file (`Expected newline before '.'`), fixed before this run.

## .taskcheck

Result:

Not run by the test-writer (`sh scripts/check.sh` also runs the server and web checks, which this task does not touch). The Android part of it (`ktlintCheck testDebugUnitTest lintDebug`) passed as above; the parent worker runs `.taskcheck` before `done`.

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured by intersecting JaCoCo `<line>` entries of `report.xml` with the added lines of `git diff -U0 3b902c15d..0d921566c -- android/app/src/main/java`: 64/65 changed executable lines covered (98.5 %); changed-line branches 20/23 (CaptureButton.kt 14/17, CockpitShell.kt 6/6, CockpitDestination.kt 0/0).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-capture-button; base 3b902c15d5d5b7c30adc86f831f569be8be4aa72; HEAD 0d921566cac88cdad800f424e9a9cfc196a0fceb; task-owned commit 0d921566c as given by the parent and matching the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 0d921566c plus uncommitted tests `CaptureButtonTest.kt` sha256 bdf935c3d098cee600a93a0b087871526e23368c68841d27ece04bab6805b7f0 and `CaptureButtonFlowTest.kt` sha256 ee00341995bd9ed7914de47f4e57c20dbe319dedbae2a2ed774deb679a3913a1 (hashes taken after the last edit, before the coverage report, unchanged since).
- Exact included files; renamed/deleted/excluded files and reasons: included `android/app/src/main/java/ch/sonensei/cockpit/capture/entry/CaptureButton.kt`, `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt`, `android/app/src/main/java/ch/sonensei/cockpit/ui/navigation/CockpitDestination.kt`. Excluded (resources, not instrumentable code): `res/values/strings_capture_entry.xml`, `res/values-de/strings_capture_entry.xml` (verified by tests and lint instead). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`, Gradle 9.8.1; `./gradlew createDebugUnitTestCoverageReport` in `android/`; all debug unit tests (execution data from the full `testDebugUnitTest` run above on the same snapshot).
- Run start/completion time and exit status: full test run finished 2026-11-01T14:14:44+01:00 (exit 0); report task 2026-11-01T14:14:51 to 14:14:57+01:00, BUILD SUCCESSFUL.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (mtime 2026-11-01 14:14:57.635 +0100, sha256 d5b6ae32aaf2056606180d0bb1721472a067134ebf433d9ac9f5450718a85e19).
- Evidence outputs are fresh and inputs stayed unchanged: yes; the report was rewritten in this run, the test files were not edited after the full run, and the working tree had no production changes.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage).

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/capture/entry/CaptureButton.kt` | 566/577 (98.1 %) | 14/17 (82.4 %) | 22/22 (100 %) | 57/58 (98.3 %) |
| `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt` | 1104/1118 (98.7 %) | 53/55 (96.4 %) | 29/29 (100 %) | 92/93 (98.9 %) |
| `android/app/src/main/java/ch/sonensei/cockpit/ui/navigation/CockpitDestination.kt` | 341/341 (100 %) | 9/9 (100 %) | 13/13 (100 %) | 34/34 (100 %) |
| Aggregate (summed counters, 3 measured files; 2 resource files excluded, none unmeasured) | 2011/2036 (98.8 %) | 76/81 (93.8 %) | 64/64 (100 %) | 183/185 (98.9 %) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CaptureButton.kt:61` (1 branch, 2 instructions) | Rethrow of `CancellationException` in the queue-status `catch` | Defensive coroutine hygiene; cancellation is not observable from a test of the button. Justified gap. |
| `CaptureButton.kt:82` (1 branch) | Compiler-generated default-argument branch for `modifier` of `CaptureButton` | The shell never passes a modifier; no behavior. Justified gap. |
| `CaptureButton.kt:118`, `:129` (1 branch, 9 instructions) | Synthetic `NoWhenBranchMatchedException` path of the exhaustive `when (placement)` | Unreachable; both real placements are tested. Justified gap. |
| `CockpitShell.kt:69-70`, `:158`, `:177` | Pre-existing reminder-request handling and navigation code, not changed by this task | Outside the task's changed lines; all 6 changed executable lines of the shell are covered. |

Before the new tests, the Hilt branch of `capturePendingCount()` (`hiltViewModel()` plus `collectAsStateWithLifecycle`) was only reachable through a Hilt activity, which the existing tests did not use; `CaptureButtonFlowTest` now exercises it.

## Unable to test

- On-device behavior in real airplane mode and the real Android task/back stack after the sheet finishes: Robolectric models neither radio state nor task switching. Covered as closely as possible (no network path is ever called; sheet finishes, shell activity stays open on the same screen; intent stays in the app's task). Follow-up only if the parent wants manual or instrumented (`androidTest`) evidence.
- Attachment in the in-app sheet: per the recorded assumption, the widget's sheet (`CaptureActivity`) has no attachment picker, so there is nothing to test for this path; attachment queuing is covered by the existing share tests.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.
