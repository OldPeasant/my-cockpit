---
title: Android: task detail screen with editing
status: done
owner: dell26/agent-1
deps: [and-dashboard,and-logbook,and-search,and-gtd-data]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/task,android/app/src/main/java/ch/sonensei/cockpit/gtd/next,android/app/src/main/java/ch/sonensei/cockpit/gtd/today,android/app/src/main/java/ch/sonensei/cockpit/gtd/lists,android/app/src/main/java/ch/sonensei/cockpit/gtd/projects,android/app/src/main/java/ch/sonensei/cockpit/search,android/app/src/main/java/ch/sonensei/cockpit/dashboard,android/app/src/main/java/ch/sonensei/cockpit/ui,android/app/src/test/java/ch/sonensei/cockpit/gtd,android/app/src/test/java/ch/sonensei/cockpit/search,android/app/src/test/java/ch/sonensei/cockpit/dashboard,android/app/src/main/res/values/strings_task_detail.xml,android/app/src/main/res/values-de/strings_task_detail.xml]
prio: 2
lease_until: 
hold: 
releases: 0
extended: 
---
## Goal
In the Android app a task can't be opened: rows in Next, Today, Projects, Waiting, Someday and Logbook only have
the complete checkbox (`gtd/next/TaskListUi.kt` `TaskRow`), and search/Dashboard send tasks to the browser. Add a
task detail screen with editing, like the web task detail (`web/src/app/features/gtd`, task-detail), using the
existing API: `GET/PATCH/DELETE /api/v1/tasks/{id}` (ETag/If-Match), `POST /tasks/{id}/move`,
`/tasks/{id}/complete|uncomplete`, `/tasks/{taskId}/checklist[/{itemId}]`, `/api/v1/links`. Server unchanged.

