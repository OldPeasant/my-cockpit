# Fix Report

Date: 2026-10-21

Task: cal-google - Google Calendar adapter (SPEC.md §6.2)

Scope: Current task. Approved findings CR-1/TW-1, TW-2 and CR-3 from `reviews/cal-google/review.md` and `reviews/cal-google/tests.md`. Fix commit: 2f2f8c8e.

## Fixed findings

### CR-1 / TW-1 — Google's "cancelled" write answer was read back as CONFIRMED

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleEventMapping.kt`

Problem:
`toRemote` mapped every status except "tentative" to CONFIRMED. When an occurrence that has an override is cancelled, Google answers with `status: "cancelled"`. The write-through then stored the override as CONFIRMED again.

Fix:
`toRemote` maps "cancelled" to `EventStatus.CANCELLED` and updated the KDoc. The sync path is unaffected: `fetchEvents` and `withCancelledInstances` check `GoogleEventMapping.isCancelled` before they call `toRemote`, and treat cancelled items as deletions or EXDATEs. The only other caller is `answer()`, which handles write answers.

Validation:
- `GoogleCalendarAdapterTests > cancelling an edited occurrence keeps it cancelled in Google's answer` — Pass (failed before the fix)
- `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.infra.google.*' --tests 'ch.sonensei.cockpit.calendar.sync.*'` — Pass

### TW-2 — Sync-engine tests used GOOGLE as "a type without adapter"

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/sync/FakeCalendarAdapter.kt`

Problem:
`CalendarSyncEdgeCaseTests` (2 tests) and `CalendarSyncTests` (1 test) save a GOOGLE source and expect it to have no adapter. That stopped being true once the Google adapter was registered. The same would happen to MICROSOFT and ICS_FEED when their adapters arrive.

Fix:
`FakeCalendarAdapterConfiguration`, which the sync tests already import, now also registers a `@Primary` `CalendarProviderRegistry` that contains only the fake (CALDAV) adapter. In those tests, every non-LOCAL type except CALDAV has no adapter, whichever real adapters exist. The three tests and their assertions are unchanged. The real registry is still built from all adapter beans, so duplicate detection still runs. `GoogleCalendarSyncTests` does not import this configuration and still uses the real registry.

Validation:
- `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.infra.google.*' --tests 'ch.sonensei.cockpit.calendar.sync.*'` — Pass (147 tests, 0 failures)

### CR-3 — The adapter sent the connection's token without checking that it is a Google connection

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleCalendarAdapter.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/google/GoogleCalendarAdapterTests.kt`

Problem:
The adapter took any connection UUID from the source config and sent that connection's access token to Google, even when the connection belonged to another provider (for example a Microsoft Graph token).

Fix:
`connectionId(source)` now looks up `tokens.connection(id)`. If the connection belongs to a provider other than `OAuthProvider.GOOGLE`, it throws `CalendarProviderException("The source is not linked to a Google connection")` before any token is requested. A missing connection is still reported by the existing token path ("was removed"). Every adapter operation goes through `connectionId`. The test `FixedTokens` now returns connection info for the test connection (provider can be changed, default GOOGLE). New test: `a source linked to a connection of another provider fails without sending its token to Google`. It covers listCalendars, fetchEvents, createEvent, updateEvent and deleteEvent, and checks that WireMock received no requests.

Validation:
- `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.infra.google.*' --tests 'ch.sonensei.cockpit.calendar.sync.*'` — Pass

## Not fixed

### CR-2 — Writes turn Google e-mail and default reminders into popup overrides

Status:
Requires decision

Reason:
The parent worker decided not to fix this and records it as an assumption (`tasks.py assume`). No code was changed.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.infra.google.*' --tests 'ch.sonensei.cockpit.calendar.sync.*'` — BUILD SUCCESSFUL, 147 tests in 7 classes, 0 failures

Full test suite:
- `cd server && ./gradlew check`, first run: 3116 tests, 1 failure in `ClockConfigurationTests > the application clock is the system clock in UTC`. The application clock truncates to microseconds, and the test compares it with a nanosecond `Instant.now()`, so it fails intermittently. That code is outside this task and was not changed by these fixes.
- `cd server && ./gradlew check`, second run: BUILD SUCCESSFUL

Lint/type-check/build:
- ktlintCheck is part of both commands above — Pass

`.taskcheck`:
- Not run (only the server check was requested; the parent worker runs `.taskcheck`)

Remaining unresolved findings:
1 (CR-2, deliberately left for the parent's assumption)

## Final status

All validated findings fixed and validation passed. CR-2 was intentionally not fixed (the parent records it as an assumption). There is a separate flaky test in `ClockConfigurationTests`, outside this task.
