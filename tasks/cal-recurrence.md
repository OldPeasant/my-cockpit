---
title: Recurrence expansion
status: in_progress
owner: dell26/agent-3
deps: [cal-model]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence,server/src/test/kotlin/ch/sonensei/cockpit/calendar/recurrence]
prio: 1
lease_until: 2026-10-20T16:12:20Z
---
## Goal
Expand events into occurrences in a range (SPEC.md §6.1).

## Acceptance criteria
- [ ] `OccurrenceExpander` (ical4j) expands RRULE/EXDATE/overrides in an event's time zone into instants for [from, to)
- [ ] Tests: weekly across DST start/end (Europe/Zurich), all-day events, moved and cancelled occurrences, COUNT/UNTIL, monthly by weekday
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.recurrence.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
- ASSUMPTION (2026-10-20 17:07+0200 by dell26/agent-3) [confirmed 2026-10-20 17:09+0200 by Peter Gürtler]: Expander is pure (no persistence): expand(candidates, OccurrenceRange(from,to,userZone)); all-day occurrences overlap by the days [fromDate,toDate) the range touches in the user's zone, consistent with findCandidatesInRange
- ASSUMPTION (2026-10-20 17:07+0200 by dell26/agent-3) [confirmed 2026-10-20 17:08+0200 by Peter Gürtler]: Occurrences of a timed series keep the master's exact duration and wall-clock start in the event's zone (UTC if none); ambiguous local times at DST end use the earlier (summer) offset, nonexistent ones are shifted forward (java.time rules)
- ASSUMPTION (2026-10-20 17:07+0200 by dell26/agent-3) [confirmed 2026-10-20 17:08+0200 by Peter Gürtler]: A non-cancelled override is shown at its own time even when its recurrence id is also an EXDATE or no longer matches the RRULE, and overrides whose master is not among the candidates are shown standalone (Google-style exceptions); DTSTART is always the first occurrence
- ASSUMPTION (2026-10-20 17:21+0200 by dell26/agent-3): A series whose RRULE ical4j cannot parse or expand is shown with its first occurrence only (DTSTART), with a WARN log naming the event id, so one bad synced rule doesn't break the whole range
- ASSUMPTION (2026-10-20 17:29+0200 by dell26/agent-3): A DATE-only UNTIL on a timed series (invalid per RFC 5545 but emitted by providers) includes that whole day in the event's zone
- ASSUMPTION (2026-10-20 17:42+0200 by dell26/agent-3): An override whose master is soft-deleted (or absent from the input) is shown standalone by OccurrenceExpander; cascading series deletion to overrides is left to the event-editing/sync tasks.
