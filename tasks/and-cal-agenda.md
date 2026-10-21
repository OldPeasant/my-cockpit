---
title: Android calendar agenda and day/week
status: in_progress
owner: dell26/agent-1
deps: [and-login,cal-local-api]
touches: [android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda,android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda]
prio: 2
lease_until: 2026-10-21T14:05:32Z
---
## Goal
Calendar on the phone (SPEC.md §9.2 item 4).

## Acceptance criteria
- [ ] Agenda list (infinite scroll by range) and day/week views; calendar visibility
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*'` passes, covering range loading and grouping by day (Robolectric/Compose UI tests for screens)

## Notes
- ASSUMPTION (2026-10-21 15:35+0200 by dell26/agent-1): Agenda offline Room cache (SPEC 9.3) is not in this task's acceptance criteria; deferred to new task and-cal-cache. Offline, the agenda shows the offline banner with what is already loaded in memory.
