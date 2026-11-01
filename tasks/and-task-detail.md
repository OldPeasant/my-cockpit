---
title: Android: task detail screen with editing
status: todo
owner:
deps: [and-dashboard,and-logbook,and-search,and-gtd-data]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/task,android/app/src/main/java/ch/sonensei/cockpit/gtd/next,android/app/src/main/java/ch/sonensei/cockpit/gtd/today,android/app/src/main/java/ch/sonensei/cockpit/gtd/lists,android/app/src/main/java/ch/sonensei/cockpit/gtd/projects,android/app/src/main/java/ch/sonensei/cockpit/search,android/app/src/main/java/ch/sonensei/cockpit/dashboard,android/app/src/main/java/ch/sonensei/cockpit/ui,android/app/src/test/java/ch/sonensei/cockpit/gtd,android/app/src/test/java/ch/sonensei/cockpit/search,android/app/src/test/java/ch/sonensei/cockpit/dashboard,android/app/src/main/res/values/strings_task_detail.xml,android/app/src/main/res/values-de/strings_task_detail.xml]
prio: 2
---
## Goal
In the Android app a task can't be opened: rows in Next, Today, Projects, Waiting, Someday and Logbook only have
the complete checkbox (`gtd/next/TaskListUi.kt` `TaskRow`), and search/Dashboard send tasks to the browser. Add a
task detail screen with editing, like the web task detail (`web/src/app/features/gtd`, task-detail), using the
existing API: `GET/PATCH/DELETE /api/v1/tasks/{id}` (ETag/If-Match), `POST /tasks/{id}/move`,
`/tasks/{id}/complete|uncomplete`, `/tasks/{taskId}/checklist[/{itemId}]`, `/api/v1/links`. Server unchanged.

## Acceptance criteria
- [ ] Tapping a task row (outside the checkbox) in Next, Today, Projects (project's tasks), Waiting, Someday and
      Logbook opens the task detail screen; task results in global search and task entries on the Dashboard open
      it too instead of the browser; Back returns to where the user came from
- [ ] The screen shows title, notes (rendered Markdown, as the Android notes screen renders it), checklist,
      project, contexts, due date/time, defer/start date if the model has one, time estimate, energy, creativity,
      waiting-for person (for Waiting tasks), and linked items (tapping a linked item opens it where the app has a
      screen, else the web deep link)
- [ ] Editing: title, notes, project (picker of active projects), contexts (multi-select), due date/time,
      estimate, energy, creativity; saved with PATCH and `If-Match`; a 412 (changed elsewhere) reloads the task
      and tells the user instead of overwriting; validation errors from Problem Details are shown at the field or
      as a message
- [ ] Checklist: tick/untick, add, rename and delete items
- [ ] Actions: complete / reopen, move to Next, Someday or Waiting (with person), delete (to Trash, with undo
      where the app has that pattern); lists the user returns to show the change without a manual refresh
- [ ] Offline/read cache: when the server is unreachable the task opens read-only from the cached list data with
      the app's "offline" banner; edit controls are disabled
- [ ] All new UI strings exist in English and German (`res/values`, `res/values-de`)
- [ ] ViewModel unit tests (load, edit + If-Match, 412 conflict, checklist, move, delete, offline) and Compose UI
      tests for opening from a list and editing a field; `cd android && ./gradlew ktlintCheck testDebugUnitTest
      lintDebug` passes

## Notes
Requested by the owner (2026-11-01): "I can't open any [task] by selecting one", with editing. Out of scope:
creating new tasks here (capture covers that), recurrence editing (show the rule read-only if present), project
detail editing.
