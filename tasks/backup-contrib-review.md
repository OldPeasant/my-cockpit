---
title: Backup contributor: weekly reviews
status: todo
owner:
deps: [review-api,backup-contrib-core]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/review/backup,server/src/test/kotlin/ch/sonensei/cockpit/review/backup]
prio: 3
---
## Goal
Weekly reviews (`weekly_review`, SPEC.md §8.6) in backups (SPEC.md §14), so "Last weekly review" and an open review survive a restore.

## Acceptance criteria
- [ ] data/weekly_reviews.jsonl (id, startedAt, completedAt, step, timestamps); restored with the at-most-one-open-review rule intact
- [ ] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding an open and a completed review

## Notes
Found while doing review-api: no backup task covered the review module.
