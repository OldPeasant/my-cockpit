---
title: Backup contributor: bookmarks
status: in_progress
owner: dell26/agent-1
deps: [backup-contrib-core,bookmarks-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/backup,server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/backup]
prio: 3
lease_until: 2026-10-25T20:32:15Z
---
## Goal
Bookmarks in backups.

## Acceptance criteria
- [ ] data/bookmarks.jsonl; readable/bookmarks.html in Netscape format (imports in Firefox — parse test)
- [ ] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding bookmarks

## Notes
- ASSUMPTION (2026-10-25 21:05+0100 by dell26/agent-1): readable/bookmarks.html: flat list (no folders), newest first, without the Trash; tags in TAGS with a comma inside a tag replaced by a space (format has no escape); read-later unread ones get TOREAD="1"; ICON_URI for the favicon; snapshots not linked in the mirror
