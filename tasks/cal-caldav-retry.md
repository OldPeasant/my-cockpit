---
title: CalDAV: survive dropped connections (flaky Radicale integration tests)
status: todo
owner:
deps: []
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/caldav,server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav]
prio: 1
---
## Goal
GitHub CI on main fails in most runs since cal-caldav: `CalDavRadicaleSyncTests` (integrationTest job) loses 4-5 of
10 tests per run, a different set each time. Radicale closes the connection before any response
(`java.io.EOFException` at Http1AsyncReceiver), both in the test's `dav()` helper (`@BeforeEach`, line 78) and in
`CalDavClient` ("the CalDAV server could not be reached" -> `syncOk()` fails, line 119). Real CalDAV servers can drop
connections too, and today one dropped request fails the sync of that calendar. Make the client and the tests robust.

## Acceptance criteria
- [ ] `CalDavClient` retries a request once (short backoff) when the connection fails before any response
  arrives, only for requests safe to repeat (PROPFIND, REPORT, GET; PUT/DELETE with If-Match or If-None-Match); no retry
  after a response was received or on timeouts; covered by unit tests (WireMock fault, e.g. `Fault.EMPTY_RESPONSE`)
- [ ] The test helper `dav()` in `CalDavRadicaleSyncTests` retries the same way
- [ ] `cd server && ./gradlew integrationTest --tests '*CalDavRadicaleSyncTests' --rerun` passes 10 times in a row
  (Docker or Podman), and the cause of Radicale's drops is noted (found or ruled-out causes) in this task's notes

## Notes
- Findings (2026-10-21, human session): local reproduction 1 in ~6 runs; a direct probe with java.net.http against
  tomsquest/docker-radicale:3.5.4.0 lost ~1 in 900 requests with EOFException, the same with HTTP_1_1 and with the
  default HTTP_2 (h2c upgrade), so neither h2c nor connection reuse is the cause (Radicale answers HTTP/1.0 and
  closes every connection). The drop rate is much higher on GitHub runners; not understood yet (Radicale
  `[server] max_connections`/`timeout`, container start-up readiness: `Wait.forListeningPort()` may be too early).
- Failing CI runs: 39631689604, 39626614823 (`gh run view <id> --log-failed`).
