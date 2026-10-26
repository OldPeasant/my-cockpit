---
title: BackupRoundTripTest leaks a 61 MB cockpit-round-trip-data temp dir per run, filling /tmp (Disk quota exceeded)
status: done
owner: dell26/agent-1
deps: []
touches: [server/src/test/kotlin/ch/sonensei/cockpit/backup]
prio: 2
lease_until: 
---
## Goal
`BackupRoundTripTest` creates `Files.createTempDirectory("cockpit-round-trip-data")` in its companion object and
never deletes it, so every server test run leaves about 61 MB in `/tmp`. On 2026-10-26 the host's `/tmp` tmpfs held
hundreds of these directories (13 of 16 GB). As a result, `BackupRoundTripTest` and `FilesBackupContributorTests`
(60 MB file) failed with `java.io.IOException: Disk quota exceeded` in every `./gradlew check` run.

## Acceptance criteria
- [x] The round-trip test's data dir is deleted after the test class runs (e.g. `@AfterAll` deleteRecursively or a JUnit `@TempDir`); a full `cd server && ./gradlew test` run leaves no new `cockpit-round-trip-data*` directory in the temp dir
  - verified (2026-10-26 07:32+0100 by dell26/agent-1): server/gradlew -p server test --rerun-tasks --init-script .tmp/tmpdir.gradle (test JVM java.io.tmpdir=.tmp/testtmp, starting empty) -> BUILD SUCCESSFUL, 342 result files 0 failures; .tmp/testtmp afterwards has no cockpit-round-trip-data* (only empty cockpit-backups*/cockpit-restores* work dirs, du 0 bytes). Fix: @AfterAll deleteRecursively in BackupRoundTripTest (8215e1cc7)
- [x] Other server tests that create large temp files/dirs (e.g. `FilesBackupContributorTests`, `cockpit-backups*`) clean them up too
  - verified (2026-10-26 07:32+0100 by dell26/agent-1): Same full run (java.io.tmpdir=.tmp/testtmp) -> no cockpit-*-data*, cockpit-files-backup-data*, cockpit-spa* left; all 13 companion temp dirs now deleted in @AfterAll (ae683aed6), per-test zips live in JUnit @TempDir; leftovers are 16 empty cockpit-backups*/cockpit-restores* dirs from main code (0 bytes, see assumption)

## Notes
- Existing leaked directories in the host's `/tmp` must be removed by a human (agents may not write outside their clone).
- ASSUMPTION (2026-10-26 07:24+0100 by dell26/agent-1): Criterion 2 names tests outside touches (files/, other modules' backup tests, core/spa): added the same @AfterAll deleteRecursively to every test companion that creates a temp dir (12 classes). Main-code BackupJobService/RestoreService 'cockpit-backups'/'cockpit-restores' work dirs left as is: their zips are already deleted on shutdown, only empty dirs remain.
- done by dell26/agent-1: `sh scripts/check.sh` passed in 84s on 461729810; criteria verified 2/2
