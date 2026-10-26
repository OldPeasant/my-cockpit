---
title: IcsFeedClient: refusing an oversized Content-Length sometimes surfaces as IOException ('could not be reached') instead of 'larger than'
status: in_progress
owner: dell26/agent-1
deps: []
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics,server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/ics]
prio: 2
lease_until: 2026-10-26T02:41:04Z
extended: dell26/agent-1
---
## Goal
`IcsFeedClientTests` "a feed of exactly the maximum size is read, a larger Content-Length is refused before reading"
failed in 2 of 2 full `server/gradlew -p server check` runs on dell26/agent-1 (2026-10-26, while working on
mail-sync-perf), and passed when its class ran alone. The refused fetch got "The feed server could not be reached
(IOException)" instead of "The feed is larger than ...". Likely cause: `LimitedBody(refuseAtOnce = true)` cancels the
body subscription, and the JDK HttpClient sometimes completes the future with its own IOException before the
subscriber's `tooLarge` error, so `fetchOnce` maps it to "could not be reached".

## Acceptance criteria
- [x] An oversized Content-Length is always reported as "larger than" (the refusal's own error wins over the IOException the cancelled exchange causes)
  - verified (2026-10-26 03:22+0100 by dell26/agent-1): cd server && ./gradlew check (full suite, HEAD 4acf6e3b8) -> IcsFeedClientTests 46 tests, 0 failures, incl. 50x refused Content-Length and 20x mid-stream refusal asserting 'larger than'; refusal stored before cancel and checked first in send() (code-reviewer: 0 findings)
- [ ] `IcsFeedClientTests` passes reliably inside the full `server/gradlew -p server check` run (several consecutive runs)

## Notes
- extended by dell26/agent-1: Fix + tests + review.md/tests.md committed (0 findings); AC1 verified. Left: AC2 needs 2+ more full 'cd server && ./gradlew check' runs (run 1 at 4acf6e3b8: IcsFeedClientTests 46/46 pass; 2 unrelated backup test failures from 'Disk quota exceeded' on host), then verify #2, write quality.json (reviewed_revision = HEAD, fixer not required), sh scripts/check.sh, done.
