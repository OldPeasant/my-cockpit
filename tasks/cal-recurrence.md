---
title: Recurrence expansion
status: todo
owner:
deps: [cal-model]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence,server/src/test/kotlin/ch/sonensei/cockpit/calendar/recurrence]
prio: 1
---
## Goal
Expand events into occurrences in a range (SPEC.md §6.1).

## Acceptance criteria
- [ ] `OccurrenceExpander` (ical4j) expands RRULE/EXDATE/overrides in an event's time zone into instants for [from, to)
- [ ] Tests: weekly across DST start/end (Europe/Zurich), all-day events, moved and cancelled occurrences, COUNT/UNTIL, monthly by weekday
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.recurrence.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
