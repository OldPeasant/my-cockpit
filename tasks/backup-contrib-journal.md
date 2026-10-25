---
title: Backup contributor: journal and habits
status: in_progress
owner: dell26/agent-2
deps: [backup-contrib-core,journal-api,habits-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/journal/backup,server/src/test/kotlin/ch/sonensei/cockpit/journal/backup]
prio: 3
lease_until: 2026-10-25T20:27:27Z
---
## Goal
Journal and habits in backups.

## Acceptance criteria
- [ ] data/journal.jsonl, habits.json, habit_checks.jsonl; readable/journal/<yyyy>/<date>.md, readable/habits.csv
- [ ] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding entries with images and habits with checks

## Notes
