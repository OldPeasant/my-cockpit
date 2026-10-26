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
- [ ] the test waits for the gated contributor's step (or otherwise no longer depends on contributor order)
- [ ] `cd server && ./gradlew test --tests '*BackupEventsStreamTests'` passes, and the full `./gradlew check` passes

## Notes
