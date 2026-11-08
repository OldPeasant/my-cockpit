---
title: Flaky test: PageFetcherSizeLimitTests HTTP/2 reuse case gets TIMEOUT for some of 50 fetches
status: in_progress
owner: dell26/agent-1
deps: []
touches: [server/src/test/kotlin/ch/sonensei/cockpit/bookmarks,server/src/main/kotlin/ch/sonensei/cockpit/bookmarks]
prio: 2
lease_until: 2026-11-08T23:09:44Z
releases: 1
extended: 
---
## Goal
`PageFetcherSizeLimitTests` "pages larger than max-size are cut off reliably, also over a reused HTTP 2 connection"
fails often (2026-11-08: in `sh scripts/check.sh` 46x "4096 bytes" + 4x "TIMEOUT (null)", and again when run
alone with `./gradlew test --tests '*PageFetcherSizeLimitTests*'`), which blocks `done` for unrelated tasks. The
fetcher uses a 1 s timeout in the test; find out whether cancelling at the limit over HTTP/2 can really hang
(production bug in `PageFetcher`) or whether the test's timeout is just too tight, and fix the cause.

## Acceptance criteria
- [x] Root cause found and fixed (PageFetcher or test), not just retried
  - verified (2026-11-08 23:45+0100 by dell26/agent-1): Root cause: HTTP/2 stream cancel at max-size on the shared client connection. Before: REPEATS=1000 -> 999 ok + 1 'UNREACHABLE (EOF reached while reading)' at fetch #509. Fix: HttpClient.version(HTTP_1_1) in PageFetcher (no retry). After: REPEATS=1000 -> 1000/1000 ok; new test asserts WireMock sees HTTP/1.1
- [x] `cd server && ./gradlew test --tests '*PageFetcherSizeLimitTests*'` passes 10 runs in a row
  - verified (2026-11-08 23:45+0100 by dell26/agent-1): sh .tmp/loop.sh (cd server; 10x ./gradlew test --tests '*PageFetcherSizeLimitTests*' --rerun -q, REPEATS=200) at 85d491a81 -> passed 10/10
- [ ] `sh scripts/check.sh` passes

## Notes
- ASSUMPTION (2026-11-08 23:45+0100 by dell26/agent-1): PageFetcher now forces HTTP/1.1: with HTTP/2 the per-fetch stream cancel on the shared connection made ~1 in 500 fetches fail (EOF reproduced at REPEATS=1000; TIMEOUT seen under load). Exact JDK-vs-Jetty mechanism not pinned down; HTTP/1.1 costs a new connection per truncated page, irrelevant for bookmark metadata
- release by dell26/agent-1: Fix done: PageFetcher forces HTTP/1.1 (HTTP/2 stream cancel on shared conn broke ~1/500 fetches; EOF reproduced at REPEATS=1000, 1000/1000 after fix). Criteria 1+2 verified at 85d491a81. review.md (0 findings above P3; CR-1 P3) and tests.md committed. Test-writer then changed the protocol test (two fetches, addresses CR-1), so: optionally confirm it fails without .version(HTTP_1_1), rerun 10x loop, rerun code-reviewer on final code (test-writer evidence is at final code), record CR-1 fixed => needs fixer report (fixer may just verify), run sh scripts/check.sh, verify #3, write quality.json, done.
