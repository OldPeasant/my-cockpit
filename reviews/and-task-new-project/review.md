# Code Review

Date: 2026-11-14

Task: and-task-new-project - Android task detail: create a new project from the project picker

Scope: final revision 4e39ae4159871458a6c77fd0f431bc844acfa46e against base 0415413eef1fc2bc162f3d44ef2457c8971dd1a0 (`git diff 0415413e..4e39ae41 -- . ':!tasks' ':!reviews'`). Re-review after the rebase: the production code was compared against the earlier reviewed revision (c61e62b32) and is identical; the new test-only commit 4e39ae415 was reviewed in full. Gradle was not run by this review (a parallel test-writer runs it).

Requirements:
- tasks/and-task-new-project.md (goal, acceptance criteria, recorded assumptions: project set in the draft and assigned on save with title-keyed reuse on retry; a cancelled form keeps the created project; `ProjectResponse.toDomain()` added in `gtd/data/GtdMappers.kt` outside `touches`)
- SPEC.md (projects, stalled rule, §4.2 conventions)
- AGENTS.md project notes
- Reference behavior: android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxRepository.kt ("New project", `makeNextStepIfStalled`)

Git range:
- Branch: main
- HEAD: 4e39ae4159871458a6c77fd0f431bc844acfa46e
- Base/start: 0415413eef1fc2bc162f3d44ef2457c8971dd1a0
- Commits reviewed: 4 (290215502 implementation, 9942f87e5 tests and reports, a1cfe443f fixes for CR-2/CR-3 of the first review, 4e39ae415 extra tests only); no uncommitted changes in scope

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Verification of the final revision

- Production code (`GtdMappers.kt`, `TaskDetailRepository.kt`, `TaskDetailScreen.kt`, `TaskDetailViewModel.kt`, EN/DE `strings_task_detail.xml`): `git diff c61e62b32 a1cfe443f -- android/app/src/main` is empty, so the code is the same as in the earlier re-review, which found no issues. Re-read on this revision: `newProject` guards blank title, missing draft, busy and read-only state; reuses a project created here with the same title; sets it in the draft only on success and shows `ProjectNotCreated`/`Offline` otherwise. `save()` calls `makeNextStepIfStalled` only for a project created in this screen after a successful save; the repository mirrors the Inbox best-effort handling (cancellation rethrown, other failures swallowed).
- Test-only commit 4e39ae415:
  - `GtdMappersTest` "a project answer maps straight to the same domain project as through the cache row": checks every `GtdProject` field for a fully populated answer, the defaults for a minimal one, and equality with `toEntity(n).toDomain()` for all statuses. Behavioral, not implementation-coupled.
  - `TaskDetailNewProjectRepositoryTest` "no connection at all is ignored too": closes the MockWebServer and asserts `makeNextStepIfStalled` returns without throwing and makes no next-step call. The second `server.close()` in `tearDown` follows the existing pattern (`InboxRepositoryTest`) for mockwebserver3.
  - Local Gradle test results from 10:37 (`android/app/build/test-results/testDebugUnitTest`) show `TaskDetailNewProjectRepositoryTest` 7 tests and `GtdMappersTest` 9 tests, 0 failures, including the new tests.

## Criteria checked without findings

1. "New project…" is the last picker entry; the dialog asks only for a title; `CreateProjectRequest(title)` gives an Active project without area; the task is assigned when the form is saved (recorded assumption).
2. Dialog Cancel and blank titles leave the project unchanged; create failures show an error and leave the draft's project unchanged; retry after a failed save or a repeated "New project…" with the same title creates no second project.
3. The created project is merged into the picker until the projects reload (no duplicate after), `projects.refresh()` updates Projects, the saved `projectId` puts the task in the project's task list, and the next-step call keeps the new project from showing as stalled.
4. All six new strings exist in `values/` and `values-de/` with matching placeholders.
5. ViewModel, repository (MockWebServer), Compose and strings tests cover the new behavior. The full `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` runs were not done by this review; the parent worker must run and record them.

## Review conclusion

No material issues found.
