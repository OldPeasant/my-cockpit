# Fix Report

Date: 2026-11-08

Task: and-projects-manage - Android: create, edit, complete and drop projects

Scope: current task. The findings CR-1, CR-2 and CR-3 from `reviews/and-projects-manage/review.md`, checked against base 9714b6dc5a5558b625f35c0c233ffbce1696f3ce plus the task commits 8283bc4c2 (implementation) and 75b71602f (tests and review). Also one lint error that blocked the requested validation (see "Additional fix").

## Fixed findings

### CR-1 (P2) - An old form save can close or replace a form the user opened later

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModelTest.kt`

Problem:
Cancel and back stay active while a save runs. The save coroutine wrote its result (`DONE` closes the form, `CONFLICT` puts back a rebase of the old draft, a created project gets opened) into whatever form was open when the answer arrived. That could be a form the user opened after cancelling.

Fix:
Every open, replace or close of the form now goes through a private `replaceForm()`, which increments a `formSession` counter. This covers `newProject`, `editProject`, `cancelForm`, `close` for an edit form, and the save results. `saveForm` and `create` capture the session when they start. They only apply the result to the form (close, rebase, clear `saving`, open the created project) if the session has not changed. The project message is still shown, so the user learns that the earlier save went through or was refused.

Validation:
- New tests: `a save that returns after its form was cancelled leaves the form opened since alone`, `a conflict that returns after its form was cancelled does not bring the old draft back`, `a create that returns after its form was cancelled neither closes the next form nor opens the project`.
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.projects.*' lintDebug` - Pass

### CR-2 (P3) - Complete or drop skips the confirmation while the project's tasks are still loading

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectForm.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreenTest.kt`

Problem:
The open-task count was `detail.tasks.size`. That is 0 while the tasks are still loading, so Complete or Drop (from the menu or through the form's status) was sent without asking.

Fix:
Before the tasks have loaded, "unknown" now counts as "maybe some remain". `setStatus` and `saveForm` ask in that case too. `ProjectConfirm.Finish.openTasks` became `Int?`, where `null` means not known yet. The dialog then leaves out the count line, so the title and the Complete/Drop button stay. No new strings were needed.

Validation:
- New tests: `finishing while the project's tasks are still loading asks first, without a count` (ViewModel; covers both the menu path and the form path), `finishing while the open tasks are not known yet asks without a count` (Compose).
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.projects.*' lintDebug` - Pass

### CR-3 (P3) - A pending confirmation can show up again for a different project

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreenTest.kt`

Problem:
`confirm` stayed set when the open project disappeared from the cache. `open()` did not clear it either, so the old Delete or Finish dialog could show up for the next project opened, and `confirm()` would then apply to that project.

Fix:
`ProjectConfirm` now has a `projectId`, and `Finish` and `Delete` both set it. `open()` clears `confirm`. `confirm()` drops a confirmation unless its project is still the open one (it is `selected` and its detail exists). The screen only shows the dialog when `confirm.projectId` matches the open detail. The existing tests that build `ProjectConfirm` values were updated for the new field.

Validation:
- New tests: `a confirmation left open when its project disappeared does not apply to the next project opened`, `a confirmation for a project that is no longer open is dropped` (ViewModel), `a confirmation asked about another project is not shown` (Compose).
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.projects.*' lintDebug` - Pass

### Additional fix (not a review finding) - Android lint `Typos` errors in the new German strings

Files changed:
- `android/app/src/main/res/values-de/strings_projects_lists.xml`

Problem:
`lintDebug` failed with 3 errors: "abschliessen"/"Abschliessen" in `projects_complete`, `projects_confirm_complete_title` and `projects_confirm_complete`. These strings came from implementation commit 8283bc4c2. The rest of the German app uses "ß" ("Suche schließen", "die du abschließt" in the same file).

Fix:
Changed the three strings to "abschließen"/"Abschließen". Lint is part of criterion 7 and the project contract ("fix warnings, don't suppress them"), and the requested validation command could not pass without this fix. No test asserts these German texts.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.projects.*' lintDebug` - Pass (it failed with the 3 lint errors before)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.projects.*' lintDebug` - BUILD SUCCESSFUL. 162 tests in 7 classes under `ch.sonensei.cockpit.gtd.projects`, 0 failures, 0 skipped (ProjectsViewModelTest 57, ProjectsScreenTest 52, ProjectWorkRepositoryTest 24, ProjectsRouteTest 13, ProjectEditingTest 9, ProjectsScreensInAppTest 4, ProjectGroupingTest 3). Lint reports 0 errors.

Full test suite:
- Covered by `sh scripts/check.sh` (see `.taskcheck` below).

Lint/type-check/build:
- ktlintCheck and lintDebug pass (in the command above). Kotlin compiles for main and test.

`.taskcheck`:
- CHECK_RESULT_PLACEHOLDER

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
