# Fix Report

Date: 2026-11-02

Task: fix-flaky-caldav-dropped-chunk-test - Flaky test: CalDavClientTests 'a connection that fails after the answer started is not repeated' sometimes retries

Scope: current task; findings CR-1 and CR-2 from `reviews/fix-flaky-caldav-dropped-chunk-test/review.md`

## Fixed findings

### CR-2 - Client URI hard-codes 127.0.0.1 while the server binds to `InetAddress.getLoopbackAddress()`

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavClientTests.kt`

Problem:
The test's `ServerSocket` bound to `InetAddress.getLoopbackAddress()`, which is `::1` when the JVM prefers IPv6 addresses, while the client URI always targets `http://127.0.0.1:<port>/dav/`. Under that JVM setting the client could not connect and the test would fail.

Fix:
The `ServerSocket` now binds explicitly to `InetAddress.getByName("127.0.0.1")`, the same IPv4 loopback the client URI uses, so server and client always agree regardless of JVM address preferences. Test-only change; production code untouched.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.infra.caldav.CalDavClientTests'` - Pass (BUILD SUCCESSFUL; 27 tests, 0 failures, 0 errors, 0 skipped)

### CR-1 - The sleep before the connection count adds 400 ms without adding detection

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavClientTests.kt` (by the test-writer in commit f96d0a580, not by this fixer)

Problem:
`Thread.sleep(DROPPED_CONNECTION_BACKOFF.toMillis() * 2)` before `connections.get() shouldBe 1` could not detect anything the synchronous assertions miss.

Fix:
Already removed by the test-writer in f96d0a580 ("drop the needless wait"). The fixer made no further change for this finding.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.infra.caldav.CalDavClientTests'` - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.infra.caldav.CalDavClientTests'` - Pass (27 tests, 0 failures)

Full test suite:
- Not run (one-line test-only change; scope limited to CalDavClientTests by the parent worker)

Lint/type-check/build:
- `./gradlew ktlintCheck` (part of the command above) - Pass

`.taskcheck`:
- Not run (left to the parent worker before `done`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
