---
title: Backup contributor: files
status: in_progress
owner: dell26/agent-6
deps: [backup-contrib-core,files-preview]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/files/backup,server/src/test/kotlin/ch/sonensei/cockpit/files/backup]
prio: 1
lease_until: 2026-10-21T02:03:53Z
---
## Goal
Files in backups (SPEC.md §10.2).

## Acceptance criteria
- [ ] data/files.jsonl, folders.json; files/ holds blobs in the original tree with original names (clashes → ' (2)', mapping in files.jsonl); streamed, constant memory for large files
- [ ] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding nested folders, name clashes and a 60 MB file

## Notes
