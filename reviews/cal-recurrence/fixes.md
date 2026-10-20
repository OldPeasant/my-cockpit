# Fix Report

Date: 2026-10-20

Task: cal-recurrence - Recurrence expansion

Scope: current task. Findings TW-1 (`reviews/cal-recurrence/tests.md`) and CR-1, CR-2, CR-3 (`reviews/cal-recurrence/review.md`), on top of HEAD `3517e09`. Files changed: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpander.kt` and `server/src/test/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpanderEdgeCaseTests.kt`.

## Fixed findings

### TW-1 / CR-2 - Orphan overrides ignore CANCELLED

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpander.kt`

Problem:
In `expand`, an override whose master is not among the inputs was shown standalone even if its status was CANCELLED. `expandSeries` already drops cancelled overrides, so the result depended on whether the master was among the candidates.

Fix:
The orphan path now applies the same `status != EventStatus.CANCELLED` filter before it builds the occurrence. The test-writer's failing test `cancelled override is never shown, also when its master is not among the candidates` is unchanged and now passes.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.recurrence.*'` - Pass (44 tests)

### CR-1 - One unparsable RRULE makes the whole range expansion throw

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpander.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpanderEdgeCaseTests.kt`

Problem:
If ical4j could not parse or expand an RRULE, it threw from `Recur(...)` or `getDates`. Examples: `FREQ=FORTNIGHTLY`, a blank rule, `BYDAY=XX`, or a date-only UNTIL on a timed series. The exception aborted `expand` for every event in the range.

Fix:
This follows the recorded assumption. Each series' ical4j call is now wrapped in `recurOrEmpty`, which catches `RuntimeException`. On failure it returns no generated dates, so only DTSTART remains. That first occurrence is still filtered by range overlap, and overrides and EXDATEs are applied as usual. It logs one WARN line with the event id and the exception's simple class name. It does not log the rule, title, description or stack trace. The logger uses the project idiom `LoggerFactory.getLogger(javaClass)`.

Bonus: a DATE-only `UNTIL=yyyyMMdd` on a timed series is now rewritten to the floating `UNTIL=yyyyMMddT235959`, which ical4j evaluates in the event's zone. The series then runs through the end of that day instead of falling back. The rewrite is a single anchored regex on the `UNTIL` part and applies only to timed series. All-day series are unchanged because ical4j already handles them.

Regression tests added:
- `a series with an invalid rule keeps its first occurrence and does not break the other events`: covers `FREQ=FORTNIGHTLY`, blank and `BYDAY=XX`, each with a valid single event alongside, inside and outside the range.
- `an all-day series with an invalid rule keeps its first occurrence`
- `overrides still apply to a series with an invalid rule`
- `a date-only UNTIL on a timed series includes that whole day in the event's zone`: covers UNTIL both at the end and at the start of the rule, and a 23:30 start.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.recurrence.*'` - Pass. The WARN lines in the test output contain only the event id and the exception class.
- Mutation check: with the UNTIL rewrite removed, the date-only UNTIL test fails.

### CR-3 - Ambiguous DST-end times resolve by the seed's offset

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpander.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpanderEdgeCaseTests.kt`

Problem:
ical4j keeps the seed's offset for an ambiguous local time. A series seeded in winter time therefore placed the 2026-10-25 02:30 Europe/Zurich occurrence at the later instant (01:30Z). That contradicts the recorded assumption (earlier offset) and RFC 5545 §3.3.5.

Fix:
Generated timed dates are now normalised with `ZonedDateTime.withEarlierOffsetAtOverlap()`. The one exception is the date with the seed's own local date-time, which maps to the seed itself. This keeps DTSTART exactly as stored, even when it is the later of two ambiguous times, and does not create a duplicate first occurrence. Nonexistent times (DST start) are unaffected.

Regression tests added:
- `ambiguous time at DST end uses the earlier offset also for a series starting in winter time`: a series starting 2026-01-04T02:30 weekly on Sunday puts the 2026-10-25 occurrence at 2026-10-25T00:30:00Z, and the occurrence id matches.
- `a series starting at the later of two ambiguous times keeps its start as the first occurrence`

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.recurrence.*'` - Pass
- Mutation check: with the normalisation removed, the winter-seed test fails.

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` - applied, then `ktlintCheck` (part of `check`) passed
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.recurrence.*'` - Pass (44 tests, 0 failures)

Full test suite:
- `cd server && ./gradlew check` (ktlint and all server tests) - BUILD SUCCESSFUL

Lint/type-check/build:
- Covered by `./gradlew check` above (ktlintCheck, compile)

`.taskcheck`:
- `sh scripts/check.sh` - Pass ("all checks passed": TFP gate, server, web, android). The web budget warning (initial bundle 501.24 kB over 500 kB) existed before this change and is unrelated: the web part was not touched.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
