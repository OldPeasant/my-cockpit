# Fix Report

Date: 2026-10-21

Task: cal-ics - ICS feed adapter

Scope: Findings CR-1, CR-2, CR-4 and CR-5 from `reviews/cal-ics/review.md`, plus the failing test-writer test `IcsEventMappingTests` "a day in DURATION is a calendar day across a daylight saving change (RFC 5545 3.3.6)". Base commit a8fe02662740b1b60542fb4cd96988739c8533ef. Fix commit 139f580a.

## Fixed findings

### CR-1 - A 2xx response that is not an iCalendar file deletes every event of the feed

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsParser.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedAdapter.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsParserTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedAdapterTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedSyncTests.kt`

Problem:
The parser never fails, so an empty, HTML or cut-off 2xx body was mapped to zero events with `complete = true`, and the engine deleted every synced event of the feed and its links.

Fix:
`IcsComponent` now has `ended`, set only when a component is closed by its own `END`. The adapter throws `CalendarProviderException("The feed is not a complete iCalendar file")` unless there is at least one `VCALENDAR` root and every `VCALENDAR` root was ended. A complete but empty `VCALENDAR` is still a complete listing, so it can still clear the calendar. Tests: the parser `ended` flag (closed at end of text, closed by an outer END, own END); the adapter refuses empty, HTML, a loose VEVENT, two truncation points, and a cut-off second calendar; an empty VCALENDAR is complete with no events; WireMock sync regression (good sync, then 200 HTML gives events kept, `lastError` "not a complete iCalendar file" without the URL token).

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.ics.*'` - Pass

### CR-2 - The request timeout does not limit reading the body

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedClient.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedProperties.kt`

Problem:
`HttpRequest.timeout` only covers the wait for the response headers. With `BodyHandlers.ofInputStream()` a server that trickles the body kept the fetch, and so the whole calendar sync, running with no deadline.

Fix:
`fetch` sets one deadline for the whole fetch (`timeout` from the first request through redirects to the end of the last body). Each request uses `sendAsync` with the remaining time as the request timeout and waits with `future.get(remaining)`. On timeout or interrupt the future is cancelled, which cancels the exchange. Bodies of 2xx answers go to a new `LimitedBody` subscriber that collects at most `max-size` bytes. Over the limit, or when a `Content-Length` larger than the limit is announced for a non-gzip body, it fails with "larger than" and cancels the download. Bodies of other statuses are discarded. A gzip body is decompressed afterwards with the same limit, so the limit holds before and after decompression. Error messages are unchanged and still contain no URL or body. Fixed the KDoc of `timeout`: it now says the timeout covers the whole fetch and that host resolution for the SSRF check is not part of it. The existing test "the timeout covers the body, so a feed that trickles in slowly is given up" now passes, unchanged.

Validation:
- `./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.infra.ics.IcsFeedClientTests'` (3 runs) - Pass (39/39 each)

### TW (failing test) - A day in DURATION is a calendar day across a daylight saving change

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsEventMapping.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsEventMappingTests.kt`

Problem:
A timed event's end was `start + Duration`, so `P1D` was always exactly 24 hours, including across a DST change (RFC 5545 §3.3.6 makes days and weeks nominal).

Fix:
The new `plusNominal` adds the day or week count of the DURATION as calendar days (`ZonedDateTime.plusDays`) in the event's zone, and adds the rest (hours, minutes, seconds) as exact time. `duration()` (also used for VALARM triggers) is unchanged. The failing test is kept as is. Added a test for weeks across DST (`P1W`) and for `P1DT1H30M` across DST.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.ics.*'` - Pass

### CR-4 - Overrides whose series the adapter drops cause a permanent sync error

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsEventMapping.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsEventMappingTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedSyncTests.kt`

Problem:
`RECURRENCE-ID` exceptions of a cancelled series, of a series without `RRULE`, or with no series at all were still returned. The engine counted them as not stored (permanent `lastError`) and dropped the cursor, which forced a full download on every poll.

Fix:
`IcsEventMapping.events` returns an exception only when the feed has a mapped (so not cancelled) master with an `RRULE` under the same UID, in any order. The existing test "an exception has its recurrence id and neither RRULE nor EXDATEs" mapped a lone exception. It now includes its series, because the behavior it relied on was the defect. Its assertions are unchanged. Added a mapping test (cancelled series, RDATE-only series, no series, series after its exception) and a sync test (exception of a cancelled series gives no `lastError`).

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.ics.*'` - Pass

### CR-5 - Unreadable feed events are silently deleted locally

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedAdapter.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedAdapterTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/ics/IcsFeedSyncTests.kt`

Problem:
With `complete = true`, the earlier local copy of an event the mapping could not read was removed as if the feed had deleted it, and nothing appeared in the sync status.

Fix:
This is the simplest option that fits the `RemoteEventChanges` contract. When any VEVENT could not be read, the adapter returns `complete = false`. Readable events are still upserted and nothing is deleted. The next download in which every event can be read is complete again and removes events the feed has dropped. The cursor is kept, so an unchanged feed is still answered with 304. The WARN log (count and source id only) remains. Trade-off: while a feed contains an unreadable event, events removed from the feed stay locally, and the skip is still not shown in `lastError`. Showing it there would need a change to the sync engine (`calendar/sync`), which is outside this task's `touches`. The parent worker should record this with `tasks.py assume`. Tests: the adapter returns `complete = false` with no deletions; sync regression (an event becomes unreadable, its local copy is kept; when the feed is readable again and the event is gone, it is removed).

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.ics.*'` - Pass

## Not fixed

### CR-3 - Missing tests for size limit, gzip, webcal, redirects and parser/mapping edge cases

Status:
Out of scope

Reason:
This finding was not in the list the parent worker gave the fixer. The test-writer's committed tests (e06460e3) already cover most of it. The regression tests named in CR-1 and CR-2 exist and pass.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` - Pass (no remaining changes)
- `cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.calendar.infra.ics.*'` - Pass (115 tests: IcsEventMappingTests 36, IcsFeedAdapterTests 13, IcsFeedClientTests 39, IcsParserTests 16, IcsFeedSyncTests 11; 0 failures)

Full test suite:
- `cd server && ./gradlew check` - 3407 tests, 1 failure: `ClockConfigurationTests` "the application clock is the system clock in UTC". This test is unrelated to this task (core persistence). It failed on a nanosecond vs microsecond timestamp comparison and passed when re-run on its own (`./gradlew test --tests 'ch.sonensei.cockpit.core.persistence.ClockConfigurationTests'` - Pass). It looks flaky.
- `cd server && ./gradlew check -x test` - Pass (ktlint main, test and scripts)

Lint/type-check/build:
- Covered by `./gradlew check -x test` above - Pass. The Kotlin compiler warning at `IcsFeedSyncTests.kt:109` (unnecessary `!!` in `exdatesOf`) was there before these fixes and is unrelated to the findings, so it was not changed.

`.taskcheck`:
- Pass (`sh scripts/check.sh` - "all checks passed")

Remaining unresolved findings:
1 (CR-3, out of the supplied scope)

## Final status

All validated findings fixed and validation passed.
