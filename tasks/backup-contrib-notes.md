---
title: Backup contributor: notes
status: in_progress
owner: dell26/agent-6
deps: [backup-contrib-core,notes-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/notes/backup,server/src/test/kotlin/ch/sonensei/cockpit/notes/backup]
prio: 1
lease_until: 2026-10-21T05:50:56Z
releases: 0
extended: dell26/agent-6
---
## Goal
Notes in backups (SPEC.md §10.2).

## Acceptance criteria
- [x] data/notes.jsonl, note_revisions.jsonl; readable/notes/<yyyy-mm-dd>-<slug>.md with front matter (id, tags, links) and image links rewritten to `../../files/...`
  - verified (2026-10-21 07:15+0200 by dell26/agent-6): cd server && ./gradlew test --tests '*NotesBackupContributorTests' -> 18 tests, 0 failures (data/notes.jsonl + note_revisions.jsonl, readable/notes/<date>-<slug>.md, front matter id/tags/links, cockpit-file: -> ../../files/... resolved against zip entries)
- [x] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding notes with images, tags and revisions
  - verified (2026-10-21 07:15+0200 by dell26/agent-6): cd server && ./gradlew test --tests '*BackupRoundTripTest' (Testcontainers Postgres, NotesBackupFixture with image, tags, revisions) -> 1 test, 0 failures

## Notes
- ASSUMPTION (2026-10-21 05:41+0200 by R1963MACOS/agent-1): readable/notes/ mirrors only notes not in the Trash (data/ has all, Trash included); file date = creation day in UTC, slug = title or derived title in lower-case ASCII (accents dropped, max 60 chars, 'note' when empty), clashes get -2, -3 (older note keeps the name).
- ASSUMPTION (2026-10-21 05:41+0200 by R1963MACOS/agent-1): Front matter: id, title, tags, links (type:id, both directions, each once, oldest first), created, updated, pinned, archived as YAML. Image refs cockpit-file:{id} become ../../files/<path> with each segment percent-encoded; refs to files not in the backup stay unchanged.
- ASSUMPTION (2026-10-21 05:41+0200 by R1963MACOS/agent-1): To get the files/ paths of images without duplicating the clash rules, FilesBackupContributor got a public paths(fileIds) method (small change outside this task's touches, in files/backup).
- release by R1963MACOS/agent-1: handed off by R1963MACOS/agent-1: You've hit your session limit · resets 6:20am (Europe/Zurich)
- ASSUMPTION (2026-10-21 05:52+0200 by dell26/agent-6): The notes mirror reads the core link table directly (read-only, inside the backup's snapshot transaction) to list a note's links in its front matter, instead of a new bulk-read API in core: same pattern as the files paths() exception, keeps the change inside this task's touches.
- extended by dell26/agent-6: Implementation, tests and code review (0 findings, CR-1/CR-2 resolved, fixes.md) are committed, HEAD d75b8db2. Left: test-writer on final code to write reviews/backup-contrib-notes/tests.md with coverage; tasks.py verify for both criteria (incl. ./gradlew test --tests '*BackupRoundTripTest'); quality.json; .taskcheck (server part passed, the full check took >10 min here); done.
