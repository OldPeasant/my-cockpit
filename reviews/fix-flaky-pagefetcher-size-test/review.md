# Code Review

Date: 2026-11-08

Task: fix-flaky-pagefetcher-size-test - Flaky test: PageFetcherSizeLimitTests HTTP/2 reuse case gets TIMEOUT for some of 50 fetches

Scope: `git diff 382ad9732..85d491a81` (one task commit), reviewed by reading only (Gradle not run, by instruction of the parent worker because a test-writer was running it in parallel)

Requirements:
- tasks/fix-flaky-pagefetcher-size-test.md (acceptance criteria, recorded evidence and assumption)
- AGENTS.md project notes (conventions, lint, assumptions)
- SPEC.md and docs/ checked for any HTTP/2 requirement on page fetching: none found (`git grep` for HTTP/2, HTTP_2, http2, h2c)
- Existing PageFetcher code and tests (PageFetcherSizeLimitTests, other PageFetcher users: BookmarkMetadataWorker, BookmarkSnapshotService)

Git range:
- Branch: main
- HEAD: a62dd2f76 (task code commit reviewed: 85d491a81)
- Base/start: 382ad9732ddb5dcdb02926378b2f3adbbb984cda
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## What was reviewed

- `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/PageFetcher.kt`: the shared `HttpClient` now uses
  `.version(HttpClient.Version.HTTP_1_1)`. The code comment explains why. Two KDoc/comment lines that said
  "(seen with HTTP/2)" are now protocol-neutral. Cancelling the body later (in `send`'s `finally`) is unchanged and
  still needed: cancelling inside the subscriber can fail the exchange before the client hands out the body.
- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/PageFetcherSizeLimitTests.kt`: the repeated-fetch test was
  renamed and now runs 200 fetches instead of 50, and a new test checks that WireMock sees `HTTP/1.1`.

Acceptance criteria assessment:
- Root cause fixed, not retried: met. The fix takes away the failing path (HTTP/2 stream cancels on a connection
  shared with later fetches) and adds no retry, no longer timeout and no wider assertion. The recorded evidence
  (1 failure in 1000 fetches before, 1000/1000 after) and the assumption honestly say that the exact JDK-vs-Jetty
  mechanism is not pinned down. That is acceptable because the change avoids the mechanism instead of hiding it.
  The test's 1 s timeout is unchanged, so the fix does not just relax the test.
- 10 consecutive focused runs: evidence recorded (10/10 at 85d491a81). Not re-run here (no Gradle, by instruction).
- `sh scripts/check.sh` passes: not yet verified by the parent worker. That is still open and is not a review finding.

Production impact checked: forcing HTTP/1.1 affects every bookmark metadata and snapshot fetch. Plain-http hosts no
longer get an `Upgrade: h2c` request, and HTTPS hosts get ALPN offering only `http/1.1`, which every public web
server supports. Each truncated page closes its own connection. This costs a new TCP/TLS handshake per truncated
page, which does not matter for a background metadata fetch. Nothing changes in the SSRF protections, redirect
handling, timeouts or size limits. The existing test "over HTTP 1_1 a page larger than max-size is cut off and its
connection is closed" already covers HTTP/1.1 truncation and connection closing.

No security, integration, architecture or lint concerns found in the diff.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Protocol regression test may not catch HTTP/2 on a fresh connection**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/PageFetcherSizeLimitTests.kt:108`
    - Category: Tests
    - Problem: The new test makes a single fetch and checks that `request.protocol` is `"HTTP/1.1"`. Without the
      fix, the JDK client opens a plain-http connection by sending that first request itself as HTTP/1.1 with
      `Upgrade: h2c` / `HTTP2-Settings` headers. Whether WireMock (Jetty) then reports this upgrade request as
      `HTTP/1.1` or `HTTP/2.0` depends on Jetty's h2c upgrade handling. Whether the fetcher already holds an HTTP/2
      connection from an earlier test also depends on JUnit's method order, because the fetcher instance is shared
      (`PER_CLASS`). So the test may pass even with `.version(HTTP_1_1)` removed. The evidence does not show that the
      test was seen failing without the fix. The 200-fetch test also cannot fully guard this: at the measured rate of
      about 1 failure in 500 to 1000 HTTP/2 fetches, 200 fetches would often pass.
    - Why it matters: This test is the only one that reliably guards against someone removing the HTTP/1.1 setting,
      for example while "simplifying" the client builder. If it cannot fail, the flake can come back unnoticed.
    - Suggested fix: Make the assertion independent of the server's upgrade handling. Either also assert that the
      recorded request has no `Upgrade` header (`request.containsHeader("Upgrade") shouldBe false`), or fetch twice
      and assert that every serve event is `HTTP/1.1`. Better still, confirm once that the test fails with the
      `.version(...)` line removed.

## Review conclusion

No material issues found. One optional P3 test-strength improvement (CR-1). The remaining acceptance criterion
(`sh scripts/check.sh` passes) is still for the parent worker to verify.
