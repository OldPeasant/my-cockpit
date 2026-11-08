---
title: Android: convert tasks to notes and events
status: in_progress
owner: dell26/agent-3
deps: [and-task-detail-dates,and-inbox-rows]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/task,android/app/src/test/java/ch/sonensei/cockpit/gtd/task,android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox,android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox,android/app/src/main/res/values/strings_task_detail.xml,android/app/src/main/res/values-de/strings_task_detail.xml,android/app/src/main/res/values/strings_inbox.xml,android/app/src/main/res/values-de/strings_inbox.xml]
prio: 2
lease_until: 2026-11-08T15:21:20Z
---
## Goal
Web converts a task to a note (reference) or an event from task detail and inbox processing (`gtd-convert-note`,
`gtd-convert-event` server endpoints). Android has neither (`InboxActions.kt:15`).

## Acceptance criteria
- [ ] Inbox processing has "To reference" (converts to a note, opens it or shows a link) and "To calendar" (dialog: calendar, start, duration/all-day)
- [ ] Task detail has the same two actions in its menu
- [ ] After converting, the user sees where the item went with an "Open" action
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
