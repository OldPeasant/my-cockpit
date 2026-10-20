# Fix Report

Date: 2026-10-20

Task: cal-recurrence - Recurrence expansion

Scope: Current task, second fix round. This round covers finding CR-1 (P3) of the second review in `reviews/cal-recurrence/review.md`, on top of HEAD `f9c1e1cf`. The first round's fixes (TW-1, and CR-1, CR-2 and CR-3 of the first review) are already in earlier commits (b902721d, 62bd3ae0). This round changes `server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpander.kt` and `server/src/test/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpanderEdgeCaseTests.kt`.

## Fixed findings

### CR-1 - An occurrence at an ambiguous DST-end time is lost when the range ends inside the repeated hour

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpander.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/recurrence/OccurrenceExpanderEdgeCaseTests.kt`

Problem:
ical4j selects candidates up to `range.to` using the offset of the series seed. `withEarlierOffsetAtOverlap()` can then move a candidate up to one hour earlier. Take a weekly 02:30 Europe/Zurich series seeded in winter time (DTSTART 2026-01-04T02:30, `FREQ=WEEKLY;BYDAY=SU`) and a range ending inside the repeated hour on 2026-10-25, for example at 01:00Z. ical4j's candidate is 01:30Z, which is past the range end, so it is never generated. The normalised occurrence, which starts at 00:30Z, overlaps the range but was not returned. I confirmed this first: the new regression test failed before the fix ("38 tests completed, 1 failed").

Fix:
For timed series, the period end passed to ical4j is now `range.to + 2h` (`DST_END_MARGIN`). Two hours is more than the largest DST offset jump in any zone. The existing exact `overlaps(...)` filter in `expandSeries` still removes every occurrence outside the range, so results outside the edge case do not change.

The start needs no margin: `withEarlierOffsetAtOverlap()` never moves a candidate later, so a candidate ical4j leaves out before `periodStart` would only move further before the range. COUNT and UNTIL are unaffected, because COUNT counts from DTSTART and UNTIL is part of the rule, not of the query period.

New test: `an occurrence at the ambiguous DST-end time is found when the range ends inside the repeated hour`. It uses the winter-time seed above and three range ends inside the repeated hour (00:45Z, 01:00Z, 01:30Z), and expects exactly the 00:30Z occurrence each time.

Validation:
- `./gradlew test --offline --rerun --tests 'ch.sonensei.cockpit.calendar.recurrence.OccurrenceExpanderEdgeCaseTests'` without the fix - Fail (1 of 38 tests failed, the new test, as expected)
- `cd server && ./gradlew ktlintFormat` - Pass (no further changes)
- `cd server && ./gradlew test --offline --rerun --tests 'ch.sonensei.cockpit.calendar.recurrence.*'` with the fix - Pass (OccurrenceExpanderEdgeCaseTests 38, OccurrenceExpanderTests 13; 0 failures, errors or skips)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --offline --rerun --tests 'ch.sonensei.cockpit.calendar.recurrence.*'` - Pass (51 tests)

Full test suite:
- `cd server && ./gradlew check --offline` - BUILD SUCCESSFUL (server lint and all server tests)

Lint/type-check/build:
- `cd server && ./gradlew ktlintFormat` and `./gradlew check --offline` (includes ktlintCheck) - Pass

`.taskcheck`:
- Not run by the fixer: only server code changed, and `./gradlew check` passed. The parent worker runs `.taskcheck` before `done`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
