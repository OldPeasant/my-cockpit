---
title: BackupRoundTripTest leaks a 61 MB cockpit-round-trip-data temp dir per run, filling /tmp (Disk quota exceeded)
status: in_progress
owner: dell26/agent-1
deps: []
touches: [server/src/test/kotlin/ch/sonensei/cockpit/backup]
prio: 2
lease_until: 2026-10-26T06:44:13Z
---
## Goal
`BackupRoundTripTest` creates `Files.createTempDirectory("cockpit-round-trip-data")` in its companion object and
never deletes it, so every server test run leaves about 61 MB in `/tmp`. On 2026-10-26 the host's `/tmp` tmpfs held
hundreds of these directories (13 of 16 GB). As a result, `BackupRoundTripTest` and `FilesBackupContributorTests`
(60 MB file) failed with `java.io.IOException: Disk quota exceeded` in every `./gradlew check` run.

## Acceptance criteria
- [ ] The round-trip test's data dir is deleted after the test class runs (e.g. `@AfterAll` deleteRecursively or a JUnit `@TempDir`); a full `cd server && ./gradlew test` run leaves no new `cockpit-round-trip-data*` directory in the temp dir
- [ ] Other server tests that create large temp files/dirs (e.g. `FilesBackupContributorTests`, `cockpit-backups*`) clean them up too

## Notes
- Existing leaked directories in the host's `/tmp` must be removed by a human (agents may not write outside their clone).
