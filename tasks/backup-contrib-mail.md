---
title: Backup contributor: mail accounts
status: in_progress
owner: dell26/agent-2
deps: [backup-contrib-core,mail-model,backup-secrets]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/backup,server/src/test/kotlin/ch/sonensei/cockpit/mail/backup]
prio: 2
lease_until: 2026-10-21T22:29:47Z
---
## Goal
Mail config in backups (SPEC.md §10.2).

## Acceptance criteria
- [x] data/mail_accounts.json (config, secrets via the secrets mechanism), mail_message_refs.jsonl for linked mails only; no message content
  - verified (2026-10-22 00:04+0200 by dell26/agent-2): cd server && ./gradlew test --tests '*MailBackupContributorTests' -> 20 passed, 0 failed (mail_accounts.json without password/sync state, IMAP passwords only in secret mail_passwords and decryptable after import, refs only for linked/reminded mails with id/account/folder/Message-ID only, no subject/addresses/body)
- [x] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding an IMAP and an OAuth account and a link to a mail
  - verified (2026-10-22 00:04+0200 by dell26/agent-2): cd server && ./gradlew test --tests '*BackupRoundTripTest' (MailBackupFixture seeds an IMAP and an OAuth account and a link to a mail) -> 1 passed, 0 failed

## Notes
- ASSUMPTION (2026-10-21 23:22+0200 by dell26/agent-2): mail_accounts.json nests each account's identities and folders (path, role, role override; no UIDVALIDITY/UIDNEXT/MODSEQ/last sync, so the first sync after restore is a full window sync): SPEC lists only mail_accounts.json, and identities/folder role overrides are owner config
- ASSUMPTION (2026-10-21 23:22+0200 by dell26/agent-2): 'Linked mails' = mail_message rows referenced by a link (either end) or a reminder (item_type EMAIL); refs keep only id, timestamps, version, account, folder, Message-ID header (no subject/addresses/body) and are restored detached (uid null) so the header sync reattaches them by Message-ID. Linked messages older than the sync window are not retaken after restore (retakeLinked only runs on a UIDVALIDITY resync). The export reads core's link/reminder tables via SQL
- ASSUMPTION (2026-10-21 23:22+0200 by dell26/agent-2): IMAP passwords go to backup secret 'mail_passwords' (account id -> password); an IMAP account restored without it has a null password (login fails with AUTHENTICATION_FAILED until the owner enters it again); no readable mirror for mail
- ASSUMPTION (2026-10-21 23:37+0200 by dell26/agent-2): Review CR-1: linked mails older than the sync window are not reattached after a restore (first sync never runs retakeLinked). Kept the restore detached (no sentinel UIDVALIDITY, which would make pruneDetached delete reminder-only refs since isLinked ignores reminders) and added a restore warning; the sync-side fix is follow-up task mail-restore-retake-linked
