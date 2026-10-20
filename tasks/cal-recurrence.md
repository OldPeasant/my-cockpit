---
title: Recurrence expansion
status: in_progress
owner: dell26/agent-3
deps: [cal-model]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence,server/src/test/kotlin/ch/sonensei/cockpit/calendar/recurrence]
prio: 1
lease_until: 2026-10-20T15:26:58Z
---
## Goal
Expand events into occurrences in a range (SPEC.md §6.1).

## Acceptance criteria
- [ ] `OccurrenceExpander` (ical4j) expands RRULE/EXDATE/overrides in an event's time zone into instants for [from, to)
- [ ] Tests: weekly across DST start/end (Europe/Zurich), all-day events, moved and cancelled occurrences, COUNT/UNTIL, monthly by weekday
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.recurrence.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
- ASSUMPTION (2026-10-20 17:07+0200 by dell26/agent-3): Expander is pure (no persistence): expand(candidates, OccurrenceRange(from,to,userZone)); all-day occurrences overlap by the days [fromDate,toDate) the range touches in the user's zone, consistent with findCandidatesInRange
- ASSUMPTION (2026-10-20 17:07+0200 by dell26/agent-3): Occurrences of a timed series keep the master's exact duration and wall-clock start in the event's zone (UTC if none); ambiguous local times at DST end use the earlier (summer) offset, nonexistent ones are shifted forward (java.time rules)
- ASSUMPTION (2026-10-20 17:07+0200 by dell26/agent-3) [confirmed 2026-10-20 17:08+0200 by Peter Gürtler]: A non-cancelled override is shown at its own time even when its recurrence id is also an EXDATE or no longer matches the RRULE, and overrides whose master is not among the candidates are shown standalone (Google-style exceptions); DTSTART is always the first occurrence
