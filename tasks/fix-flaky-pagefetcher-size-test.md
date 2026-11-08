---
title: Flaky test: PageFetcherSizeLimitTests 'cut off reliably over reused HTTP 2 connection' gets TIMEOUT for 1-4 of 50 fetches
status: todo
owner:
deps: []
touches: [server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra,server/src/test/kotlin/ch/sonensei/cockpit/bookmarks]
prio: 2
---
## Goal
`sh scripts/check.sh` fails on rudi's machine (JDK 21.0.7 Red Hat, Fedora 40) in
`PageFetcherSizeLimitTests > pages larger than max-size are cut off reliably, also over a reused HTTP 2 connection`:
expected `{"4096 bytes"=50}`, got e.g. `{"4096 bytes"=46, "TIMEOUT (null)"=4}`. It reproduced in 4 of 4 isolated runs
(`server/gradlew -p server test --tests 'ch.sonensei.cockpit.bookmarks.PageFetcherSizeLimitTests' --rerun`), also at
low load, on main 2026-11-08 (no server change in the branch). Likely the HTTP/2 cancel-at-limit race in `PageFetcher`
(see the test's KDoc): a cancelled stream sometimes hangs until the request timeout.

## Acceptance criteria
- [ ] Root cause found; fixed in `PageFetcher` (production) if it is a real race, not by weakening the assertion
- [ ] The test passes 10 times in a row (`--rerun`) on a loaded machine, and `cd server && ./gradlew check` passes

## Notes
Found by rudi/agent-1 while finishing and-mail-attachments (blocks `done` on that machine).
