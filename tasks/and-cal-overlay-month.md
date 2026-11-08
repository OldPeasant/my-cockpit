---
title: Android calendar: task overlay and month view
status: in_progress
owner: rudi/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda,android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda,android/app/src/main/res/values/strings_calendar_agenda.xml,android/app/src/main/res/values-de/strings_calendar_agenda.xml]
prio: 2
lease_until: 2026-11-08T16:43:33Z
---
## Goal
SPEC.md §6.3 and the phase 4 demo want the task/tickler overlay on Android too; web also has a month view.

## Acceptance criteria
- [ ] Day/week/agenda show due and scheduled tasks and ticklers as an overlay (toggle), tap opens the task
- [ ] A month view (dots or compact titles per day; tap a day opens the day view)
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 17:26+0100 by rudi/agent-1): Android overlay: all task marks (incl. due-with-time) are shown in the all-day strip of day/week (time in the label of the day view), not placed in the hour grid like the web; keeps the grid layout code untouched
