---
title: Backup contributors: core entities
status: in_progress
owner: dell26/agent-5
deps: [backup-core,reminders-core]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib,server/src/test/kotlin/ch/sonensei/cockpit/core/backupcontrib]
prio: 1
lease_until: 2026-10-20T20:56:31Z
---
## Goal
Back up the user, settings, links, reminders (SPEC.md §10.2).

## Acceptance criteria
- [ ] Contributors for user (password hash, TOTP via secrets mechanism), settings, links, reminders, with readable README sections where useful
- [ ] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding a user, settings, links and reminders

## Notes
