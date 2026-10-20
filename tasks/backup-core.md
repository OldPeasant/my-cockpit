---
title: Backup export: zip format and orchestration
status: in_progress
owner: dell26/agent-5
deps: [p0-settings,p0-items-links,p0-crypto]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/backup,server/src/test/kotlin/ch/sonensei/cockpit/backup]
prio: 1
lease_until: 2026-10-20T20:31:14Z
releases: 1
extended: 
---
## Goal
Backup export in format version 1 (SPEC.md §10.2).

## Acceptance criteria
- [ ] `BackupContributor` interface (export canonical JSON/JSONL, readable mirrors, import) with ordering; `BackupFixture` interface for tests
- [ ] `POST /api/v1/backup` starts an async job (progress via SSE), `GET /api/v1/backup/{id}/download` streams the zip: README.md, manifest.json (formatVersion, appVersion, createdAt, counts, sha256 per file), data/, files/, readable/
- [ ] `BackupRoundTripTest` harness: seeds all `BackupFixture`s, exports, wipes DB + data dir, imports, deep-compares canonical JSON and file hashes
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.backup.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- release by R1963MACOS/agent-2: handed off by R1963MACOS/agent-2: You've hit your session limit · resets 7:50pm (Europe/Zurich)
- ASSUMPTION (2026-10-20 17:14+0200 by dell26/agent-4): Backup jobs and finished zips live in memory/temp dir only (one job at a time, a new backup discards earlier ones); progress is change events of type BACKUP on /api/v1/events, clients refetch GET /api/v1/backup/{id}. Passphrase/secrets.enc.json is left to the restore/secrets tasks.
- extended by dell26/agent-4: Implementation complete and rebased on main (contributor/fixture interfaces, zip v1 writer/reader, async job + SSE events, download, round-trip harness, OpenAPI regenerated); backup.* (65) + OpenApiContractTest pass. Left: fix review CR-1 (BackupJobService.run catches only Exception; catch Throwable and guard deleteIfExists so the job always ends FAILED) and CR-2 (KDoc [CanonicalJson] in ZipBackupWriter); then test-writer (tests.md missing) + code-reviewer on the final revision, fixes.md, verify #1-#5, .taskcheck, quality.json, done.
- ASSUMPTION (2026-10-20 18:43+0200 by dell26/agent-4): Review CR-3 (P3): ZipBackupReader passes a JSON null .jsonl line as null and lets malformed lines surface as raw Jackson errors; only reachable with a hand-edited zip, import rolls back. Deferred to backup-restore, which owns the restore validation report.
- release by dell26/agent-4: Implementation complete, squashed into one commit on top of main (merge + regenerating openapi.yaml/web client gave no diff). Reports in reviews/backup-core/: review.md (final: 0 P0/P1/P2, 1 P3 = CR-3, deferred to backup-restore via assume), tests.md, fixes.md (CR-1, CR-2 fixed). sh scripts/check.sh passed before the final merge. Left: (1) reports name revision 30ea3b6 (pre-squash hash, backup code identical), so reviewed_revision is stale per docs/QUALITY.md: rerun test-writer + code-reviewer on HEAD; (2) run 'cd server && ./gradlew test --tests ch.sonensei.cockpit.backup.*' + OpenApiContractTest, record verify #1-#5; (3) write reviews/backup-core/quality.json (fixer ran for CR-1/CR-2; CR-3 deferred), commit; (4) .taskcheck and tasks.py done.
- ASSUMPTION (2026-10-20 19:16+0200 by dell26/agent-7): Review CR-4 (P3): ZipBackupReader manifest with null entries in files[] may surface as raw NPE instead of InvalidBackupException; only reachable with a hand-edited zip. Deferred to backup-restore together with CR-3 (restore validation report).
- release by R1963MACOS/agent-2: handed off by R1963MACOS/agent-2: You've hit your session limit · resets 12:50am (Europe/Zurich)
