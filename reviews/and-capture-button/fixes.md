# Fix Report

Date: 2026-11-01

Task: and-capture-button - Android: in-app capture button on every screen

Scope: current task. Findings CR-1 (P2) and CR-2 (P3) from `reviews/and-capture-button/review.md`. Base commit 21f4c93132a459c2c8e8af31f820803e16126865.

Provenance: the parent worker's session was interrupted while it was fixing CR-1 and CR-2. Its uncommitted fix code was later committed as c3ddfb07d, without a fixer report. The fixer reviewed that commit critically and found it correct for the snackbar part of CR-1 and for CR-2. The fixer then completed CR-1 for the list rows (uncommitted changes, listed below), added a regression test against a real GTD screen, and ran the validation below.

## Fixed findings

### CR-1 — New shell FAB covers the Undo action of the task-completion snackbar on Today, Next, Waiting, Someday and Projects

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/ui/FabClearance.kt` (c3ddfb07d)
- `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt` (c3ddfb07d)
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/today/TodayScreen.kt` (c3ddfb07d snackbar; fixer list padding)
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/NextScreen.kt` (c3ddfb07d snackbar; fixer list padding)
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/ListUi.kt` (c3ddfb07d snackbar; fixer list padding)
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreen.kt` (c3ddfb07d snackbar; fixer list padding on the project list and the project detail list)
- `android/app/src/test/java/ch/sonensei/cockpit/ui/FabClearanceTest.kt` (c3ddfb07d; fixer added one test)

Problem:
The shell's capture FAB sits in the bottom-end corner of the content. The GTD screens' own in-content `SnackbarHost` was aligned to the bottom, so the FAB covered the snackbar's Undo action. The review also noted that the lists have no bottom content padding. On Waiting and Someday (`LiveTaskRow` with its trailing "Follow up"/"Activate" button), the last row's action cannot be scrolled out from under the FAB.

Fix:
- c3ddfb07d (checked by the fixer): `LocalFabClearance` (default 0 dp) is provided by the shell for each destination: 72 dp (56 dp FAB + the Scaffold's 16 dp FAB spacing) on screens that show the shell FAB, 0 dp on Calendar/Mail/Notes/Files (`CockpitDestination.withOwnFab`). The four GTD `SnackbarHost`s are padded by it. The fixer checked the geometry: the shell content is laid out inside the Scaffold's inner padding (above the bottom bar), and the Scaffold places the FAB 16 dp above that edge, so 72 dp puts the snackbar's bottom at the FAB's top. Inbox and the other shell-FAB screens have no in-content snackbars (grep for `SnackbarHost(`/`showSnackbar`), so no other screen needs the snackbar padding. `CockpitShellFrame` (internal) only extracts the existing shell body so tests can place content inside the real frame. `CockpitShell`'s public signature is unchanged.
- Fixer addition: the five `LazyColumn`s in those same GTD files (Today, Next, `LiveTaskListContent` for Waiting/Someday, Projects list, project detail) now use `contentPadding = PaddingValues(bottom = LocalFabClearance.current)`. With this, the last row can be scrolled above the FAB. The extra space only shows on screens with the shell FAB. The fixer judged this part of the same regression, because without it a row's action button on the Waiting/Someday screens is unreachable under the FAB. The edit stays in the files and the one-line pattern that the parent's recorded assumption covers (gtd/ is outside the task's `touches`).
- Fixer test: `FabClearanceTest` "on a real GTD list in the shell, neither the Undo action nor the last row's action lies under the FAB" renders the real `SomedayContent` (30 tasks, an Undo completion snackbar) inside the real shell frame. It checks that the Undo action's bounds and, after scrolling to the end, the last row's action bounds do not overlap the FAB. Negative check: with the `ListUi` list padding temporarily removed, the test failed (`last row's action DpRect(left=246.0.dp, top=329.0.dp, right=304.0.dp, bottom=381.0.dp) must not lie under FAB DpRect(left=248.0.dp, top=318.0.dp, right=304.0.dp, bottom=374.0.dp)`). With the padding restored, it passes. The existing c3ddfb07d tests also check the clearance per destination, and include a control showing that an unpadded snackbar does overlap the FAB.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.ui.FabClearanceTest' --tests 'ch.sonensei.cockpit.capture.entry.CaptureButtonTest'` — Pass
- Same `FabClearanceTest` run with the `ListUi` list padding temporarily removed — Fail as expected (regression caught), padding then restored
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` — Pass

### CR-2 — A quick double tap opens two stacked capture sheets

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/capture/entry/CaptureButton.kt` (c3ddfb07d)
- `android/app/src/test/java/ch/sonensei/cockpit/capture/entry/CaptureButtonTest.kt` (c3ddfb07d)

Problem:
`captureSheetIntent` had no flags, and `CaptureActivity` has the standard launch mode, so two quick taps started two sheets in the app task.

Fix:
c3ddfb07d adds `Intent.FLAG_ACTIVITY_SINGLE_TOP` to `captureSheetIntent`. Once the first sheet is on top, a second launch reuses it instead of stacking a new one. The intent still has no `FLAG_ACTIVITY_NEW_TASK`, so the sheet stays in the app task and criterion 3 is unaffected. Both intent tests (FAB and app-bar action) now also assert the flag. The fixer checked this and made no changes. Remaining limitation (not changed): SINGLE_TOP only applies once the first sheet is the top activity. Two taps delivered before the first `startActivity` is processed could in theory still race. This is the reviewer's suggested fix, and debouncing clicks was optional in the review.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.entry.CaptureButtonTest'` — Pass
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` — Pass

## Not fixed

None of the supplied findings remain open.

Observation outside the findings, for the parent to decide (no change made): the other shell-FAB screens' lists (Inbox, Habits, New, Review, Contacts, More) also have no FAB bottom clearance. From reading the code, their rows are either whole-row clickable or only have controls at the start, so the FAB can cover part of the last row but no control that can't be reached otherwise. They are outside this task's touches, and the review did not raise them.

## Validation summary

Focused tests:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.ui.FabClearanceTest' --tests 'ch.sonensei.cockpit.capture.entry.CaptureButtonTest'` — Pass
- Negative check of the new FabClearanceTest test without the list padding — failed as intended

Full test suite:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` — BUILD SUCCESSFUL; 2263 unit tests, 0 failures, 0 errors, 0 skipped

Lint/type-check/build:
- Android ktlintCheck and lintDebug — Pass (after `./gradlew ktlintFormat` wrapped the new `LazyColumn` arguments and sorted the `PaddingValues` imports)

`.taskcheck`:
- Fail, unrelated to this task. `sh scripts/check.sh` was run twice. The tfp gate and server parts passed. The web vitest part failed both times, with 13 and then 7 tests hitting `Test timed out in 5000ms`, and a different set of web specs failed in each run. The machine's load average was about 40 because of parallel agents, and the task changes no file under `web/`. Because the web part failed, `check.sh` stopped before its Android part, which passed when run directly (see above). The parent should run `.taskcheck` again when the machine is less loaded.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed (Android `ktlintCheck testDebugUnitTest lintDebug`). `.taskcheck` currently fails only on load-induced timeouts in unrelated web unit tests and must be re-run by the parent.
