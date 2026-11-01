# Code Review

Date: 2026-11-01

Task: and-capture-button - Android: in-app capture button on every screen

Scope: `git diff 3b902c15d..HEAD -- android` (implementation commit 0d921566c, tests commit 7347b4cbd)

Requirements:
- `tasks/and-capture-button.md` acceptance criteria and the two recorded assumptions (reuse of `CaptureActivity` in the app task without an attachment picker; FAB everywhere except Calendar/Mail/Notes/Files, which get an app-bar action; pending count as a badge)
- `SPEC.md` §2 (quick capture from every screen), §9.3 (offline capture queue)
- `docs/ARCHITECTURE.md`, `AGENTS.md` project notes (EN/DE strings, lint, feature folders)
- `reviews/and-capture-button/tests.md`

Git range:
- Branch: main
- HEAD: 7347b4cbd
- Base/start: 3b902c15d5d5b7c30adc86f831f569be8be4aa72
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | New shell FAB covers the Undo action of the task-completion snackbar on Today, Next, Waiting, Someday and Projects**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt:91`
    - Category: Correctness
    - Problem: The capture FAB is now placed in the shell `Scaffold`'s `floatingActionButton` slot on every screen without its own FAB. The GTD list screens show their snackbars from their own `SnackbarHost` aligned `BottomCenter` inside the content (`gtd/today/TodayScreen.kt:111`, `gtd/next/NextScreen.kt:162`, `gtd/lists/ListUi.kt:99`, `gtd/projects/ProjectsScreen.kt:171`), not through the Scaffold's `snackbarHost` slot. The completion snackbar carries an "Undo" action (`gtd/next/TaskListUi.kt:193-196`, `SnackbarDuration.Long`), which Material 3 lays out at the end of the snackbar. The Scaffold FAB (16 dp above the bottom bar, end-aligned, drawn after the content) sits in the same bottom-end area as that action, so on a phone the FAB lies over the Undo button. Before this change these screens had no FAB, so this is a regression caused by the task. The same applies to the last rows of the lists, which have no bottom content padding for the FAB, but the snackbar action is the important case.
    - Why it matters: Completing a task by mistake is meant to be reversible through Undo. With the FAB on top, a tap meant for Undo opens the capture sheet instead, and the undo window ends.
    - Suggested fix: Make sure the FAB and the snackbar don't overlap. One option is to move these screens' snackbars into the shell Scaffold's `snackbarHost` slot (Scaffold then places the snackbar above the FAB). Another is to give the in-content `SnackbarHost` and the lists bottom padding equal to the FAB's height plus spacing on screens that show the shell FAB. Add a Compose test that shows the completion snackbar inside the shell and checks that the Undo action is not covered by the FAB bounds.

## P3 Low

- [ ] **CR-2 | A quick double tap opens two stacked capture sheets**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/entry/CaptureButton.kt:86,91`
    - Category: Correctness
    - Problem: `onClick` calls `context.startActivity(captureSheetIntent(context))` on every tap. The intent has no flags, and `CaptureActivity` is declared without a launch mode (`AndroidManifest.xml:60-67`). Two taps before the translucent sheet covers the button therefore start two `CaptureActivity` instances in the app task.
    - Why it matters: After saving in the first sheet, the user sees a second empty sheet instead of the screen they came from. This partly undoes criterion 3 ("Closing the sheet returns to the screen the user was on") in a common case: a hasty double tap.
    - Suggested fix: Add `Intent.FLAG_ACTIVITY_SINGLE_TOP` to `captureSheetIntent`. This keeps the sheet in the app task, and the existing test that checks for no `FLAG_ACTIVITY_NEW_TASK` still passes. You can also ignore clicks while a launch is pending. Extend the intent test to assert the flag.

## Notes (not findings)

- Correctness against the criteria checks out:
    - placement through `CockpitDestination.withOwnFab`, and the four screens' own FABs confirmed in `CalendarWithEditor.kt`, `MailScreen.kt`, `NotesScreen.kt` and `FilesScreen.kt`;
    - the sheet stays in the app task: no `FLAG_ACTIVITY_NEW_TASK`, so the manifest's `taskAffinity=""` does not apply;
    - EN/DE strings and plurals are present;
    - the badge text is hidden from accessibility services, and the button description carries the count.
- The `GeneratedComponentManagerHolder` guard in `capturePendingCount()` follows the same pattern as several existing screens, so it is not a finding.
- Evidence (`tasks.py verify`) is not recorded yet. The test report says criteria 2 ("works in airplane mode") and 3 (the real task/back stack) were run only under Robolectric, with a `RecordingScheduler` instead of a device in airplane mode. The parent should record those parts as what was actually run, or skip them with a reason, rather than claim on-device behavior.

## Review conclusion

Material issues found; fix before completing the task.
