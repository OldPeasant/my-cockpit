---
title: Flaky test: CalDavClientTests 'a connection that fails after the answer started is not repeated' sometimes retries
status: in_progress
owner: Peter Gürtler
deps: [cal-caldav-retry]
touches: [server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavClientTests.kt,server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavClient.kt]
prio: 1
lease_until: 
---
## Goal
CI run 41493673821 (2026-11-02, push of `done: fix-flaky-bookmark-patch-test`, job "Fast check (server)") failed on
`CalDavClientTests > a connection that fails after the answer started is not repeated()` with
`Expected exception ch.sonensei.cockpit.calendar.sync.CalendarProviderException but no exception was thrown`
(5205 tests, 1 failed). The commit only touched the bookmarks test and the test passed in the runs before, so it is
intermittent; main is red because of it.

Likely cause (not yet confirmed): the test drops the first GET with WireMock `Fault.MALFORMED_RESPONSE_CHUNK`, then
answers 200 "ok" (`dropOnceThen`). `sendRetryingDrop` (CalDavClient.kt) treats an `IOException` as "before any
answer" unless the `BodyHandler` was already invoked. If the JDK HttpClient sometimes fails before it calls the
handler (the fault races the status line/header parsing), the client classifies it as a drop before the answer,
retries after the backoff, gets "ok" and throws nothing. Find out which side is wrong: the test (fault doesn't
reliably deliver headers first) or production (the "answered" detection misses an answer that started). If
production retries a request whose answer had started, that is a real bug: fix it there.

## Acceptance criteria
- [ ] Root cause identified and written in the Notes (test timing vs. `answered` detection in `sendRetryingDrop`), with how it was reproduced or why it can't be
- [ ] The test (or production code, if that is where the bug is) is deterministic: the test runs 200 times in a row without a failure (e.g. a `@RepeatedTest` run or a Gradle loop, removed again afterwards), and still fails if the client retried after the answer started
- [ ] The other CalDavClientTests retry tests still pass; `cd server && ./gradlew check` passes

## Notes
