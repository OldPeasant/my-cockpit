---
title: Backup secrets with passphrase
status: in_progress
owner: dell26/agent-1
deps: [backup-restore,backup-contrib-core]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/backup,server/src/test/kotlin/ch/sonensei/cockpit/backup,server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib,server/src/test/kotlin/ch/sonensei/cockpit/core/backupcontrib,server/src/main/kotlin/ch/sonensei/cockpit/core/auth,server/src/test/kotlin/ch/sonensei/cockpit/core/auth]
prio: 1
lease_until: 2026-10-21T02:05:04Z
extended: dell26/agent-1
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
- extended by dell26/agent-1: Implementation done (commits 7670dd14, d1f51214, b1a37ad1); code review done (reviews/backup-secrets/review.md: 0 P0/P1, CR-1 already turned into task web-totp-reenroll). WIP test-writer tests committed in 10607886 (BackupSecretsCryptoTests, BackupSecretsZipTests, TotpEnrollmentWebTests, RestoreCli passphrase) for CR-2/CR-3; check.sh passes. Left: rerun test-writer to finish and write reviews/backup-secrets/tests.md with coverage; record CR-2 with tasks.py assume (password-only login when no recovery code is left); decide/fix CR-3 + P3s via fixer if production changes, then re-review; run ./gradlew test --tests 'ch.sonensei.cockpit.backup.*'; verify 1-4; write quality.json; done.
- ASSUMPTION (2026-10-21 03:27+0200 by dell26/agent-1): After a restore without secrets, a re-enrolling owner must give an unused recovery code; only when none is left does the password alone log in, into a restricted session that can only enroll a new TOTP secret (avoids a permanent lockout; SPEC §10.2 only says 're-enrolls TOTP on first login'). Review CR-2.
