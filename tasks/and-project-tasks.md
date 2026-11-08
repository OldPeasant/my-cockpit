---
title: Android: add tasks directly to a project and reorder them
status: in_progress
owner: dell26/agent-1
deps: [and-projects-manage]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/projects,android/app/src/test/java/ch/sonensei/cockpit/gtd/projects,android/app/src/main/res/values/strings_projects_lists.xml,android/app/src/main/res/values-de/strings_projects_lists.xml]
prio: 1
lease_until: 2026-11-08T12:35:31Z
---
## Goal
On Android a task can only be added to a project as "next step" inside the choose-next-step prompt; web has an
"Add task" field on project detail (`project-detail-page.html:186-205`) and reordering (`:124-138`).

## Acceptance criteria
- [x] Project detail has an "Add task" field/button that creates a Next task in the project (quick entry, stays open for several tasks); optional "make it the next step"
  - verified (2026-11-08 13:15+0100 by dell26/agent-1): cd android && ./gradlew ktlintCheck testDebugUnitTest --tests '*gtd.projects*' (HEAD e14b996c3) -> 228 tests, 0 failures; ProjectTaskListTest 'the add field adds a task with the button and with the keyboard, and stays open', 'a project without a next step does not offer the choice...', ProjectsViewModelTest add/next-step tests, ProjectWorkRepositoryTest create-in-project tests pass
- [x] Tasks can be reordered with long-press drag (with accessibility actions "move up/down"); the order is saved like on web
  - verified (2026-11-08 13:15+0100 by dell26/agent-1): cd android && ./gradlew ktlintCheck testDebugUnitTest --tests '*gtd.projects*' (HEAD e14b996c3) -> 228 tests, 0 failures; ProjectTaskListTest long-press drag tests (drag saves on drop only; cancelled/interrupted drags not saved), 'the move actions are offered where a task can move' (customActions on the focused clickable row node), ProjectsViewModelTest serialized order saves via orderProjectTasks like web. TalkBack on a device not checked (no device)
- [x] A task row opens task detail; tasks of other lists (Waiting/Someday/Scheduled) keep their list label
  - verified (2026-11-08 13:15+0100 by dell26/agent-1): same run -> ProjectTaskListTest 'a task row opens task detail, and tasks of other lists keep their list label', ProjectsScreenTest 'project tasks on other lists say which list they are on' pass
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 12:54+0100 by dell26/agent-1): Android project detail: the Add task field sits above the task list (web has it below) so it stays reachable over a long list; 'Make it the next step' is only offered while the project has a next step, since a stalled project gets the new task as next step anyway (web rule).
- ASSUMPTION (2026-11-08 12:54+0100 by dell26/agent-1): Android reorder: long-press anywhere on a task row drags it; no auto-scroll at the list edges while dragging (long lists: drag in steps or use the Move up/down accessibility actions). A drag only shows a snackbar on failure; Move up/down announce 'Moved X to position n of m' like web's live region. Adding a task shows 'Added X'.
