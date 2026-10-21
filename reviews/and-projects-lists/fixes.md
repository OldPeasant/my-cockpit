# Fix Report

Date: 2026-10-21

Task: and-projects-lists - Android projects, waiting, someday

Scope: current task; findings CR-1 to CR-6 of `reviews/and-projects-lists/review.md`. Fix commit 9d89d866835b6e676ac5cea7af0ce2240e4cb103 (on top of 52b0b9ee; base a5e02f04).

## Fixed findings

### CR-1 - Without loaded areas, every project is shown under "No area"

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreen.kt`
- `android/app/src/main/res/values/strings_projects_lists.xml` (this task's own string file: 2 new strings)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreenTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectGroupingTest.kt`

Problem:
The view model passed `a.data.orEmpty()` to `groupByArea`. While the areas were loading or after the load failed, every project went into "No area", and the areas error was dropped.

Fix:
- The view model keeps the areas it loaded last (`knownAreas`). A later failed load, or a new area load after the screen comes back, keeps using them.
- Until the first area load ends, the list shows "Loading" instead of grouping (`ProjectsUiState.areasLoading`).
- If no areas are known and loading them failed, `groupByArea(projects, null)` puts the projects that have an area into their own group, headed "In an area (areas not loaded)" (`ProjectGroup.areaUnloaded`). Only projects without an area are listed under "No area".
- `ProjectsUiState.areasError` shows a `StaleBanner` with retry: "The areas could not be loaded, so projects are not grouped by area."

Regression tests: VM `the list waits for the areas, and areas that cannot load keep projects out of No area, with an error`, `areas loaded once stay in use when a later load fails`; grouping `without loaded areas the projects with an area are not shown as having none`; screen `without loaded areas the projects with an area are not listed under No area, with a retry`, `while areas load the list shows loading`.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.projects.*' --tests 'ch.sonensei.cockpit.gtd.lists.*'` - Pass

### CR-2 - Project detail: a failed reload disables actions silently

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/ListUi.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreenTest.kt`

Problem:
The detail was read-only when `detail.error != null`, but showed no banner and no retry when tasks had loaded before.

Fix:
`StaleBanner` is now `internal`, with an optional `message`. The detail shows it with retry when `detail.loaded && detail.error != null`. This also covers the case where no open tasks are left.

Regression tests: screen `a detail whose reload failed keeps its tasks read-only, says so and offers a retry`, `a detail whose reload failed without open tasks still offers a retry`.

Validation:
- same command as CR-1 - Pass

### CR-3 - The prompt's "Add" is not atomic; retrying creates the task again

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectWorkRepository.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectWorkRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModelTest.kt`

Problem:
If `createTask` succeeded but re-reading the project or setting the next step failed, tapping "Add" again created a second task.

Fix:
- `addNextStep(project, title, created: UUID? = null)` now returns `NextStepAdded(outcome, taskId)`. `taskId` is set as soon as the task is created, also when a later call fails. If `created` is given, the create call is skipped: the repository re-reads the project's version and sets the next step only.
- The view model stores the created task in the prompt (`NextStepPrompt.created = CreatedStep(title, taskId)`). A retry with the same trimmed title passes that id. A different title creates a new task.

Regression tests: repository `a created next step that could not be set reports the task, and a retry with it only sets the next step`; VM `retrying a new next step whose task was created but not set only sets it`, `a different title after a partly failed add creates a new task`.

Validation:
- same command as CR-1 - Pass

### CR-4 - `ProjectWorkRepository` HTTP/ETag logic untested

Files changed:
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectWorkRepositoryTest.kt`

Problem:
The review was written before `ProjectWorkRepositoryTest` existed. That test (from 52b0b9ee) already covered:
- the `If-Match` values for complete, set and add;
- the `needsNextStep` mapping;
- skipping the cache reload when offline;
- 412 and 404 mapped to CONFLICT;
- areas ordered by position.

It was missing a check of the call order and a 409 case.

Fix:
Added:
- an explicit order check for addNextStep: POST tasks, then GET project, then POST next-step;
- a 409 case on next-step mapped to CONFLICT;
- a 404 case on completion mapped to CONFLICT;
- the CR-3 retry test above, which also checks `If-Match` on the re-read version.

Validation:
- same command as CR-1 - Pass (ProjectWorkRepositoryTest: 16 tests)

### CR-5 - The prompt is not tied to the project whose task was completed

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreenTest.kt` (constructor update only)

Problem:
A completion that returned after the user had opened another project showed the prompt over that other project.

Fix:
- `NextStepPrompt` now carries `projectId`.
- `complete()` records the open project when it is called. It sets the prompt only if that project is still selected when the answer arrives.
- The detail shows a prompt only when the prompt's `projectId` matches the project.
- `makeNextStep` and `addNextStep` close only the prompt of their own project.
- `choose()` without an open project does nothing.

Regression tests: VM `a completion that returns after another project was opened does not prompt there`, `the prompt belongs to the open project, and without one there is none`.

