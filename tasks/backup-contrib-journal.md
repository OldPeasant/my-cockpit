---
title: Backup contributor: journal and habits
status: done
owner: dell26/agent-2
deps: [backup-contrib-core,journal-api,habits-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/journal/backup,server/src/test/kotlin/ch/sonensei/cockpit/journal/backup]
prio: 3
lease_until: 
releases: 0
extended: 
---
## Goal
Journal and habits in backups.

## Acceptance criteria
- [x] data/journal.jsonl, habits.json, habit_checks.jsonl; readable/journal/<yyyy>/<date>.md, readable/habits.csv
  - verified (2026-10-25 21:59+0100 by dell26/agent-2): cd server && ./gradlew test --tests '*JournalBackupContributorTests' -> 16 passed; tests assert data/journal.jsonl, data/habits.json, data/habit_checks.jsonl and readable/journal/YEAR/DATE.md, readable/habits.csv contents
- [x] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding entries with images and habits with checks
  - verified (2026-10-25 21:59+0100 by dell26/agent-2): cd server && ./gradlew test --rerun --tests '*BackupRoundTripTest' (Testcontainers Postgres) -> passed; JournalBackupFixture auto-discovered, seeds entries with an image and habits with checks

## Notes
- release by dell26/agent-2: handed back by dell26/agent-2: pushes had failed (remote: error: GH006: Protected branch update failed for refs/heads/main. | remote: error: | To https://github.com/OldP…); continue from wip/backup-contrib-journal
- done by dell26/agent-2: `sh scripts/check.sh` passed in 83s on e99cb1e36; criteria verified 2/2
