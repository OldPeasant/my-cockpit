# Fix Report

Date: 2026-11-14

Task: and-task-new-project - Android task detail: create a new project from the project picker

Scope: current task; findings CR-2 and CR-3 of `reviews/and-task-new-project/review.md` (CR-1 was already resolved by the committed test-writer tests and was not part of this fix run).

## Fixed findings

### CR-2 — New project stays stalled although the task is put into it

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailRepository.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/FakeTaskDetailSource.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailNewProjectViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailNewProjectRepositoryTest.kt`

Problem:
Inbox "New project" makes the item the next step of a stalled project (`InboxRepository.makeNextStepIfStalled`); task detail only saved the `projectId`, so a project created via "New project…" stayed stalled (server rule in `ProjectService`: an ACTIVE project needs an explicit open next step).

Fix:
- New `TaskDetailSource.makeNextStepIfStalled(projectId, taskId)`. `TaskDetailRepository` implements it like Inbox: `getProject`; if `stalled`, `setProjectNextStep` with `If-Match` of the project's version, then `projects.refresh()` so Projects shows it as not stalled. Best effort: IO, HTTP, serialization, argument and date errors are swallowed (same exception set as Inbox); cancellation is rethrown.
- `TaskDetailViewModel.save()`: after a successful save, if the saved task's `projectId` is one of `state.createdProjects` (made in this screen), it calls `makeNextStepIfStalled` before `source.changed()`. Saves into existing projects, failed saves, and a created project that was not chosen in the end do not call it.
- `FakeTaskDetailSource`: records `nextSteps`; its `update` now applies a patched `projectId` to the returned task (as the server does) so the VM's check against the saved task can be tested.
- Tests: VM (called after saving into a created project; not before save; not for an existing stalled project; not when the created project was swapped out; not after a failed save). Repository against MockWebServer (stalled -> POST next-step with `If-Match: "2"` and the task id, projects reloaded once; not stalled -> no next-step call, no reload; 503 on read, 412 on next-step and an unreadable project answer are ignored and reload nothing).

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*'` — Pass (TaskDetailNewProjectViewModelTest 15/15, TaskDetailNewProjectRepositoryTest 6/6, TaskDetailNewProjectScreenTest 7/7, no failures in any gtd suite)

### CR-3 — Duplicated ProjectResponse-to-GtdProject mapping

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/data/GtdMappers.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailRepository.kt`

Problem:
`createProject` hand-mapped `ProjectResponse` to `GtdProject`, dropping `nextTaskId`, `dueDate`, `reviewDate`, `descriptionMd`, and using `status.value` where the cache mapping uses `status.name`.

Fix:
Added `internal fun ProjectResponse.toDomain() = toEntity(0).toDomain()` in `GtdMappers.kt`, so the domain project goes through exactly the existing cache mapping; `createProject` now uses `it!!.toDomain()`. The unused `ProjectStatus` import was removed. `GtdMappers.kt` is outside the task's `touches` list (one 3-line addition); the parent asked for the mapper there explicitly.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*'` — Pass (existing test `creates an Active project with only the title, then reloads the projects` checks the mapped project)

## Not fixed

None. (CR-1 was resolved before this run by the committed tests; not re-examined as part of this fix scope.)

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*'` — Pass (89 gtd test classes reported, 0 failures/errors)

Full test suite:
- Not run by the fixer (only the gtd test filter requested by the parent).

Lint/type-check/build:
- ktlintCheck — Pass; Kotlin compile of main and test sources — Pass. `lintDebug` not run.

`.taskcheck`:
- Not run: the fixer's attempt to run `sh scripts/check.sh` was denied by the sandbox permissions; the parent worker must run it.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed (focused gtd suite and ktlint; `.taskcheck` and `lintDebug` left to the parent worker).
