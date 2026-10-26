---
title: IcsFeedClient: refusing an oversized Content-Length sometimes surfaces as IOException ('could not be reached') instead of 'larger than'
status: in_progress
owner: dell26/agent-1
deps: []
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics,server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/ics]
prio: 2
lease_until: 2026-10-26T06:39:08Z
extended: 
hold: 
releases: 0
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
- [x] `IcsFeedClientTests` passes reliably inside the full `server/gradlew -p server check` run (several consecutive runs)
  - verified (2026-10-26 03:48+0100 by dell26/agent-1): server/gradlew -p server check --rerun-tasks, 3 consecutive runs at 03a5d5f63 (plus 1 earlier at 4acf6e3b8) -> IcsFeedClientTests 46/46 pass in every run (TEST-...IcsFeedClientTests.xml failures=0 errors=0); each build exits 1 only because BackupRoundTripTest and FilesBackupContributorTests fail with 'java.io.IOException: Disk quota exceeded' (host /tmp tmpfs full of leaked test dirs, task backup-round-trip-tmp-leak)

## Notes
- extended by dell26/agent-1: Fix + tests + review.md/tests.md committed (0 findings); AC1 verified. Left: AC2 needs 2+ more full 'cd server && ./gradlew check' runs (run 1 at 4acf6e3b8: IcsFeedClientTests 46/46 pass; 2 unrelated backup test failures from 'Disk quota exceeded' on host), then verify #2, write quality.json (reviewed_revision = HEAD, fixer not required), sh scripts/check.sh, done.
- ASSUMPTION (2026-10-26 03:48+0100 by dell26/agent-1): AC2 counted as met: IcsFeedClientTests passed in 4 full 'gradlew -p server check' runs, though each build failed on 2 unrelated backup tests from 'Disk quota exceeded' (host /tmp full of BackupRoundTripTest's leaked dirs; new task backup-round-trip-tmp-leak)
- hold by dell26/agent-1: Task is complete: both criteria verified, review/test/quality evidence committed (cbad8b408). done refused only because .taskcheck fails in BackupRoundTripTest and FilesBackupContributorTests with 'java.io.IOException: Disk quota exceeded': host /tmp (tmpfs 16G, 13G used) is full of leaked cockpit-round-trip-data* dirs (see task backup-round-trip-tmp-leak). A human must delete /tmp/cockpit-round-trip-data* (and cockpit-backups*), then unhold; the next owner only needs to run 'python tasks.py done ics-max-size-flaky-test'.
