---
title: Backup secrets with passphrase
status: todo
owner:
deps: [backup-restore]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/backup,server/src/test/kotlin/ch/sonensei/cockpit/backup]
prio: 1
---
## Goal
Secrets in backups (SPEC.md §10.2).

## Acceptance criteria
- [ ] With a passphrase, secrets (TOTP secret, IMAP passwords, OAuth tokens) are exported to `data/secrets.enc.json` (Argon2id-derived key, AES-GCM); without, omitted
- [ ] After restoring without secrets, accounts are flagged 'reconnect required' and the next login forces TOTP re-enrollment (test)
- [ ] Wrong passphrase → validation error, nothing restored
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.backup.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
