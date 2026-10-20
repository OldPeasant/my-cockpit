---
title: Backup contributor: bookmarks
status: todo
owner:
deps: [backup-contrib-core,bookmarks-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/backup,server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/backup]
prio: 3
---
## Goal
Bookmarks in backups.

## Acceptance criteria
- [ ] data/bookmarks.jsonl; readable/bookmarks.html in Netscape format (imports in Firefox — parse test)
- [ ] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding bookmarks

## Notes