Validation:
- same command as CR-1 - Pass

### CR-6 - The `completed` set is never pruned

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModelTest.kt`

Problem:
Ids of completed tasks stayed hidden for the view model's whole lifetime. The KDoc said they would be dropped.

Fix:
On every successful project task load (data present, no error), `forgetCompleted` removes the ids the loaded list no longer holds. A failed reload prunes nothing. A task reopened elsewhere shows again on the next successful reload.

Regression test: VM `a completed task is hidden until a successful reload no longer holds it, then shows if reopened elsewhere`.

Validation:
- same command as CR-1 - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.projects.*' --tests 'ch.sonensei.cockpit.gtd.lists.*'` - Pass. 98 tests, 0 failures: ProjectsViewModelTest 25, ProjectsScreenTest 18, ProjectWorkRepositoryTest 16, ProjectGroupingTest 9, ListViewModelsTest 10, ListScreensTest 9, LiveLoadTest 6, TaskListsRepositoryTest 5.
- `cd android && ./gradlew lintDebug` - Pass.
- The full requested command `./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests ... --tests ... lintDebug` - Pass on rerun. The first combined run failed in `lintAnalyzeDebugUnitTest` with a lint-internal FileNotFoundException on a generated Hilt file of an unrelated test (`CaptureEntrySurfacesTest_TestComponentDataSupplier.java`). That was a build-state issue. It passed when run separately and on rerun, with no code change.

Full test suite:
- `sh scripts/check.sh` (server, web and android lint plus unit tests) - Pass ("all checks passed")

Lint/type-check/build:
- ktlintCheck and lintDebug - Pass

`.taskcheck`:
- Pass (`sh scripts/check.sh`)

Remaining unresolved findings:
0

## Round 2 - P3 findings of the re-review

Date: 2026-10-21

Scope: findings CR-1 and CR-2 (both P3) of the re-review in `reviews/and-projects-lists/review.md` (reviewed revision bd8f525a; task HEAD before this round 83b74e83). Only `android/app/src/{main,test}/java/ch/sonensei/cockpit/gtd/projects` changed.

### Fixed findings (round 2)

#### CR-1 - Cached projects hidden behind "Loading" until the live areas request ends

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreenTest.kt`

Problem:
While no areas were known and the areas request was still running, `groups` was empty and `loading` was true. When the server could not be reached, the cached projects stayed hidden until the HTTP timeout.

Fix:
`groups` is always `groupByArea(visible, v.areas)`. With no known areas this is the "areas not loaded" grouping, so the cached projects show at once. `areasError` stays null while the areas load, so no stale banner is shown. `ProjectsUiState.loading` is now `groups.isEmpty() && ((refreshedAt == null && !offline) || areasLoading)`. So the list says "Loading" only when there is nothing to show, and the header shows the stalled count for the cached projects.

Tests:
- VM `cached projects show at once without their areas while the areas still load, without an error` (cached projects, pending areas load, then the areas arrive and the projects are grouped by area).
- VM `without cached projects the list shows loading while the areas still load`.
- The earlier VM test `the list waits for the areas, ...` asserted the old behaviour (empty groups while areas load), which this finding changes. It became `areas that cannot load keep projects out of No area, with an error`. Its failure and recovery assertions are kept.
- Screen `while areas load the cached projects show without their areas and without an error`. The old screen test (empty groups with `areasLoading`) is kept as `while areas load without projects the list shows loading`.

#### CR-2 - An offline project detail showed two stacked offline banners

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreenTest.kt`

Problem:
When offline, the list's `OfflineBanner` and the detail's `StaleBanner` with the offline text were shown one above the other.

Fix:
The detail's `StaleBanner` is shown only when `detail.loaded && detail.error != null && (detail.error == LoadError.FAILED || !state.offline)`. A FAILED reload still shows its banner and retry when offline. The detail stays read-only.

Tests:
- Screen `offline, a detail whose reload failed offline shows only the offline banner`.
- Screen `offline, a detail whose reload failed for another reason still says so and offers a retry`.
- The existing tests for a stale detail when not offline are unchanged and pass.

### Not fixed (round 2)

None.

### Validation summary (round 2)

Focused tests:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.projects.*' --tests 'ch.sonensei.cockpit.gtd.lists.*'` - Pass. 117 tests, 0 failures (from the JUnit XML results): ProjectsViewModelTest 27, ProjectsScreenTest 26, ProjectWorkRepositoryTest 16, ProjectGroupingTest 9, ListScreensTest 14, ListViewModelsTest 13, LiveLoadTest 6, TaskListsRepositoryTest 6.

Full test suite:
- `sh scripts/check.sh` (server, web and android lint plus unit tests, including android lintDebug) - Pass ("all checks passed").

Lint/type-check/build:
- ktlintCheck and android lint (in `scripts/check.sh`) - Pass.

`.taskcheck`:
- Pass (`sh scripts/check.sh`).

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed (round 1: CR-1 to CR-6 of the first review; round 2: the P3 findings CR-1 and CR-2 of the re-review).
