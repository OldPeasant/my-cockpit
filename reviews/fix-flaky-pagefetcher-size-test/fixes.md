# Fix Report

Date: 2026-11-08

Task: fix-flaky-pagefetcher-size-test - Flaky test: PageFetcherSizeLimitTests HTTP/2 reuse case gets TIMEOUT for some of 50 fetches

Scope: current task; finding CR-1 from `reviews/fix-flaky-pagefetcher-size-test/review.md`. The fixer made no code
change: the fix was already applied by the parent worker in commit aa1cb2417, and this report checks it.

## Fixed findings

### CR-1 - Protocol regression test may not catch HTTP/2 on a fresh connection

Files changed (by commit aa1cb2417, not by the fixer):
- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/PageFetcherSizeLimitTests.kt`

Problem:
The protocol test fetched once and checked that WireMock saw `HTTP/1.1`. On a fresh plain-http connection the JDK
client sends its first request as HTTP/1.1 with an `Upgrade: h2c` header, so a single fetch could pass even with
`.version(HttpClient.Version.HTTP_1_1)` removed. Because the fetcher is shared (`PER_CLASS`), whether a connection
already existed also depended on test order. The test therefore might not guard against the HTTP/1.1 setting being
removed.

Fix:
Commit aa1cb2417 changes the test to fetch `/page` twice and assert
`server.allServeEvents.map { it.request.protocol } shouldBe listOf("HTTP/1.1", "HTTP/1.1")`. A KDoc explains why two
fetches are needed. This is the reviewer's second suggestion. It covers both cases:
- fresh connection: the second request runs over the upgraded HTTP/2 connection and is recorded as `HTTP/2.0`;
- reused HTTP/2 connection from an earlier test: both requests are recorded as `HTTP/2.0`.

What the fixer checked in the current code (read only):
- `@BeforeEach fun reset() = server.resetAll()` (line 47-48) clears the serve events before each test, so the list
  holds exactly the two requests of this test. The exact-list assertion is not weakened by events from other tests.
- `PageFetcher.kt` line 76 still has `.version(HttpClient.Version.HTTP_1_1)`, so the test passes for the right reason.
- No assertion was relaxed. The single-fetch `.single()` check was replaced by a stricter two-element check.

Validation:
- Mutation check run by the parent worker (the fixer did not run Gradle, per instruction): with
  `.version(HttpClient.Version.HTTP_1_1)` in `PageFetcher.kt` commented out,
  `./gradlew test --tests '*PageFetcherSizeLimitTests*' --rerun` - Fail as intended, with
  `expected:{["HTTP/1.1", "HTTP/1.1"]} but was:{["HTTP/2.0", "HTTP/2.0"]}` (output brackets shown as braces here).
  So the test does catch removal of the setting, which was the reviewer's "better still" suggestion.
- Same command with the line restored (parent worker) - Pass, 10 tests.

## Not fixed

None.

## Validation summary

Focused tests:
- `./gradlew test --tests '*PageFetcherSizeLimitTests*' --rerun` (parent worker): fails with the fix removed and
  passes (10 tests) with it in place.

Full test suite:
- Not run by the fixer (Gradle reserved for the parent worker, which runs it in parallel).

Lint/type-check/build:
- Not run by the fixer, for the same reason. The fixer made no code change.

`.taskcheck`:
- Not run by the fixer. The parent worker runs `sh scripts/check.sh`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed. The fix for CR-1 was already applied in commit aa1cb2417; the fixer confirmed it by
reading the code and from the parent worker's mutation run. The fixer made no code change.
