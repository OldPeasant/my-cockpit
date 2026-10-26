---
title: Backup contributor: weekly reviews
status: in_progress
owner: dell26/agent-2
deps: [review-api,backup-contrib-core]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/review/backup,server/src/test/kotlin/ch/sonensei/cockpit/review/backup]
prio: 3
lease_until: 2026-10-26T11:55:10Z
---
## Goal
Weekly reviews (`weekly_review`, SPEC.md §8.6) in backups (SPEC.md §14), so "Last weekly review" and an open review survive a restore.

## Acceptance criteria
- [x] data/weekly_reviews.jsonl (id, startedAt, completedAt, step, timestamps); restored with the at-most-one-open-review rule intact
  - verified (2026-10-26 12:27+0100 by dell26/agent-2): cd server && ./gradlew test --tests '*ReviewBackupContributorTests' -> 11/11 passed (export of data/weekly_reviews.jsonl with id/createdAt/updatedAt/version/startedAt/completedAt/step; exact restore; >1 open review or unknown step rejected at RestoreService.validate and import, no rows written)
- [x] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding an open and a completed review
  - verified (2026-10-26 12:27+0100 by dell26/agent-2): cd server && ./gradlew test --tests '*BackupRoundTripTest' (with ReviewBackupFixture seeding 2 completed + 1 open review) -> 1/1 passed; full server suite 4776 tests 0 failures

## Notes
Found while doing review-api: no backup task covered the review module.
- ASSUMPTION (2026-10-26 11:44+0100 by dell26/agent-2): Weekly reviews go to data/weekly_reviews.jsonl as the task says, not weekly_reviews.json as SPEC 10.2 lists; no readable mirror (SPEC lists none, a review has no content). A backup with more than one open review or an unknown step is rejected as invalid at upload and import rather than repaired.
