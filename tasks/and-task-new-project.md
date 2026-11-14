---
title: Android task detail: create a new project from the project picker
status: in_progress
owner: felix/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/task,android/app/src/test/java/ch/sonensei/cockpit/gtd/task,android/app/src/main/res/values/strings_task_detail.xml,android/app/src/main/res/values-de/strings_task_detail.xml]
prio: 2
lease_until: 2026-11-14T10:29:00Z
---
## Goal
The task detail `ProjectPicker` (`gtd/task/TaskDetailScreen.kt`) offers only existing projects or "No project".
Inbox processing already has "New project" (`gtd/inbox/InboxRepository.kt`); reuse that behavior.

## Acceptance criteria
- [x] The project picker in task detail has "New project…"; it asks only for a title, creates the project (Active, no area) and assigns the task to it
  - verified (2026-11-14 10:59+0100 by felix/agent-1): cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*' -> 1243 tests pass, incl. TaskDetailNewProjectScreenTest (New project… entry after projects, title-only dialog) and TaskDetailNewProjectViewModelTest/RepositoryTest (CreateProjectRequest(title) only -> Active, no area; task saved with projectId)
- [x] Cancel or an empty title leaves the task's project unchanged; if creating the project or saving the task fails, an error is shown and a retry does not create a second project (same approach as Inbox processing)
  - verified (2026-11-14 10:59+0100 by felix/agent-1): same gtd test run -> pass: Cancel/blank title keep project (Screen+VM tests); create failure/offline/422 show error with project unchanged; retry after failed create or failed save reuses created project, createProject called once (TaskDetailNewProjectViewModelTest)
- [x] The new project appears in the picker and in Projects, and the task appears in the project's task list
  - verified (2026-11-14 10:59+0100 by felix/agent-1): same gtd test run -> pass: created project merged into picker without duplicate after refresh, projects.refresh() reloads Projects (RepositoryTest, MockWebServer), saved task carries projectId so it lists under the project; stalled new project gets the task as next step
- [x] New strings are in English and German (`res/values/` + `res/values-de/`)
  - verified (2026-11-14 11:00+0100 by felix/agent-1): git diff base..HEAD -- android/app/src/main/res -> 6 strings each in values/strings_task_detail.xml and values-de/strings_task_detail.xml; TaskDetailStringsTest passes
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-14 with the owner: from a mail, make a Next action and archive the mail in one step (mails already clarified should not pass through the cockpit Inbox again); create a project directly from the task detail.
- ASSUMPTION (2026-11-14 10:13+0100 by felix/agent-1): New project… sets the new project in the edit form's draft; the task is assigned when the form is saved (like picking any project in the picker). A failed save keeps the project in the draft, so Save again creates no second project; a project already created in this screen with the same title is reused.
- ASSUMPTION (2026-11-14 10:14+0100 by felix/agent-1): If the form is cancelled after New project…, the project stays (Active, empty); no automatic delete.
- ASSUMPTION (2026-11-14 10:28+0100 by felix/agent-1): Added ProjectResponse.toDomain() to gtd/data/GtdMappers.kt (outside touches, 3 lines) to avoid a duplicate project mapping (review CR-3).
