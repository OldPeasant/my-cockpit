---
title: Backup contributor: contacts
status: in_progress
owner: dell26/agent-3
deps: [backup-contrib-core,contacts-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/contacts/backup,server/src/test/kotlin/ch/sonensei/cockpit/contacts/backup]
prio: 2
lease_until: 2026-10-22T02:36:14Z
---
## Goal
Contacts in backups.

## Acceptance criteria
- [ ] data/contacts.jsonl; readable/contacts/contacts.vcf (parses back)
- [ ] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding contacts with photos

## Notes
