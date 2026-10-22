---
title: Backup contributor: contacts
status: in_progress
owner: dell26/agent-3
deps: [backup-contrib-core,contacts-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/contacts/backup,server/src/test/kotlin/ch/sonensei/cockpit/contacts/backup]
prio: 2
lease_until: 2026-10-22T02:56:08Z
---
## Goal
Contacts in backups.

## Acceptance criteria
- [ ] data/contacts.jsonl; readable/contacts/contacts.vcf (parses back)
- [ ] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding contacts with photos

## Notes
- ASSUMPTION (2026-10-22 04:21+0200 by dell26/agent-3): contacts.jsonl holds every contact fully, also those of external sources (GOOGLE/MICROSOFT/CARDDAV) and the Trash: no contact sync exists yet, and full rows keep links and are lossless; the vcf mirror only has contacts not in the Trash
- ASSUMPTION (2026-10-22 04:21+0200 by dell26/agent-3): readable contacts.vcf references photos as PHOTO;VALUE=uri:../../files/<path> (like note images) instead of embedding them: blobs belong to the files module, so the mirror stays small; made VCardService.toVCard public (outside touches, one visibility change) so the mirror writes exactly what the vCard export writes
