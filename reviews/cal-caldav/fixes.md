# Fix Report

Date: 2026-10-21

Task: cal-caldav - CalDAV adapter

Scope: current task. Findings F1 and F2 come from the test-writer (two failing unit tests in `CalDavEventMappingTests`). F3 and F4 are review findings CR-2 and CR-3 from `reviews/cal-caldav/review.md`. Code base: HEAD bc85703d plus the uncommitted changes listed below. The parent commits them.

## Fixed findings

### F1 - DURATION days and times were mixed up (24 hours read as a calendar day)

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavEventMapping.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavEventMappingTests.kt`

Problem:
`duration()` returned a `java.time.Duration`, so `PT24H` and `P1D` were the same value, and `plus()` treated 24 hours as one calendar day. RFC 5545 §3.3.6 says only days and weeks are nominal. Hours, minutes and seconds are exact.

Fix:
`duration()` now returns a new value type, `CalDavEventMapping.IcsDuration(days: Long, time: Duration)`. Both parts carry the sign. The callers use it as follows:
- DTEND from DURATION: `plusDays(days)` in the event's zone, then `plus(time)`.
- All-day duration: `days + time.toDays()`, the same result as before for day-only durations.
- VALARM TRIGGER: `isPositive` and `toMinutes()`, with a day counted as 1440 minutes as before.

The existing `iCalendar durations` unit test compared against `java.time.Duration`. It now compares against `IcsDuration`, because the return type changed. I added `PT24H` and `-P1DT1H` cases. I did not change the test-writer's DST test.

Validation:
- `./gradlew test --tests '*CalDavEventMappingTests*'` - Pass (38/38, including "DURATION days are calendar days in the event's zone, hours are exact, across the end of summer time")

### F2 - Generated VTIMEZONE RRULEs missed the wall-clock transition day

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavEventMapping.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavEventMappingTests.kt`

Problem:
`rrule(rule)` used the rule's nominal day and weekday, but DTSTART is `dateTimeBefore` (local wall time). That can fall on another day:
- times defined in UTC or STANDARD time (America/Nuuk, Chile/EasterIsland: the day before);
- rules that roll over into the next month (Africa/Cairo: Friday on or after 26 October, up to 1 November; the old code cut that range at the 31st).

Fix:
Each transition rule is now turned into one or more observances:
- The day shift between the rule's own date (computed the way `ZoneOffsetTransitionRule.createTransition` does) and the wall-clock date is applied to BYDAY and BYMONTHDAY.
- Each day of the shifted range is mapped to the month it falls in. When the range reaches into a neighbouring month (positive or negative BYMONTHDAY), each month gets its own observance with its own RRULE. A weekday occurs once in a 7-day range, so exactly one of them matches each year. Each observance's DTSTART is the first transition (from 1970) that falls in its month.
- When the month of a day depends on leap years, the transitions from 1970 to 2100 are listed in RDATE instead. Only February ranges that cross into March, or March ranges that cross back, have this problem. No current JDK zone does.
- I added `vtimezone(tzid, rules)` so a test can use custom ZoneRules. `vtimezone(zone)` delegates to it.

Test change, kept as strict as before: "the RRULEs of every generated VTIMEZONE give the transitions of the Java zone rules" still checks every zone for 2027..2040 with ical4j. It now checks per transition rule: the onsets of all observances that belong to the rule (RRULE expanded by ical4j, plus RDATEs) must be exactly that year's transition. It also asserts that every observance starts at a transition of exactly one rule (DTSTART, TZOFFSETFROM and TZOFFSETTO match).

New tests:
- Cairo's two STANDARD observances (October 26..31 and November 1);
- Nuuk's shifted `BYDAY=SA;BYMONTHDAY=24..30` at 23:00;
- a fictitious leap-year-dependent rule that falls back to RDATE, checked for 1971..2100.

Validation:
- `./gradlew test --tests '*CalDavEventMappingTests*'` - Pass (38/38, including the all-zones test)

### F3 (CR-2) - An out-of-range DURATION or TRIGGER blocked the calendar's sync

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavEventMapping.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavCalendarAdapter.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavEventMappingTests.kt`

Problem:
`Duration.ofDays(...)` threw `ArithmeticException` on values like `P200000000000000D`. No catch handled it, so the exception escaped `fetchEvents`. Digit strings longer than a Long were silently read as 0.

Fix:
- `duration()` uses exact arithmetic. On overflow (`ArithmeticException` or `NumberFormatException`) it returns null.
- A DURATION that is used (no DTEND) but cannot be read now makes the VEVENT unreadable, so it is skipped and counted like other unreadable VEVENTs. This also applies to malformed DURATION values, which before gave a zero-length event.
- Day counts that parse but exceed the date range throw `DateTimeException` or `ArithmeticException`. `readOrNull` now also catches `ArithmeticException`.
- The write path's `answer()` maps `ArithmeticException` to a `CalendarProviderException` (502 instead of 500), like the other read errors.
- A TRIGGER that is out of range, or whose minutes do not fit an Int (`Math.toIntExact`), is no reminder. That alarm is kept unchanged on write, like other alarms the cockpit cannot show.

New test: "durations out of range are unreadable - such a DURATION skips its VEVENT, such a TRIGGER is no reminder". It covers timed and all-day events.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.caldav.*'` - Pass

