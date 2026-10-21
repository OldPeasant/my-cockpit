---
title: Backup contributor: files
status: in_progress
owner: dell26/agent-1
deps: [backup-contrib-core,files-preview]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/files/backup,server/src/test/kotlin/ch/sonensei/cockpit/files/backup]
prio: 1
lease_until: 2026-10-21T03:21:31Z
releases: 1
extended: 
---
## Goal
Files in backups (SPEC.md §10.2).

## Acceptance criteria
- [x] data/files.jsonl, folders.json; files/ holds blobs in the original tree with original names (clashes → ' (2)', mapping in files.jsonl); streamed, constant memory for large files
  - verified (2026-10-21 04:58+0200 by dell26/agent-1): cd server && ./gradlew test --tests '*FilesBackup*' -> FilesBackupContributorTests 24/24 pass (data/folders.json + data/files.jsonl fields, files/ original tree + names, ' (2)'/' (3)' clashes incl. case/folder-vs-file/trash with path in files.jsonl, 60 MB blob export+import with thread allocation < 15 MB)
- [x] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding nested folders, name clashes and a 60 MB file
  - verified (2026-10-21 04:58+0200 by dell26/agent-1): cd server && ./gradlew test --tests '*BackupRoundTripTest' (Testcontainers Postgres) -> 1/1 pass, FilesBackupFixture seeds nested folders, name clashes and a 60 MB file

## Notes
- ASSUMPTION (2026-10-21 03:46+0200 by dell26/agent-6): Backups include files and folders in the Trash (in their original tree; on a clash the item not in the Trash keeps the name). Paths are unique case-insensitively and across files and folders of one folder, so the tree extracts on every file system; a folder wins over a file.
- ASSUMPTION (2026-10-21 03:46+0200 by dell26/agent-6): files.jsonl does not store the blob storage key: restore writes each blob under a new key and checks its size and SHA-256. Thumbnails (cache) and unfinished upload sessions are not backed up; restore re-indexes active files/folders for search. No readable/ mirror: files/ already is the readable form.
- ASSUMPTION (2026-10-21 04:10+0200 by dell26/agent-6): Exported files/ paths are unique regardless of case (folders and files of a folder together); they are not sanitized for Windows-reserved characters/names or Unicode normalization, because the criterion requires original names and restore reads the zip, not an extracted tree (corrects assumption #1).
- release by dell26/agent-6: Implementation, tests, review, fixes all done and committed (last code commit 1820c594 tests; final review b986d7cd: 0 findings; tests.md/fixes.md/review.md in reviews/backup-contrib-files/). sh scripts/check.sh passes. Left only finalization: (1) tasks.py verify for criteria 1+2 using evidence in review.md/tests.md (./gradlew test --tests '*FilesBackup*' --tests '*BackupRoundTripTest' -> 24+1 tests pass, 60MB fixture, memory checks); (2) write reviews/backup-contrib-files/quality.json per docs/QUALITY.md (reviewed_revision = HEAD code revision, fixer ran for CR-1..CR-5, all resolved); (3) commit and tasks.py done.
