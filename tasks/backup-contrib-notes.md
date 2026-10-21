---
title: Backup contributor: notes
status: in_progress
owner: R1963MACOS/agent-1
deps: [backup-contrib-core,notes-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/notes/backup,server/src/test/kotlin/ch/sonensei/cockpit/notes/backup]
prio: 1
lease_until: 2026-10-21T03:58:43Z
---
## Goal
Notes in backups (SPEC.md §10.2).

## Acceptance criteria
- [ ] data/notes.jsonl, note_revisions.jsonl; readable/notes/<yyyy-mm-dd>-<slug>.md with front matter (id, tags, links) and image links rewritten to `../../files/...`
- [ ] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding notes with images, tags and revisions

## Notes
- ASSUMPTION (2026-10-21 05:41+0200 by R1963MACOS/agent-1): readable/notes/ mirrors only notes not in the Trash (data/ has all, Trash included); file date = creation day in UTC, slug = title or derived title in lower-case ASCII (accents dropped, max 60 chars, 'note' when empty), clashes get -2, -3 (older note keeps the name).
- ASSUMPTION (2026-10-21 05:41+0200 by R1963MACOS/agent-1): Front matter: id, title, tags, links (type:id, both directions, each once, oldest first), created, updated, pinned, archived as YAML. Image refs cockpit-file:{id} become ../../files/<path> with each segment percent-encoded; refs to files not in the backup stay unchanged.
- ASSUMPTION (2026-10-21 05:41+0200 by R1963MACOS/agent-1): To get the files/ paths of images without duplicating the clash rules, FilesBackupContributor got a public paths(fileIds) method (small change outside this task's touches, in files/backup).