### F4 (CR-3) - A 403 was documented as throwing, and discovery reported it as "No CalDAV service found"

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavClient.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavCalendarAdapter.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavCalendarAdapterTests.kt`

Problem:
The class doc of `CalDavClient` claimed that 403 throws, but `send` throws only for 401 and 5xx. In discovery, a 403 became "No CalDAV service found at the source's URL".

Fix:
- `send` was not changed. A sync-collection REPORT legitimately answers 403 for an invalid sync token (RFC 6578), and the adapter falls back to a full listing on that, so making 403 throw there would break the fallback. I corrected the class doc instead.
- The adapter's depth-0 PROPFIND helper (used for the principal and for sync-token/ctag) now throws `CalendarProviderException("<what>: the CalDAV server denied access (HTTP 403), the user lacks permission")`. The message has no password and no response body.
- For the first discovery step on a bare host, a 403 at "/" still tries `/.well-known/caldav`. The 403 is reported only when that gives nothing either.

New tests:
- "403 during discovery says access was denied, without the password or the body" (at the start URL and at the principal);
- "a bare host that refuses its root is still looked up at the well-known URL, a 403 there too is denied access".

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.caldav.*'` - Pass (CalDavCalendarAdapterTests 32/32)

## Not fixed

None of the supplied findings remain open. Two failures in the full `./gradlew check` run are outside these findings. Both come from code already at HEAD, not from these fixes:

### PRE-1 - Spring context of the calendar sync tests fails: "More than one calendar adapter for [CALDAV]"

Status:
Out of scope. Reported to the parent.

Reason:
- 73 tests fail: `CalendarSyncTests` 17, `CalendarSyncOverrideTests` 25, `CalendarSyncEdgeCaseTests` 31.
- Root cause: `IllegalArgumentException: More than one calendar adapter for [CALDAV]` from `CalendarProviderRegistry.<init>`. The real `@Component` `CalDavCalendarAdapter` from task commit 41595fe4 and the test `FakeCalendarAdapter` (also `sourceType = CALDAV`, registered by `FakeCalendarAdapterConfiguration`) are both injected into the regular `CalendarProviderRegistry` bean. The `@Primary` fake-only registry does not stop that bean from being built.
- The fix belongs in `server/src/test/kotlin/ch/sonensei/cockpit/calendar/sync/` or `calendar/sync/CalendarProviderRegistry.kt`. Both are outside this task's `touches` and outside the supplied findings.
- `.taskcheck` will fail until this is resolved. The parent must decide on it.

### PRE-2 - `ClockConfigurationTests` "the application clock is the system clock in UTC" is flaky

Status:
Out of scope.

Reason:
The application clock returns instants truncated to microseconds, and the test compares them with a nanosecond `Instant.now()` taken just before (`2026-10-21T14:02:21.704032Z should be >= 2026-10-21T14:02:21.704032160Z`). It fails in the full run whenever both readings fall in the same microsecond, and it passed when run alone. Module `core.persistence`, unrelated to this task.

## Validation summary

Focused tests:
- `./gradlew test --tests '*CalDavEventMappingTests*'` - Pass, 38/38 (the two test-writer tests included)
- `./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.caldav.*'` - Pass: CalDavCalendarAdapterTests 32/32, CalDavEventMappingTests 38/38, ICalendarTests 15/15

Full test suite:
- `./gradlew ktlintFormat check` - Fail: 3377 tests, 74 failed. All 74 are PRE-1 (73) and PRE-2 (1), none in `calendar.infra.caldav`.

Lint/type-check/build:
- `./gradlew check -x test` (ktlint main, test and script source sets, compile) - Pass

`.taskcheck`:
- Not run separately. Its server part (`./gradlew check`) fails because of PRE-1 and PRE-2.

Radicale integration tests (`./gradlew integrationTest --tests '*CalDav*'`):
- Not run. These changes affect duration parsing, VTIMEZONE generation and 403 handling in discovery, and are covered by the unit tests above.

Remaining unresolved findings:
0 of the supplied findings. 2 failures outside the scope (PRE-1, PRE-2) are reported to the parent.

## Final status

All validated findings are fixed and their focused validation passes, but `./gradlew check` still fails. The cause is a failure already at HEAD (PRE-1) that this task introduced but that lies outside its `touches`, plus an unrelated flaky test (PRE-2). The parent must decide on PRE-1.
