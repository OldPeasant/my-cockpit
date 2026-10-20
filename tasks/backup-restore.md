---
title: Backup restore: validation, full replace, CLI
status: in_progress
owner: dell26/agent-4
deps: [backup-core]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/backup,server/src/test/kotlin/ch/sonensei/cockpit/backup]
prio: 1
lease_until: 2026-10-21T00:11:17Z
releases: 1
extended: 
---
## Goal
Restore from a backup zip (SPEC.md §10.1).

## Acceptance criteria
- [x] `POST /api/v1/restore` (upload) returns a validation report (format version, counts, sha256 mismatches, warnings) without changing data; `POST /api/v1/restore/{id}/confirm` makes a pre-restore backup to `${COCKPIT_DATA_DIR}/pre-restore/` and replaces all data transactionally
  - verified (2026-10-21 01:51+0200 by dell26/agent-4): server/gradlew -p server test --tests 'ch.sonensei.cockpit.backup.*' (RestoreWebTests: upload report w/ format version, counts, sha256 problems, warnings, data unchanged; confirm writes pre-restore zip and replaces all data in one tx, pre-restore zip restores replaced data) against Testcontainers Postgres -> 132 tests, 0 failures
- [x] `java -jar cockpit-server.jar --restore=/path/backup.zip` restores on a fresh install and exits; the setup flow is then skipped
  - verified (2026-10-21 01:52+0200 by dell26/agent-4): bootJar cockpit-server-0.0.1-SNAPSHOT.jar --restore=<backup.zip> against a fresh Postgres container (test-writer, .tmp/e2e) -> exit 0, pre-restore written, 'setup is skipped'; normal start then GET /api/v1/setup/status -> setupRequired:false; missing file exit 2, corrupt zip exit 1; plus RestoreCliTests (Testcontainers) pass
- [x] Corrupt zip / wrong format version / sha mismatch → rejected with a clear report, data untouched (tests)
  - verified (2026-10-21 01:52+0200 by dell26/agent-4): RestoreWebTests + ZipBackupReaderValidationTests + RestoreCliTests: corrupt zip / wrong format version / sha256 mismatch / empty upload -> valid:false with problem, no id, data unchanged; server/gradlew -p server test --tests 'ch.sonensei.cockpit.backup.*' -> pass
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.backup.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-21 00:09+0200 by dell26/agent-6): Upload is a raw application/zip (or octet-stream) request body, not multipart, so large backups are not cut by Spring's 1 MB multipart limit; it always answers 200 with a report (valid:false + problems for corrupt zip / wrong format version / sha256 mismatch, no id). One pending upload at a time, in a temp dir, in memory only; a new upload or a confirm discards it.
- ASSUMPTION (2026-10-21 00:09+0200 by dell26/agent-6): Confirm empties every table but flyway_schema_history (sessions and devices too, so clients sign in again) and imports in one transaction; the data directory (blobs) is moved into a .restore-<id> stash meanwhile and moved back on failure. A failing import answers 422 Problem Details with a 'problems' list (file/line only, never content); 409 while a backup runs (BackupJobService.exclusive).
- ASSUMPTION (2026-10-21 00:09+0200 by dell26/agent-6): CLI --restore runs the full app context with server.port=-1 (no listener; WebApplicationType.NONE breaks the security config), always makes the pre-restore backup and replaces whatever is there (no refusal on a non-empty install), prints the report and exits 0 restored / 1 invalid or failed / 2 no file. 'Setup skipped' follows from the restored active owner account (SetupService.isSetupRequired).
- ASSUMPTION (2026-10-21 00:09+0200 by dell26/agent-6): Restore warnings come from a new BackupContributor.restoreWarnings hook (UserBackupContributor: no owner account / setup unfinished / TOTP secret not in backup with unused recovery code count) plus an app-version mismatch warning. Small edit to core/backupcontrib/UserBackupContributor.kt and CockpitServerApplication.main outside the task's touches.
- release by dell26/agent-6: Implementation complete and committed (upload/confirm endpoints, --restore CLI, rejection tests, openapi + web client regenerated); scripts/check.sh passes on b1b0dfdf. reviews/backup-restore/review.md (0 P0-P2, 1 deferred P3: double hashing on upload) and fixes.md exist. Last commit b1b0dfdf added extra tests to RestoreWebTests/ZipBackupReaderValidationTests after that review. LEFT: 1) run test-writer -> reviews/backup-restore/tests.md (missing, incl. task-scoped coverage); 2) re-run code-reviewer on final code if test changes count as code changes (docs/QUALITY.md freshness); 3) run ./gradlew test --tests 'ch.sonensei.cockpit.backup.*' --tests '*OpenApiContractTest' and record tasks.py verify for all 5 criteria; 4) write quality.json (CR-3/P3 disposition: deferred), commit, tasks.py done.
