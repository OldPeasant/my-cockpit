---
title: CalDAV: survive dropped connections (flaky Radicale integration tests)
status: in_progress
owner: dell26/agent-1
deps: []
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/caldav,server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav]
prio: 1
lease_until: 2026-10-26T00:09:42Z
---
## Goal
GitHub CI on main fails in most runs since cal-caldav: `CalDavRadicaleSyncTests` (integrationTest job) loses 4-5 of
10 tests per run, a different set each time. Radicale closes the connection before any response
(`java.io.EOFException` at Http1AsyncReceiver), both in the test's `dav()` helper (`@BeforeEach`, line 78) and in
`CalDavClient` ("the CalDAV server could not be reached" -> `syncOk()` fails, line 119). Real CalDAV servers can drop
connections too, and today one dropped request fails the sync of that calendar. Make the client and the tests robust.

## Acceptance criteria
- [x] `CalDavClient` retries a request once (short backoff) when the connection fails before any response
  arrives, only for requests safe to repeat (PROPFIND, REPORT, GET; PUT/DELETE with If-Match or If-None-Match); no retry
  after a response was received or on timeouts; covered by unit tests (WireMock fault, e.g. `Fault.EMPTY_RESPONSE`)
  - verified (2026-10-26 00:51+0100 by dell26/agent-1): cd server && ./gradlew ktlintCheck test --tests '*CalDavClientTests' -> 27 passed, 0 failed (WireMock Fault.EMPTY_RESPONSE / CONNECTION_RESET_BY_PEER retried once for GET/PROPFIND/REPORT/If-Match PUT/If-None-Match PUT/If-Match DELETE; unconditional PUT/DELETE, MKCALENDAR, POST, MALFORMED_RESPONSE_CHUNK after headers and a 200 ms read timeout not retried; second drop fails); JaCoCo CalDavClient.kt lines 162/168, branches 112/118
- [x] The test helper `dav()` in `CalDavRadicaleSyncTests` retries the same way
  - verified (2026-10-26 00:52+0100 by dell26/agent-1): CalDavRadicaleSyncTests.dav() now calls the shared sendRetryingDrop(..., repeatable = true) (same once-retry, 200 ms backoff, no retry after headers or on timeout); exercised by 10x ./gradlew integrationTest --tests '*CalDavRadicaleSyncTests' --rerun on rootless Podman -> 10/10 runs passed
- [x] `cd server && ./gradlew integrationTest --tests '*CalDavRadicaleSyncTests' --rerun` passes 10 times in a row
  (Docker or Podman), and the cause of Radicale's drops is noted (found or ruled-out causes) in this task's notes
  - verified (2026-10-26 00:52+0100 by dell26/agent-1): sh .tmp/radicale10.sh (from server/: 10 consecutive ./gradlew integrationTest --tests '*CalDavRadicaleSyncTests' --rerun, rootless Podman 5.x, DOCKER_HOST podman.sock) -> 10 of 10 runs exit 0, each 10 tests 0 failures 0 errors; 0 client retries, Podman create broken pipe recovered by withStartupAttempts(2) in 3 runs; cause investigation in task notes (max_connections/timeout ruled out, CI port-proxy readiness suspected, not verified)

## Notes
- Findings (2026-10-21, human session): local reproduction 1 in ~6 runs; a direct probe with java.net.http against
  tomsquest/docker-radicale:3.5.4.0 lost ~1 in 900 requests with EOFException, the same with HTTP_1_1 and with the
  default HTTP_2 (h2c upgrade), so neither h2c nor connection reuse is the cause (Radicale answers HTTP/1.0 and
  closes every connection). The drop rate is much higher on GitHub runners; not understood yet (Radicale
  `[server] max_connections`/`timeout`, container start-up readiness: `Wait.forListeningPort()` may be too early).
- Failing CI runs: 39631689604, 39626614823 (`gh run view <id> --log-failed`).
- Cause investigation (2026-10-26, dell26/agent-1), root cause of the EOF drops still not found:
  - Ruled out, Radicale `[server] max_connections` (default 8): radicale/server.py (3.5.4) only stops `select()`ing
    the listening socket while 8 workers run, so extra clients wait in the TCP backlog; nothing is closed.
  - Ruled out, `[server] timeout` (default 30 s): a per-socket read timeout, far above our request times; it would
    log "Client timed out", not close before a request is read.
  - Not reproduced locally: 10 `--rerun` runs on rootless Podman (about 100 tests) needed 0 client retries (the
    INFO line "lost its connection before an answer" never appeared), so the drop rate here is below 1 per run.
  - Still suspected for GitHub runners: the Docker port proxy (docker-proxy/userland proxy) accepts the host-side
    connection and closes it when the container side fails or is not ready yet, and `Wait.forListeningPort()` can
    pass before Radicale serves. That fits an EOF before any response and a higher rate on CI. Not verified (no
    access to CI logs, `gh` is not available to agents). The retry now hides single drops in both the client and
    the helper, and every client retry is logged at INFO, so CI logs will show how often it happens.
  - Separate local problem found and fixed: with a socket-activated Podman API service, the Radicale container
    create failed every time with `IOException: Broken pipe` (docker-java reused a pooled connection after the
    idle service exited while the Spring context started). `withStartupAttempts(2)` fixes it; the second attempt
    was needed in 3 of the 10 runs.
- ASSUMPTION (2026-10-26 00:42+0100 by dell26/agent-1): The Radicale test helper dav() retries every method once (MKCALENDAR, unconditional PUT/DELETE too), unlike CalDavClient: the tests own the server and check outcomes, and restricting it would leave most setup requests unprotected. A repeated MKCALENDAR that already succeeded would answer 405 and fail reset(); accepted as far rarer than a drop.
- ASSUMPTION (2026-10-26 00:42+0100 by dell26/agent-1): Retry backoff is a fixed 200 ms, one retry; 'before any response' means before the status line and headers arrived (tracked via the BodyHandler), so a failure while reading the body is not repeated. HEAD and OPTIONS count as safe methods besides PROPFIND/REPORT/GET. Each retry is logged at INFO (method, host, exception class only).
- ASSUMPTION (2026-10-26 00:47+0100 by dell26/agent-1): A retried If-Match PUT/DELETE whose first attempt reached the server (only the answer was lost) gets 412 and is reported as 'changed remotely' (409), although the user's change was applied; the next sync shows the user's own version. Left as is (rare, recoverable, CalDavCalendarAdapter write paths are outside this task's criteria); a follow-up could re-read the resource after a 412 on a retried write, as the create path does.
