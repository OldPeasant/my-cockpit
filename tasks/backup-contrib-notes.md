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
