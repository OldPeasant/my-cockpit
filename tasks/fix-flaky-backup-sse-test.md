---
title: BackupEventsStreamTests flakes under full-suite load (step was 'bookmarks: data', expected 'controllable: data')
status: in_progress
owner: dell26/agent-1
deps: []
touches: [server/src/test/kotlin/ch/sonensei/cockpit/backup]
prio: 2
lease_until: 2026-10-26T13:57:55Z
---
## Goal
`backup.BackupEventsStreamTests` "progress of a running backup reaches the SSE stream before the export
finishes" failed once in `sh scripts/check.sh` on 2026-10-26 (BackupEventsStreamTests.kt:92: expected step
`controllable: data` but was `bookmarks: data`) and passes when run alone. The test assumes the first UPDATED
event belongs to the gated `controllable` contributor, but other contributors' steps can be reported first.

## Acceptance criteria
- [x] the test waits for the gated contributor's step (or otherwise no longer depends on contributor order)
  - verified (2026-10-26 14:42+0100 by dell26/agent-1): BackupEventsStreamTests now reads UPDATED SSE events until the job is at 'controllable: data' with stepsDone+1 == events read, and drains stepsTotal+2-read; reviewed against BackupService.export ordering by code-reviewer and test-writer -> no dependency on contributor order
- [ ] `cd server && ./gradlew test --tests '*BackupEventsStreamTests'` passes, and the full `./gradlew check` passes

## Notes
