# Fix Report

Date: 2026-10-20

Task: gtd-recurrence — Recurring tasks

Scope: current task, finding CR-1 from `reviews/gtd-recurrence/review.md`

## Fixed findings

### CR-1 — Comment on the probe day contradicts the actual UNTIL behavior

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/TaskRecurrence.kt`

Problem:
The comment above `PROBE_DAY` gave "an UNTIL with a time" as an example of a rule the expansion probe rejects. ical4j accepts an UNTIL with a UTC time, and `TaskRecurrenceTests` asserts that `FREQ=DAILY;UNTIL=20261020T120000Z` is valid. What the probe really rejects is a time-of-day part such as `BYHOUR` (`TaskRecurrenceTests`, `FREQ=DAILY;BYHOUR=9` -> "is not a valid RRULE").

Fix:
Comment only, no behavior change. The example now reads "a time-of-day part like BYHOUR=9 cannot be applied to days".

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.gtd.TaskRecurrence*'` — Pass (BUILD SUCCESSFUL; TaskRecurrenceTests, TaskRecurrenceEdgeCaseTests, TaskRecurrenceWebTests: 36 tests, 0 failures, 0 skipped)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.gtd.TaskRecurrence*'` — Pass, 36 tests, 0 failures

Full test suite:
- Not run (comment-only change; the parent requested only the focused command)

Lint/type-check/build:
- `./gradlew ktlintCheck` (server) — Pass

`.taskcheck`:
- Not run (the parent runs it before `done`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
