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
- [x] Root cause identified and written in the Notes (test timing vs. `answered` detection in `sendRetryingDrop`), with how it was reproduced or why it can't be
  - verified (2026-11-02 20:11+0100 by Peter Gürtler): Root cause in task Notes: CI test XML of run 41493673821 shows the client's 'lost its connection before an answer ... sending it again' for this test; WireMock 3.13.1 JettyFaultInjector.malformedResponseChunk disassembled (setStatus+flushBuffer, then raw SocketChannel.write + socket.close past Jetty); throwaway probe of 2000 GETs against that fault with CalDavClient.httpClient() -> 1999 answered, 1 'EOF reached while reading' with no BodyHandler call: the test's fault, production retries by design
- [x] The test (or production code, if that is where the bug is) is deterministic: the test runs 200 times in a row without a failure (e.g. a `@RepeatedTest` run or a Gradle loop, removed again afterwards), and still fails if the client retried after the answer started
  - verified (2026-11-02 20:11+0100 by Peter Gürtler): On 98942ff8b (test-only, raw ServerSocket on 127.0.0.1 sending status+headers then closing mid-body): temporary @RepeatedTest(200) -> 200/200 pass (1.3 s), also 300/300 on f96d0a580 and 482a42fd0; mutation CalDavClient.kt 'if (!repeatable || answered.get())' -> 'if (!repeatable)' -> the test FAILS (no exception thrown), reverted (git diff empty)
- [x] The other CalDavClientTests retry tests still pass; `cd server && ./gradlew check` passes
  - verified (2026-11-02 20:11+0100 by Peter Gürtler): cd server && ./gradlew check jacocoTestReport on 98942ff8b -> exit 0, 5205 tests, 0 failures, all 27 CalDavClientTests (incl. the other retry tests) pass

## Notes
- Root cause (2026-11-02, Peter Gürtler/Claude): the CI test XML of run 41493673821 has the client's "lost its connection before an answer (IOException), sending it again" line for this test: the client classified the drop as before the answer and retried. WireMock 3.13.1 `JettyFaultInjector.malformedResponseChunk()` does `setStatus(200)`, `flushBuffer()`, then writes garbage straight to the raw SocketChannel and calls `socket.close()`, bypassing Jetty. Reproduced with a throwaway probe (2000 GETs against that fault, JDK HttpClient as built by `CalDavClient.httpClient()`): 1999 x ProtocolException after the BodyHandler ran (answered=true), 1 x `IOException: EOF reached while reading` with the BodyHandler never called (answered=false), i.e. the client got no status line at all. A GET with no answer is retried by design, so production is right and the test's fault is unreliable. Fix: the test uses a raw ServerSocket that reads the request head, sends the full status line and headers (Content-Length 100) plus 2 body bytes, then shuts the output down cleanly; a second connection would get a complete 200 "ok".
- ASSUMPTION (2026-11-02 19:54+0100 by Peter Gürtler): Root cause is the test, not production: WireMock's MALFORMED_RESPONSE_CHUNK fault (JettyFaultInjector: setStatus+flushBuffer, then raw channel write + socket.close) occasionally reaches the client as EOF before the status line (local probe: 1 of 2000, IOException 'EOF reached while reading', answered=false; CI log showed the retry line). A GET without any answer is retried by design (cal-caldav-retry), so production stays unchanged; the test now uses a raw socket server that always sends status+headers before closing mid-body
