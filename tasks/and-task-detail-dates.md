---
title: Android task detail: scheduled, tickler, recurrence, all lists
status: in_progress
owner: rudi/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/task,android/app/src/test/java/ch/sonensei/cockpit/gtd/task,android/app/src/main/res/values/strings_task_detail.xml,android/app/src/main/res/values-de/strings_task_detail.xml]
prio: 2
lease_until: 2026-11-08T09:49:51Z
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
- ASSUMPTION (2026-11-08 10:27+0100 by rudi/agent-1): Scheduled date and SCHEDULED list are coupled in the Android form: picking a scheduled date puts the task on Scheduled, clearing it moves a scheduled task to Next, choosing another list drops the date (server only allows scheduledDate on SCHEDULED)
- ASSUMPTION (2026-11-08 10:27+0100 by rudi/agent-1): Android form saves like web: one /move (list + tickler, always resending the tickler since an omitted one clears it) then a PATCH of the remaining fields; moving to Waiting in the form requires a person (as the existing Waiting dialog); waiting-since is only editable for a task already on Waiting
- ASSUMPTION (2026-11-08 10:27+0100 by rudi/agent-1): Recurrence presets on Android: none/daily/weekdays/weekly/monthly/yearly/custom with an interval (INTERVAL=n); any other rule (extra parts, other BYDAY) is kept as a custom rule shown as editable text; custom rules get only a FREQ presence check client-side, the server validates the rest
- ASSUMPTION (2026-11-08 10:27+0100 by rudi/agent-1): Checklist reorder on Android: long-press anywhere on a row and drag (by whole rows), plus Move up/Move down accessibility custom actions; positions saved as 0..n like the web
