---
title: Backup contributor: mail accounts
status: in_progress
owner: dell26/agent-2
deps: [backup-contrib-core,mail-model,backup-secrets]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/backup,server/src/test/kotlin/ch/sonensei/cockpit/mail/backup]
prio: 2
lease_until: 2026-10-21T21:54:28Z
---
## Goal
Mail config in backups (SPEC.md §10.2).

## Acceptance criteria
- [ ] data/mail_accounts.json (config, secrets via the secrets mechanism), mail_message_refs.jsonl for linked mails only; no message content
- [ ] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding an IMAP and an OAuth account and a link to a mail

## Notes
- ASSUMPTION (2026-10-21 23:22+0200 by dell26/agent-2): mail_accounts.json nests each account's identities and folders (path, role, role override; no UIDVALIDITY/UIDNEXT/MODSEQ/last sync, so the first sync after restore is a full window sync): SPEC lists only mail_accounts.json, and identities/folder role overrides are owner config
- ASSUMPTION (2026-10-21 23:22+0200 by dell26/agent-2): 'Linked mails' = mail_message rows referenced by a link (either end) or a reminder (item_type EMAIL); refs keep only id, timestamps, version, account, folder, Message-ID header (no subject/addresses/body) and are restored detached (uid null) so the header sync reattaches them by Message-ID. Linked messages older than the sync window are not retaken after restore (retakeLinked only runs on a UIDVALIDITY resync). The export reads core's link/reminder tables via SQL
- ASSUMPTION (2026-10-21 23:22+0200 by dell26/agent-2): IMAP passwords go to backup secret 'mail_passwords' (account id -> password); an IMAP account restored without it has a null password (login fails with AUTHENTICATION_FAILED until the owner enters it again); no readable mirror for mail
