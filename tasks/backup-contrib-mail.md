---
title: Backup contributor: mail accounts
status: in_progress
owner: dell26/agent-2
deps: [backup-contrib-core,mail-model,backup-secrets]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/backup,server/src/test/kotlin/ch/sonensei/cockpit/mail/backup]
prio: 2
lease_until: 2026-10-21T21:37:15Z
---
## Goal
Mail config in backups (SPEC.md §10.2).

## Acceptance criteria
- [ ] data/mail_accounts.json (config, secrets via the secrets mechanism), mail_message_refs.jsonl for linked mails only; no message content
- [ ] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding an IMAP and an OAuth account and a link to a mail

## Notes
