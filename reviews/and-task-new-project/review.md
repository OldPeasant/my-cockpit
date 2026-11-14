# Code Review

Date: 2026-11-14

Task: and-task-new-project - Android task detail: create a new project from the project picker

Scope: implementation commit 990502fd7ba7ca48931f268e467f5f499340fdb9 on base 42aeffb510fbaa757d97b23da39a83f72ef847ff (`git diff 42aeffb5..990502fd`); HEAD c65dc2e03 is the same change rebased (no android diff between 990502fd and HEAD). Uncommitted test files of the parallel test-writer were ignored.

Requirements:
- tasks/and-task-new-project.md (goal, acceptance criteria, two recorded assumptions)
- SPEC.md (projects, stalled rule, §4.2 conventions)
- AGENTS.md project notes
- Reference behavior: android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxRepository.kt ("New project")

Git range:
- Branch: main
- HEAD: c65dc2e03 (rebased equivalent of 990502fd7ba7ca48931f268e467f5f499340fdb9)
- Base/start: 42aeffb510fbaa757d97b23da39a83f72ef847ff
- Commits reviewed: 1

Summary: 0 P0, 1 P1, 1 P2, 1 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | No tests cover the new behavior in the reviewed commit**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/FakeTaskDetailSource.kt:158`
    - Category: Tests
    - Problem: The commit only adapts test fixtures (fake `createProject`, extra constructor arguments in `TaskDetailRepositoryTest`/`TaskConversionRepositoryTest`). Nothing tests `TaskDetailViewModel.newProject` (success sets draft project, blank title ignored, failure shows `ProjectNotCreated` and leaves the project unchanged, retry with the same title reuses the created project), `TaskDetailRepository.createProject` (request body, mapping, projects refresh, 400/422 handling), or the picker's "New project…" dialog (cancel, disabled Create on empty title).
    - Why it matters: Acceptance criterion 5 requires unit/Compose tests for the new behavior; criterion 2's no-duplicate retry is regression-prone and currently unverified.
    - Suggested fix: Commit the test-writer's tests covering the cases above (ViewModel, repository against the mock server, Compose picker/dialog) before recording criterion 5.

## P2 Medium

- [ ] **CR-2 | New project stays stalled although the task is put into it (diverges from Inbox "New project")**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailViewModel.kt:466`
    - Category: Correctness
    - Problem: The task goal says to reuse the Inbox behavior. Inbox processing (`InboxRepository.kt:172-173`, `makeNextStepIfStalled`) makes the item the next step of a stalled (e.g. newly created) project. Task detail only sets `projectId` in the draft and saves; the server's stalled rule (`ProjectService.kt:36`, SPEC.md "active project without an open next task is flagged") requires an explicit next step, so the new project with its only task shows as stalled in Projects/Dashboard. No assumption records this deviation.
    - Why it matters: Every project created this way immediately appears as stalled, unlike the same flow from the Inbox, giving inconsistent behavior and a false "stalled" signal.
    - Suggested fix: After a successful save that assigned the task to a project created here, set the task as the project's next step when the project is stalled (as Inbox does, failure non-fatal), or record a `tasks.py assume` explaining why task detail deliberately differs.

## P3 Low

- [ ] **CR-3 | Duplicated ProjectResponse-to-GtdProject mapping**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailRepository.kt:368`
    - Category: Quality
    - Problem: `createProject` hand-maps `ProjectResponse` to `GtdProject` with only some fields (drops `nextTaskId`, `dueDate`, `reviewDate`, `descriptionMd`), duplicating the mapping already in `gtd/data/GtdMappers.kt` (`ProjectResponse.toEntity` / `CachedProjectEntity.toDomain`).
    - Why it matters: Two mappings of the same response can drift (e.g. a new field or status mapping change applied to only one).
    - Suggested fix: Add a shared `ProjectResponse.toDomain()` in `GtdMappers.kt` (data package) or reuse the existing mapping, instead of a local partial copy.

## Review conclusion

Material issues found; fix before completing the task.

Notes on criteria checked without findings: "New project…" asks only for a title and creates via `CreateProjectRequest(title)` (server default Active, no area); cancel and blank titles leave the draft unchanged; create failures show an error and leave the project unchanged; a failed save keeps the created project in the draft and a repeated "New project…" with the same title reuses it (matching the recorded assumption and Inbox's title-keyed reuse); the created project is merged into the picker until the projects refresh, and `projects.refresh()` updates the Projects screen; EN and DE strings are both added.
