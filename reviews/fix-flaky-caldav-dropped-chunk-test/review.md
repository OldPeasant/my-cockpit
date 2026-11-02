# Code Review

Date: 2026-11-02

Task: fix-flaky-caldav-dropped-chunk-test - Flaky test: CalDavClientTests 'a connection that fails after the answer started is not repeated' sometimes retries

Scope: final re-review of the full task diff `git diff b065374b7..98942ff8b -- server` (reviewed revision 98942ff8b2cdd749995ba4eaff9f44e88f5bdb65). Code commits: 9040673c6 (first review, as 482a42fd0 before rebase), f96d0a580 (removes the sleep, CR-1), 98942ff8b (binds the ServerSocket to 127.0.0.1, CR-2); ca264324d, faf76a805 and e5af4ab31 touch only `tasks/` and `reviews/`. Test-only change in `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavClientTests.kt`; production `CalDavClient.kt` (`sendRetryingDrop`) read for context, unchanged.

Requirements:
- tasks/fix-flaky-caldav-dropped-chunk-test.md (acceptance criteria, Notes, assumption)
- AGENTS.md project notes (lint, test conventions, fictitious test data)
- KDoc contract of `sendRetryingDrop` in CalDavClient.kt (retry only when no status line/headers arrived)
- Parent instructions (judge root cause, determinism, socket/thread behaviour, h2c upgrade, sleep-based check; no Gradle run)

Git range:
- Branch: main
- HEAD: 98942ff8b2cdd749995ba4eaff9f44e88f5bdb65
- Base/start: b065374b796099708fd0d94b660c06573a9f56c7
- Commits reviewed: 3 code commits (9040673c6, f96d0a580, 98942ff8b) plus 3 commits touching only tasks/ and reviews/

Summary: 0 P0, 0 P1, 0 P2, 0 P3 open (2 P3 from the first review, both resolved)

## Root cause assessment

The conclusion holds. Disassembling `JettyFaultInjector.malformedResponseChunk()` from the wiremock-standalone 3.13.1 jar in the Gradle cache confirms the described sequence: `setStatus(200)`, `flushBuffer()`, then a raw `SocketChannel.write(GARBAGE)` and `Socket.close()` outside Jetty. Nothing guarantees that Jetty's flush of the status line has reached the client before the raw write and close (and a close with unread request bytes can turn into a reset that discards data the client had not read yet), so the client can occasionally see the connection end before the status line. In that case the `BodyHandler` never ran, `answered` is false, and `sendRetryingDrop` retries a GET, which is exactly its documented contract ("fails before any answer arrives ... sends it once more"). Production is therefore correct and leaving `CalDavClient.kt` unchanged is justified. The 1-in-2000 probe and the CI retry log line are the parent's evidence; I could not re-check the CI log, but the probe result fits the fault implementation.

## Determinism assessment of the new test

