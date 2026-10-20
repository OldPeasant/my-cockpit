---
title: Backup restore: validation, full replace, CLI
status: in_progress
owner: dell26/agent-6
deps: [backup-core]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/backup,server/src/test/kotlin/ch/sonensei/cockpit/backup]
prio: 1
lease_until: 2026-10-20T23:17:17Z
---
## Goal
Restore from a backup zip (SPEC.md §10.1).

## Acceptance criteria
- [ ] `POST /api/v1/restore` (upload) returns a validation report (format version, counts, sha256 mismatches, warnings) without changing data; `POST /api/v1/restore/{id}/confirm` makes a pre-restore backup to `${COCKPIT_DATA_DIR}/pre-restore/` and replaces all data transactionally
- [ ] `java -jar cockpit-server.jar --restore=/path/backup.zip` restores on a fresh install and exits; the setup flow is then skipped
- [ ] Corrupt zip / wrong format version / sha mismatch → rejected with a clear report, data untouched (tests)
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.backup.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-21 00:09+0200 by dell26/agent-6): Upload is a raw application/zip (or octet-stream) request body, not multipart, so large backups are not cut by Spring's 1 MB multipart limit; it always answers 200 with a report (valid:false + problems for corrupt zip / wrong format version / sha256 mismatch, no id). One pending upload at a time, in a temp dir, in memory only; a new upload or a confirm discards it.
- ASSUMPTION (2026-10-21 00:09+0200 by dell26/agent-6): Confirm empties every table but flyway_schema_history (sessions and devices too, so clients sign in again) and imports in one transaction; the data directory (blobs) is moved into a .restore-<id> stash meanwhile and moved back on failure. A failing import answers 422 Problem Details with a 'problems' list (file/line only, never content); 409 while a backup runs (BackupJobService.exclusive).
- ASSUMPTION (2026-10-21 00:09+0200 by dell26/agent-6): CLI --restore runs the full app context with server.port=-1 (no listener; WebApplicationType.NONE breaks the security config), always makes the pre-restore backup and replaces whatever is there (no refusal on a non-empty install), prints the report and exits 0 restored / 1 invalid or failed / 2 no file. 'Setup skipped' follows from the restored active owner account (SetupService.isSetupRequired).
- ASSUMPTION (2026-10-21 00:09+0200 by dell26/agent-6): Restore warnings come from a new BackupContributor.restoreWarnings hook (UserBackupContributor: no owner account / setup unfinished / TOTP secret not in backup with unused recovery code count) plus an app-version mismatch warning. Small edit to core/backupcontrib/UserBackupContributor.kt and CockpitServerApplication.main outside the task's touches.