## Acceptance criteria
- [x] Tapping a task row (outside the checkbox) in Next, Today, Projects (project's tasks), Waiting, Someday and
      Logbook opens the task detail screen; task results in global search and task entries on the Dashboard open
      it too instead of the browser; Back returns to where the user came from
  - verified (2026-11-02 11:32+0100 by dell26/agent-1): android/gradlew -p android testDebugUnitTest (Robolectric Compose, rev c171fc231) -> pass: TaskDetailScreenTest (Next row opens, checkbox still completes; Waiting row opens; shell route on top + Back returns to list), TaskDetailContentTest (Logbook row in shell opens detail), DashboardTaskOpenTest (Today/Waiting card tasks open detail), SearchRouteInAppTest (task hit opens in-app detail, not browser). Today/Projects/Someday share TaskRow/LiveTaskRow (opensTask). No device/emulator run.
- [x] The screen shows title, notes (rendered Markdown, as the Android notes screen renders it), checklist,
      project, contexts, due date/time, defer/start date if the model has one, time estimate, energy, creativity,
      waiting-for person (for Waiting tasks), and linked items (tapping a linked item opens it where the app has a
      screen, else the web deep link)
  - verified (2026-11-02 11:32+0100 by dell26/agent-1): android/gradlew -p android testDebugUnitTest (rev c171fc231) -> pass: TaskDetailContentTest 'shows the task's fields, notes rendered as Markdown, checklist and linked items', 'a Waiting task shows whom it waits for', 'a Someday task shows its tickler date'; TaskDetailRepositoryTest link direction; linked-item routing unit-tested (Hilt-only nav lambda not driven)
- [x] Editing: title, notes, project (picker of active projects), contexts (multi-select), due date/time,
      estimate, energy, creativity; saved with PATCH and `If-Match`; a 412 (changed elsewhere) reloads the task
      and tells the user instead of overwriting; validation errors from Problem Details are shown at the field or
      as a message
  - verified (2026-11-02 11:32+0100 by dell26/agent-1): android/gradlew -p android testDebugUnitTest (rev c171fc231) -> pass: TaskDetailScreenTest 'editing the title saves it with PATCH', 'a field error from the server is shown at the field'; TaskDetailRepositoryTest (MockWebServer: PATCH sends If-Match "version" + only changed fields, 412/409 -> Conflict, 400/422 Problem Details field errors); TaskDetailViewModelTest/EdgeTest (412 reloads, closes form, Conflict message); TaskDraftTest (patch body for all fields)
- [x] Checklist: tick/untick, add, rename and delete items
  - verified (2026-11-02 11:32+0100 by dell26/agent-1): android/gradlew -p android testDebugUnitTest (rev c171fc231) -> pass: TaskDetailContentTest 'checklist items are ticked, renamed, deleted and added on the detail view'; TaskDetailViewModelTest checklist tests; TaskDetailRepositoryTest checklist POST/PATCH/DELETE requests
- [x] Actions: complete / reopen, move to Next, Someday or Waiting (with person), delete (to Trash, with undo
      where the app has that pattern); lists the user returns to show the change without a manual refresh
  - verified (2026-11-02 11:32+0100 by dell26/agent-1): android/gradlew -p android testDebugUnitTest (rev c171fc231) -> pass: TaskDetailContentTest move to Waiting asks for person, delete snackbar offers undo / closes when unused; TaskDetailViewModelTest/EdgeTest complete, reopen, move, delete + undo (trash restore); TaskDetailRepositoryTest complete/uncomplete/move/trash/restore requests and TaskEdits signal; lists reload via TaskEdits (TaskLists/LogbookRepository merge)
- [x] Offline/read cache: when the server is unreachable the task opens read-only from the cached list data with
      the app's "offline" banner; edit controls are disabled
  - verified (2026-11-02 11:32+0100 by dell26/agent-1): android/gradlew -p android testDebugUnitTest (rev c171fc231) -> pass: TaskDetailScreenTest 'offline the task shows read-only with the offline banner' (edit controls disabled); TaskDetailViewModelTest/EdgeTest offline load from cached copy, reload offline keeps loaded task; TaskDetailRepositoryTest cached() snapshot then Room views, Logbook completion kept
- [x] All new UI strings exist in English and German (`res/values`, `res/values-de`)
  - verified (2026-11-02 11:32+0100 by dell26/agent-1): android/gradlew -p android testDebugUnitTest --tests ch.sonensei.cockpit.gtd.task.TaskDetailStringsTest (rev c171fc231) -> pass: every string/plural in values/strings_task_detail.xml exists in values-de with the same placeholders; lintDebug 0 errors (MissingTranslation clean)
- [x] ViewModel unit tests (load, edit + If-Match, 412 conflict, checklist, move, delete, offline) and Compose UI
      tests for opening from a list and editing a field; `cd android && ./gradlew ktlintCheck testDebugUnitTest
      lintDebug` passes
  - verified (2026-11-02 11:32+0100 by dell26/agent-1): android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport (rev c171fc231) -> BUILD SUCCESSFUL, all unit tests green; gtd.task 108 tests 0 failures (VM: load, edit+If-Match, 412, checklist, move, delete, offline; Compose: open from list, edit title)

## Notes
Requested by the owner (2026-11-01): "I can't open any [task] by selecting one", with editing. Out of scope:
creating new tasks here (capture covers that), recurrence editing (show the rule read-only if present), project
detail editing.
- ASSUMPTION (2026-11-01 23:26+0100 by dell26/agent-2) [confirmed 2026-11-02 06:06+0100 by Peter Gürtler]: Edit happens in a form opened by an Edit button (title, notes, project, contexts, due date/time, estimate, energy, creativity) with Save/Cancel pinned on top, not per-field inline editing; checklist and actions work directly on the detail view.
- ASSUMPTION (2026-11-01 23:26+0100 by dell26/agent-2) [confirmed 2026-11-02 06:06+0100 by Peter Gürtler]: On a 412 the task is reloaded and the form is closed (unsaved edits are dropped, with a message) rather than merged; nothing is overwritten.
- ASSUMPTION (2026-11-01 23:26+0100 by dell26/agent-2): Offline read cache: the detail screen uses the opened row's in-memory copy (TaskSnapshots, cleared on logout), else the Room-cached Inbox/Next/Today rows; Waiting/Someday/Project/Logbook are not cached, so their copy only exists in memory while the app runs.
- ASSUMPTION (2026-11-01 23:26+0100 by dell26/agent-2): Delete moves to Trash and shows an Undo snackbar on the detail screen (POST /trash/TASK/{id}/restore, like Bookmarks); when the snackbar ends without Undo the screen closes back to the list.
- ASSUMPTION (2026-11-01 23:26+0100 by dell26/agent-2): Live lists (Waiting, Someday, project tasks, Logbook) also reload on a local TaskEdits signal after a detail change; cached views are refreshed directly; the Dashboard already reloads on resume.
- ASSUMPTION (2026-11-01 23:26+0100 by dell26/agent-2): Task and checklist PATCH use hand-built JsonObject bodies (TaskWriteApi), like EventWriteApi, because the generated request classes send every field as null and the server treats null as clear.
- ASSUMPTION (2026-11-01 23:26+0100 by dell26/agent-2): Linked items: TASK opens the task detail, NOTE/EMAIL/CONTACT open the existing in-app item screens, others the web deep link, else the type's list screen (same rules as search). Recurrence shows the raw RRULE read-only.
- hold by dell26/agent-2: Android unit tests cannot run on this machine: the user's /tmp quota is full of leaked /tmp/robolectric-nativeruntime* dirs (205 MB each), so testDebugUnitTest fails ~1500 tests with 'Disk quota exceeded' (also .taskcheck). A human must delete the stale dirs; fix the leak in new task and-test-tmpdir. Implementation is committed (screen, editing, checklist, actions, offline, strings, VM + Compose tests, search tests adapted); left: run tests, test-writer/code-reviewer/fixer, verify criteria, done.
- unhold by Peter Gürtler: /tmp cleaned (13 GB free); test-tmp-in-build fixes the leak
- ASSUMPTION (2026-11-02 11:32+0100 by dell26/agent-1): Criteria 1-6 verified with Robolectric Compose/unit tests only (no emulator or device on this machine); the real-device flow was not exercised.
- done by dell26/agent-1: `sh scripts/check.sh` passed in 67s on f51f7c475; criteria verified 8/8
