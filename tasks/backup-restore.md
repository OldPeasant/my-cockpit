---
title: Backup restore: validation, full replace, CLI
status: in_progress
owner: dell26/agent-6
deps: [backup-core]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/backup,server/src/test/kotlin/ch/sonensei/cockpit/backup]
prio: 1
lease_until: 2026-10-20T22:16:47Z
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
