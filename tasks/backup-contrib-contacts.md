---
title: Backup contributor: contacts
status: todo
owner:
deps: [backup-contrib-core,contacts-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/contacts/backup,server/src/test/kotlin/ch/sonensei/cockpit/contacts/backup]
prio: 2
---
## Goal
Contacts in backups.

## Acceptance criteria
- [ ] data/contacts.jsonl; readable/contacts/contacts.vcf (parses back)
- [ ] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding contacts with photos

## Notes
