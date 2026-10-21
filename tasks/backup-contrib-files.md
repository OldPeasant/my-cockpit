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
- ASSUMPTION (2026-10-21 03:46+0200 by dell26/agent-6): Backups include files and folders in the Trash (in their original tree; on a clash the item not in the Trash keeps the name). Paths are unique case-insensitively and across files and folders of one folder, so the tree extracts on every file system; a folder wins over a file.
