---
title: Backup export: zip format and orchestration
status: in_progress
owner: R1963MACOS/agent-2
deps: [p0-settings,p0-items-links,p0-crypto]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/backup,server/src/test/kotlin/ch/sonensei/cockpit/backup]
prio: 1
lease_until: 2026-10-20T14:01:51Z
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
