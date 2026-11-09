---
title: Reminders on tasks (web + Android)
status: in_progress
owner: dell26/agent-1
deps: [and-task-detail-dates, web-task-actions, and-links-panel, web-task-reminders, and-task-reminders]
touches: [web/src/app/features/gtd/task-detail,android/app/src/main/java/ch/sonensei/cockpit/gtd/task,android/app/src/test/java/ch/sonensei/cockpit/gtd/task,android/app/src/main/res/values/strings_task_detail.xml,android/app/src/main/res/values-de/strings_task_detail.xml,web/src/locale]
prio: 2
lease_until: 2026-11-09T00:25:05Z
subtasks: web-task-reminders, and-task-reminders
releases: 0
extended: 
---
## Goal
SPEC.md §4.6 and the phase 3 demo (step 5) expect reminders on tasks/ticklers; `POST /api/v1/reminders` exists
(item types incl. TASK) but no client creates one.

## Acceptance criteria
- [x] Web task detail: add/remove reminders (presets relative to due/scheduled date, or an exact date/time); existing reminders listed
  - verified (2026-11-09 01:07+0100 by dell26/agent-1): integration of web-task-reminders: npm --prefix web run test:ci -- --include='src/app/features/gtd/task-detail/**/*.spec.ts' --include='src/locale/**/*.spec.ts' on HEAD 66e86ae8d -> 9 files, 382 passed (task-reminders.spec: list, presets due/1h/1d/scheduled, exact datetime, remove); integration review 0 findings
- [x] Android task detail: the same; local alarms are scheduled through the existing reminder sync
  - verified (2026-11-09 01:07+0100 by dell26/agent-1): integration of and-task-reminders: android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.*' --tests 'ch.sonensei.cockpit.reminders.*' on HEAD 66e86ae8d -> 449 tests, 0 failures (TaskReminderRepositoryTest: ReminderSync.sync() after create/delete; presets match web per review)
- [~] A reminder fires as a notification on both platforms and opens the task (manually verified or skipped with reason)
  - skipped (2026-11-09 01:00+0100 by dell26/agent-1): No interactive browser (notification permission) and no Android emulator/device in this agent environment; firing/opening covered by unit tests: web core/notifications ReminderNotificationsService opens deepLink /tasks/{id}; Android OpenItemRequestTaskTest (TASK reminder -> TaskRoutes.detail). Same skip recorded on both subtasks.
- [x] New strings in English and German on both clients (`web/src/locale`, `res/values{,-de}/`)
  - verified (2026-11-09 01:00+0100 by dell26/agent-1): grep -c 'name="task_detail_remind' android res values{,-de}/strings_task_detail.xml -> 13/13; grep -c 'id="taskDetail.reminder' web/src/locale/messages{,.de}.xlf -> 13/13, all 13 German units have a target; locale spec + android lintDebug (no missing translations) run in check.sh
- [x] Unit tests cover the new behavior on each changed part; `sh scripts/check.sh` passes
  - verified (2026-11-09 01:07+0100 by dell26/agent-1): sh scripts/check.sh at dfa7863bd -> all checks passed; web 382 + android 449 focused tests pass; coverage web lines 100% / android 90.5% (reviews/task-reminders/tests.md)

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- split by dell26/agent-1: into web-task-reminders, and-task-reminders. No code yet. Split into web and Android parts: each is a full client feature plus its own quality gate, too big together for one session. The parent only integrates/verifies afterwards. Once they are done, this task is only integration: merge what is left on wip/task-reminders (if anything), verify the acceptance criteria end to end, done.
- ASSUMPTION (2026-11-09 01:00+0100 by dell26/agent-1): Integration task: no code of its own and no wip/task-reminders branch; the quality gate reviews the combined result of web-task-reminders + and-task-reminders on main, and the notification firing criterion stays skipped (needs a browser/device, owner to check manually)
