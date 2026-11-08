---
title: Reminders on tasks (web + Android)
status: in_progress
owner: dell26/agent-1
deps: [and-task-detail-dates, web-task-actions, and-links-panel, web-task-reminders, and-task-reminders]
touches: [web/src/app/features/gtd/task-detail,android/app/src/main/java/ch/sonensei/cockpit/gtd/task,android/app/src/test/java/ch/sonensei/cockpit/gtd/task,android/app/src/main/res/values/strings_task_detail.xml,android/app/src/main/res/values-de/strings_task_detail.xml,web/src/locale]
prio: 2
lease_until: 2026-11-09T00:19:51Z
subtasks: web-task-reminders, and-task-reminders
releases: 0
extended: 
---
## Goal
SPEC.md §4.6 and the phase 3 demo (step 5) expect reminders on tasks/ticklers; `POST /api/v1/reminders` exists
(item types incl. TASK) but no client creates one.

## Acceptance criteria
- [ ] Web task detail: add/remove reminders (presets relative to due/scheduled date, or an exact date/time); existing reminders listed
- [ ] Android task detail: the same; local alarms are scheduled through the existing reminder sync
- [ ] A reminder fires as a notification on both platforms and opens the task (manually verified or skipped with reason)
- [ ] New strings in English and German on both clients (`web/src/locale`, `res/values{,-de}/`)
- [ ] Unit tests cover the new behavior on each changed part; `sh scripts/check.sh` passes

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- split by dell26/agent-1: into web-task-reminders, and-task-reminders. No code yet. Split into web and Android parts: each is a full client feature plus its own quality gate, too big together for one session. The parent only integrates/verifies afterwards. Once they are done, this task is only integration: merge what is left on wip/task-reminders (if anything), verify the acceptance criteria end to end, done.
