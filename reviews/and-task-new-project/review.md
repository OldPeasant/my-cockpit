# Code Review

Date: 2026-11-14

Task: and-task-new-project - Android task detail: create a new project from the project picker

Scope: final revision c61e62b32 against base 42aeffb510fbaa757d97b23da39a83f72ef847ff (`git diff 42aeffb5..c61e62b32`), re-review after the fixes for CR-1..CR-3 of the earlier review. Uncommitted test files of the parallel test-writer were ignored; Gradle was not run.

Requirements:
- tasks/and-task-new-project.md (goal, acceptance criteria, two recorded assumptions: project set in the draft and assigned on save, title-keyed reuse on retry; a cancelled form keeps the created project)
- SPEC.md (projects, stalled rule, §4.2 conventions)
- AGENTS.md project notes
- Reference behavior: android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxRepository.kt ("New project", `makeNextStepIfStalled`)
- Server contract: server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/ProjectService.kt (`setNextStep`, stalled rule)
- reviews/and-task-new-project/fixes.md

Git range:
- Branch: main
- HEAD: c61e62b32
- Base/start: 42aeffb510fbaa757d97b23da39a83f72ef847ff
- Commits reviewed: 3 task commits (c65dc2e03 implementation, eee80490b tests and reports, c61e62b32 fixes); assumption/heartbeat commits touch only `tasks/`

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Verification of earlier findings

- CR-1 (no tests for the new behavior): resolved. Committed tests cover the ViewModel (`TaskDetailNewProjectViewModelTest`: create with trimmed title, draft and picker, blank title, no edit form, failure/refusal/offline messages with the project unchanged, retry creates once, save again after a failed save creates no second project, title reuse, next step only for a project made here and only after a successful save), the repository against MockWebServer (`TaskDetailNewProjectRepositoryTest`: request body, projects reload, 400/422 field message, offline/failed without reload, stalled next step with `If-Match`, not stalled, ignored failures) and the Compose picker/dialog (`TaskDetailNewProjectScreenTest`: entry after the projects, title-only dialog with Create disabled while blank, trimmed title, Cancel, end-to-end with the ViewModel, error messages), plus EN/DE strings (`TaskDetailStringsTest`).
- CR-2 (new project left stalled): resolved. `TaskDetailRepository.makeNextStepIfStalled` mirrors `InboxRepository.makeNextStepIfStalled` (GET project, `setProjectNextStep` with the project's ETag only when stalled, same swallowed exception set, cancellation rethrown) and refreshes the projects afterwards. `TaskDetailViewModel.save()` calls it only after a successful save whose returned task is in a project created in this screen. Server `ProjectService.setNextStep` changes only the project, so the task version held by the screen stays valid; a refusal (e.g. a done task) is a 400 that is ignored as best effort, matching Inbox.
- CR-3 (duplicated mapping): resolved. `ProjectResponse.toDomain()` in `gtd/data/GtdMappers.kt` reuses `toEntity(0).toDomain()`; `createProject` uses it. This file is outside the task's `touches`; the 3-line addition is documented in fixes.md and is low conflict risk.

## Criteria checked without findings

1. "New project…" is the last picker entry; the dialog asks only for a title; `CreateProjectRequest(title)` gives an Active project without area; the project is set in the draft and assigned on save (recorded assumption #1).
2. Dialog Cancel and blank titles (disabled Create, plus ViewModel guard) leave the project unchanged; create failures show `ProjectNotCreated`/Offline and leave the draft's project unchanged; a failed save keeps the created project in the draft and a repeated "New project…" with the same title reuses it, so no second project is created.
3. The created project is merged into the picker until the projects refresh (no duplicate after the refresh), `projects.refresh()` updates Projects, and the saved `projectId` puts the task into the project's task list; the next-step fix also keeps the project from being shown as stalled.
4. All six new strings exist in `values/` and `values-de/` with matching placeholders.
5. Tests exist for the new behavior (see CR-1). Whether `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass on the final revision was not run by this review (fixes.md reports only the gtd test filter and ktlint; `lintDebug` and `.taskcheck` remain for the parent worker to run and record).

## Review conclusion

No material issues found.