- Request handling: `CalDavClient.httpClient()` uses the JDK default version (HTTP/2), so a plain `http://` GET goes out as HTTP/1.1 with `Connection: Upgrade, HTTP2-Settings`, `Upgrade: h2c` and `HTTP2-Settings` headers and no body. The server answers `HTTP/1.1 200` (no 101), so the client stays on HTTP/1.1 and parses the reply normally. `readRequestHead` consumes everything up to CRLFCRLF, and with no request body there are no unread bytes left, so `close()` sends FIN, not RST.
- Answer started: the status line, headers and 2 of 100 announced body bytes are written and flushed before `shutdownOutput()`, so the client always calls the `BodyHandler` (answered=true) before it hits EOF in the body; the resulting IOException is rethrown, not retried. Even an unexpected RST could only arrive after the headers were written, so the classification cannot flip back to "before the answer".
- Detects a regression: if the client retried, the second connection gets a complete `200 ok` with `Content-Length: 2`, `send` returns normally and `shouldThrow` fails. The partially read first connection cannot be pooled and reused, so a retry necessarily opens connection 2. JDK-internal retries (ConnectionExpiredException) only apply to reused pooled connections, which this fresh connection is not.
- Leaks: the accept thread is a daemon; `ServerSocket.use` closes the listener, `accept()` then throws, `runCatching` returns null and the loop breaks. Each accepted socket is closed by `socket.use`. The per-test `HttpClient` is not closed, which matches the rest of the class (the `https`, `http`, `local` clients are not closed either).
- No connection to WireMock or shared state, so it cannot interfere with the other retry tests (`dropOnceThen` with EMPTY_RESPONSE / CONNECTION_RESET_BY_PEER, unchanged).
- No secrets or real data: `PASSWORD` is the class's fictitious constant, host is loopback.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [x] **CR-1 | The sleep before the connection count adds 400 ms without adding detection**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavClientTests.kt:320`
    - Category: Tests
    - Problem: `sendRetryingDrop` retries synchronously inside `send`, so any retry has already reached the server (and incremented `connections`, which happens before the server writes the answer the client waits for) by the time `shouldThrow` evaluates; a retry also makes `shouldThrow` fail on its own. `Thread.sleep(DROPPED_CONNECTION_BACKOFF.toMillis() * 2)` therefore cannot catch anything the assertions before it miss. It cannot cause a false failure either, so this is not a flakiness risk, only 400 ms per run (80 s over the 200-run criterion loop).
    - Why it matters: unnecessary wall-clock time in the fast `.taskcheck` suite and a misleading hint that the retry could happen asynchronously.
    - Suggested fix: drop the sleep and keep `connections.get() shouldBe 1` directly after `shouldThrow`, or add a one-line comment if it is meant to guard a future asynchronous retry.
    - Resolution (f96d0a580): the sleep is removed; `connections.get() shouldBe 1` follows `shouldThrow` directly, with a comment explaining why no waiting is needed. `DROPPED_CONNECTION_BACKOFF` is still used by another test, so no unused import or constant remains. Resolved.

- [x] **CR-2 | Client URI hard-codes 127.0.0.1 while the server binds to `InetAddress.getLoopbackAddress()`**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavClientTests.kt:294` and `:315`
    - Category: Tests
    - Problem: `getLoopbackAddress()` returns `::1` when the JVM runs with `-Djava.net.preferIPv6Addresses=true`; the client would then get ConnectException twice and the test would fail on `connections shouldBe 1` (0). With the default JVM settings used by the build it is 127.0.0.1, so this does not affect current runs.
    - Why it matters: a small environment dependency in a test whose whole purpose is determinism.
    - Suggested fix: build the URI from the bound address (`server.inetAddress.hostAddress`, bracketed for IPv6) or bind explicitly to `InetAddress.getByName("127.0.0.1")`.
    - Resolution (98942ff8b): the server now binds to `InetAddress.getByName("127.0.0.1")` (now line 293), matching the client URI `http://127.0.0.1:<port>/dav/`, independent of `java.net.preferIPv6Addresses`. The `InetAddress` import is still used. Resolved.

## Re-review of the fix commits

`git diff 9040673c6..98942ff8b -- server` contains exactly two changes in `CalDavClientTests.kt`: the bind address and the removed sleep with its replacement comment. Production code is unchanged throughout the task (`git diff b065374b7..98942ff8b -- server/src/main` is empty). The determinism analysis above still holds; removing the sleep cannot hide a retry, because `sendRetryingDrop` retries synchronously inside `send` and the server increments `connections` before it answers. Gradle was not run in this review (the parent instructed so; a test-writer runs the build afterwards).

## Notes for the parent worker

- No acceptance criterion has evidence recorded yet (`python tasks.py verify fix-flaky-caldav-dropped-chunk-test` shows all three open). Criterion 2 needs both the 200-run loop and a demonstration that the test fails when the client retries after the answer started (for example temporarily ignoring `answered` in `sendRetryingDrop`), both reverted afterwards; criterion 1 is covered by the Notes once that is recorded.

## Review conclusion

No material issues found. Both P3 findings from the first review are resolved in 98942ff8b.
