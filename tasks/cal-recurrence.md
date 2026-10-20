---
title: Recurrence expansion
status: in_progress
owner: dell26/agent-3
deps: [cal-model]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence,server/src/test/kotlin/ch/sonensei/cockpit/calendar/recurrence]
prio: 1
lease_until: 2026-10-20T16:32:31Z
extended: dell26/agent-3
---
## Goal
Expand events into occurrences in a range (SPEC.md §6.1).

## Acceptance criteria
- [x] `OccurrenceExpander` (ical4j) expands RRULE/EXDATE/overrides in an event's time zone into instants for [from, to)
  - verified (2026-10-20 18:07+0200 by dell26/agent-3): cd server && ./gradlew test --offline --rerun --tests 'ch.sonensei.cockpit.calendar.recurrence.*' -> OccurrenceExpanderTests 13 + OccurrenceExpanderEdgeCaseTests 43 passed; OccurrenceExpander uses ical4j Recur in the event zone, applies EXDATE/overrides, filters to [from,to)
- [x] Tests: weekly across DST start/end (Europe/Zurich), all-day events, moved and cancelled occurrences, COUNT/UNTIL, monthly by weekday
  - verified (2026-10-20 18:07+0200 by dell26/agent-3): same run -> named tests pass: weekly across DST start/end Europe/Zurich, all-day series/single, moved+cancelled overrides, COUNT, UNTIL, monthly by weekday (OccurrenceExpanderTests.kt) plus DST-end edge cases
- [x] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.recurrence.*'` passes (Postgres via Testcontainers where persistence is involved)
  - verified (2026-10-20 18:07+0200 by dell26/agent-3): cd server && ./gradlew test --offline --rerun --tests 'ch.sonensei.cockpit.calendar.recurrence.*' -> BUILD SUCCESSFUL, 56 tests, 0 failures/errors/skips; expander is pure, no persistence so no Testcontainers involved

## Notes
- ASSUMPTION (2026-10-20 17:07+0200 by dell26/agent-3) [confirmed 2026-10-20 17:09+0200 by Peter Gürtler]: Expander is pure (no persistence): expand(candidates, OccurrenceRange(from,to,userZone)); all-day occurrences overlap by the days [fromDate,toDate) the range touches in the user's zone, consistent with findCandidatesInRange
- ASSUMPTION (2026-10-20 17:07+0200 by dell26/agent-3) [confirmed 2026-10-20 17:08+0200 by Peter Gürtler]: Occurrences of a timed series keep the master's exact duration and wall-clock start in the event's zone (UTC if none); ambiguous local times at DST end use the earlier (summer) offset, nonexistent ones are shifted forward (java.time rules)
- ASSUMPTION (2026-10-20 17:07+0200 by dell26/agent-3) [confirmed 2026-10-20 17:08+0200 by Peter Gürtler]: A non-cancelled override is shown at its own time even when its recurrence id is also an EXDATE or no longer matches the RRULE, and overrides whose master is not among the candidates are shown standalone (Google-style exceptions); DTSTART is always the first occurrence
- ASSUMPTION (2026-10-20 17:21+0200 by dell26/agent-3): A series whose RRULE ical4j cannot parse or expand is shown with its first occurrence only (DTSTART), with a WARN log naming the event id, so one bad synced rule doesn't break the whole range
- ASSUMPTION (2026-10-20 17:29+0200 by dell26/agent-3): A DATE-only UNTIL on a timed series (invalid per RFC 5545 but emitted by providers) includes that whole day in the event's zone
- ASSUMPTION (2026-10-20 17:42+0200 by dell26/agent-3): An override whose master is soft-deleted (or absent from the input) is shown standalone by OccurrenceExpander; cascading series deletion to overrides is left to the event-editing/sync tasks.
- extended by dell26/agent-3: Implementation, tests, test report and third review (0 findings) are committed; server code is identical to the reviewed revision e6757fa8 (rebased); scripts/check.sh passes. Left: tasks.py verify for criteria 1-3 (rerun recurrence tests), write reviews/cal-recurrence/quality.json (reviewed_revision = current HEAD-equivalent code), commit, tasks.py done.
