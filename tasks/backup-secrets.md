---
title: Backup secrets with passphrase
status: in_progress
owner: dell26/agent-1
deps: [backup-restore,backup-contrib-core]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/backup,server/src/test/kotlin/ch/sonensei/cockpit/backup,server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib,server/src/test/kotlin/ch/sonensei/cockpit/core/backupcontrib,server/src/main/kotlin/ch/sonensei/cockpit/core/auth,server/src/test/kotlin/ch/sonensei/cockpit/core/auth]
prio: 1
lease_until: 2026-10-21T01:15:02Z
---
## Goal
Secrets in backups (SPEC.md §10.2).

## Acceptance criteria
- [ ] With a passphrase, secrets (TOTP secret, IMAP passwords, OAuth tokens) are exported to `data/secrets.enc.json` (Argon2id-derived key, AES-GCM); without, omitted
- [ ] After restoring without secrets, accounts are flagged 'reconnect required' and the next login forces TOTP re-enrollment (test)
- [ ] Wrong passphrase → validation error, nothing restored
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.backup.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
- From backup-contrib-core: `UserBackupContributor` (core/backupcontrib) omits the TOTP secret and imports a fresh random one while keeping `totp_confirmed_at`, so a restored owner can only sign in with recovery codes. This task should add the TOTP secret to `secrets.enc.json` and provide the forced TOTP re-enrollment (touches core/backupcontrib and core/auth).
