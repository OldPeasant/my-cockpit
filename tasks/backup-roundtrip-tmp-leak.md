---
title: BackupRoundTripTest leaks a temp data dir per run
status: done
owner: dell26/agent-1
deps: []
touches: [server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupRoundTripTest.kt]
prio: 1
lease_until: 
---
## Goal
`BackupRoundTripTest` creates its data dir with `Files.createTempDirectory("cockpit-round-trip-data")` in its
companion object and never deletes it (`wipe()` only empties it between tests). Every `./gradlew check` and so
every `.taskcheck` leaves a ~61 MB `/tmp/cockpit-round-trip-data*` behind. On 2026-10-26 226 of them (13 GB) had
filled the host's 16 GB /tmp tmpfs; `.taskcheck` then failed in BackupRoundTripTest and FilesBackupContributorTests
with `java.io.IOException: Disk quota exceeded`, and gtd-delete-reassign had to be put on hold. Agents can't
delete files outside their clone, so only a human can clean up after a leak: the test must not leak.

## Acceptance criteria
- [x] `BackupRoundTripTest` deletes its data dir after the test class, also when a test fails (e.g. `@AfterAll`
  with `deleteRecursively()`, or a JUnit `@TempDir`); the backup/restore round trip it tests is unchanged
  - verified (2026-10-26 06:11+0100 by dell26/agent-1): BackupRoundTripTest.kt: companion @JvmStatic @AfterAll deleteDataDir() -> dataDir.toFile().deleteRecursively() (JUnit runs @AfterAll also after failed tests; test body/wipe/property source unchanged, confirmed by code-reviewer); round-trip test still passes (see 2)
- [x] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.backup.*'` (Testcontainers Postgres) passes, and
  the number of `/tmp/cockpit-round-trip-data*` dirs is the same before and after the run
  - verified (2026-10-26 06:11+0100 by dell26/agent-1): server/gradlew -p server test --tests 'ch.sonensei.cockpit.backup.*' (Testcontainers Postgres via podman) -> BUILD SUCCESSFUL, 14 classes/176 tests, 0 failures incl. BackupRoundTripTest; python glob count of /tmp/cockpit-round-trip-data* = 10 before and 10 after
- [x] `sh scripts/check.sh` passes
  - verified (2026-10-26 06:11+0100 by dell26/agent-1): sh scripts/check.sh -> exit 0, '== all checks passed'

## Notes
- done by dell26/agent-1: `sh scripts/check.sh` passed in 88s on 469c5a600; criteria verified 3/3
