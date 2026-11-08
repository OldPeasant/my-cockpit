---
title: Android task detail: scheduled, tickler, recurrence, all lists
status: todo
owner:
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/task,android/app/src/test/java/ch/sonensei/cockpit/gtd/task,android/app/src/main/res/values/strings_task_detail.xml,android/app/src/main/res/values-de/strings_task_detail.xml]
prio: 2
---
## Goal
Android task detail shows scheduled/tickler/recurrence read-only (`TaskDetailScreen.kt:553,554,574`) and can only
move to Next/Someday/Waiting. Web edits all of them (`task-detail.html:124-330`).

## Acceptance criteria
- [ ] Scheduled date and tickler date can be set and cleared (date picker with quick chips like inbox processing)
- [ ] The list can be changed to any of Inbox/Next/Waiting/Scheduled/Someday; waiting-since is editable
- [ ] Recurrence editor: presets (daily, weekdays, weekly, monthly, yearly), interval, FIXED vs AFTER_COMPLETION, keep an existing custom RRULE shown as text
- [ ] Checklist items can be reordered (long-press drag + accessibility actions)
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
