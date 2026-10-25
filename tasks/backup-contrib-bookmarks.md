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
- [x] data/bookmarks.jsonl; readable/bookmarks.html in Netscape format (imports in Firefox — parse test)
  - verified (2026-10-25 21:14+0100 by dell26/agent-1): cd server && ./gradlew test --tests '*BookmarksBackupContributorTests' -> 7/7 pass: data/bookmarks.jsonl with all fields + Trash; readable/bookmarks.html (Netscape DOCTYPE) parsed with jsoup walking DL/DT>A/DD like Firefox BookmarkHTMLUtils -> urls, titles, ADD_DATE, LAST_MODIFIED, TAGS, ICON_URI, TOREAD, descriptions read back (no real Firefox run)
- [x] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding bookmarks
  - verified (2026-10-25 21:15+0100 by dell26/agent-1): cd server && ./gradlew test --tests '*BackupRoundTripTest' -> 1/1 pass with BookmarksBackupFixture seeding 5 bookmarks (full, minimal, read, pending, trashed); also ./gradlew check -> BUILD SUCCESSFUL

## Notes
- ASSUMPTION (2026-10-25 21:05+0100 by dell26/agent-1): readable/bookmarks.html: flat list (no folders), newest first, without the Trash; tags in TAGS with a comma inside a tag replaced by a space (format has no escape); read-later unread ones get TOREAD="1"; ICON_URI for the favicon; snapshots not linked in the mirror
