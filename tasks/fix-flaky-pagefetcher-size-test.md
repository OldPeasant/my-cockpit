---
title: Flaky test: PageFetcherSizeLimitTests HTTP/2 reuse case gets TIMEOUT for some of 50 fetches
status: in_progress
owner: dell26/agent-1
deps: []
touches: [server/src/test/kotlin/ch/sonensei/cockpit/bookmarks,server/src/main/kotlin/ch/sonensei/cockpit/bookmarks]
prio: 2
lease_until: 2026-11-08T23:04:20Z
---
## Goal
`PageFetcherSizeLimitTests` "pages larger than max-size are cut off reliably, also over a reused HTTP 2 connection"
fails often (2026-11-08: in `sh scripts/check.sh` 46x "4096 bytes" + 4x "TIMEOUT (null)", and again when run
alone with `./gradlew test --tests '*PageFetcherSizeLimitTests*'`), which blocks `done` for unrelated tasks. The
fetcher uses a 1 s timeout in the test; find out whether cancelling at the limit over HTTP/2 can really hang
(production bug in `PageFetcher`) or whether the test's timeout is just too tight, and fix the cause.

## Acceptance criteria
- [ ] Root cause found and fixed (PageFetcher or test), not just retried
- [x] `cd server && ./gradlew test --tests '*PageFetcherSizeLimitTests*'` passes 10 runs in a row
  - verified (2026-11-08 23:45+0100 by dell26/agent-1): sh .tmp/loop.sh (cd server; 10x ./gradlew test --tests '*PageFetcherSizeLimitTests*' --rerun -q, REPEATS=200) at 85d491a81 -> passed 10/10
- [ ] `sh scripts/check.sh` passes

## Notes
