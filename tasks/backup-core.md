---
title: Backup export: zip format and orchestration
status: in_progress
owner: dell26/agent-4
deps: [p0-settings,p0-items-links,p0-crypto]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/backup,server/src/test/kotlin/ch/sonensei/cockpit/backup]
prio: 1
lease_until: 2026-10-20T16:42:29Z
releases: 0
extended: dell26/agent-4
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
