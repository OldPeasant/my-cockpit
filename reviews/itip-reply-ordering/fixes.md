# Fix Report

Date: 2026-10-26

Task: itip-reply-ordering - Ignore stale iTIP REPLYs across messages (per-attendee answered-at)

Scope: current task; findings CR-1 (P2) and CR-2 (P3) of `reviews/itip-reply-ordering/review.md`, both accepted by the parent worker (CR-1 with option (a)).

## Fixed findings

### CR-1 — A late older occurrence REPLY overwrote the status a newer series REPLY set on that override (and the reverse)

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip/ItipReplyProcessor.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/itip/ItipReplyProcessorTests.kt`

Problem:
Stamps were compared only per RECURRENCE-ID. A series REPLY sets the master and all overrides, so a late, older occurrence REPLY (no occurrence stamp yet) was applied and regressed the override; likewise a late, older series REPLY overwrote an override that a newer occurrence REPLY had answered.

Fix:
Stamps stay per RECURRENCE-ID (no schema change, no migration change). `takeStamp` now also loads the attendee's series stamp for an occurrence REPLY and ignores the REPLY (no stamp recorded) when it is strictly older than either the occurrence stamp or the series stamp; an equal one is still applied. For a series REPLY, `apply` skips every override whose own occurrence stamp is strictly newer than the REPLY (new helper `answeredLater`); the master and the other overrides are still updated and the series stamp advances. Comparison is factored into `isOlder`. Class KDoc updated.
Tests: the old test "REPLYs are ordered per occurrence, per event and per attendee" (which asserted the old behavior with an older occurrence REPLY after a newer series REPLY) is replaced by "REPLYs are ordered per occurrence and per event" (now uses a newer occurrence REPLY). New tests: "an occurrence REPLY older than the last series REPLY is ignored, as the series answer set the occurrence too" (lower SEQUENCE, and same SEQUENCE with older DTSTAMP, both ignored, no occurrence stamp stored; an equal one is taken) and "a series REPLY leaves an override alone whose own answer is newer, and sets the others" (older series REPLY sets only the master; a newer one sets the override too).

Validation:
- `cd server && ./gradlew test --tests '*ItipReply*' --tests '*CalendarBackup*' --tests '*ItipMessages*'` — Pass (ItipReplyProcessorTests 18, ItipReplyStampPersistenceTests 9, CalendarBackupContributorTests 24, ItipMessagesTests 26; 0 failures)
- `cd server && ./gradlew integrationTest --tests '*ItipGreenMail*'` — Pass (34 tests, 0 failures)

### CR-2 — A REPLY without SEQUENCE was ordered below SEQUENCE:0

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip/ItipMessages.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/itip/ItipMessagesTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/itip/ItipReplyStampPersistenceTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/itip/ItipReplyProcessorTests.kt`

Problem:
`ItipReply.ORDER` used `nullsFirst()` for SEQUENCE, so an answer from a client that omits SEQUENCE lost against any earlier `SEQUENCE:0` answer whatever its DTSTAMP, and stayed ignored across messages.

Fix:
`ItipReply.ORDER` is now `compareBy { it.sequence ?: 0 }.thenBy(nullsFirst()) { it.stamp }` (RFC 5545 §3.8.7.4 default 0); a missing DTSTAMP still counts as oldest. The stored stamp keeps the raw (possibly null) SEQUENCE; it is compared through the same comparator. This applies both within one message and across messages.
Tests: ItipMessagesTests ordering test renamed to "answers are ordered by SEQUENCE (0 when missing), then DTSTAMP (missing first)" with two extra cases (`null`/late after `0`/late, `0`/early before them). ItipReplyStampPersistenceTests "a REPLY without SEQUENCE and DTSTAMP is taken first, then loses against any REPLY that has them" (which asserted "a missing SEQUENCE counts as oldest, whatever the DTSTAMP") is replaced by "a REPLY without SEQUENCE counts as SEQUENCE 0, and one without DTSTAMP as older than one with it" against Postgres. New unit test "a REPLY without SEQUENCE counts as SEQUENCE 0 across messages".

Validation:
- same commands as CR-1 — Pass

## Not fixed

None.

## Other changes

- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/backup/CalendarBackupContributorTests.kt`: `./gradlew ktlintFormat` wrapped an over-long constructor call in the task's own backup test (formatting only, no behavior change).

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` — done; `./gradlew ktlintCheck` — Pass
- `cd server && ./gradlew test --tests '*ItipReply*' --tests '*CalendarBackup*' --tests '*ItipMessages*'` — Pass (77 tests, 0 failures)
- `cd server && ./gradlew integrationTest --tests '*ItipGreenMail*'` — Pass (34 tests, 0 failures; Testcontainers/GreenMail)

Full test suite:
- Not run (the parent worker runs `./gradlew check` and `.taskcheck`, as instructed)

Lint/type-check/build:
- `./gradlew ktlintCheck` — Pass; compile succeeded. The compiler warning "Unnecessary non-null assertion" at `ItipReplyProcessor.kt` (`tx.execute { apply(reply) }!!`) predates these fixes and was left unchanged.

`.taskcheck`:
- Not run (parent worker runs it)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
