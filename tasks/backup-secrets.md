---
title: Backup secrets with passphrase
status: in_progress
owner: dell26/agent-3
deps: [backup-restore,backup-contrib-core]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/backup,server/src/test/kotlin/ch/sonensei/cockpit/backup,server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib,server/src/test/kotlin/ch/sonensei/cockpit/core/backupcontrib,server/src/main/kotlin/ch/sonensei/cockpit/core/auth,server/src/test/kotlin/ch/sonensei/cockpit/core/auth]
prio: 1
lease_until: 2026-10-21T03:14:40Z
extended: 
releases: 1
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
- release by dell26/agent-1: Implementation of criteria 1-3 done and committed (b1516cf3, 96069437, 158e0aed, fixes CR-4..CR-7 in 2f77c3f8; more tests in d307e49d, 3efc0be2); scripts/check.sh passes at 3efc0be2. reviews/backup-secrets/review.md is the RE-REVIEW with 2 open findings. Left: (1) fixer for CR-1 (P2 security): TotpEnrollmentService.confirm() must delete the account's other sessions (or keep the restriction on the session row) so password-only re-enrollment sessions aren't upgraded to full access, plus a web test with two sessions; also stop a restricted session from replacing a secret another session is confirming; (2) fixer for CR-2 (P3): make @Schema for StartBackupRequest.passphrase allow '' (e.g. pattern ^$|^.{12,256}$), regenerate api/openapi.yaml + web client; (3) rerun test-writer on final code -> reviews/backup-secrets/tests.md with coverage (not written yet); (4) rerun code-reviewer; (5) run ./gradlew test --tests 'ch.sonensei.cockpit.backup.*' (Testcontainers); (6) tasks.py verify 1-4, quality.json, done.
- ASSUMPTION (2026-10-21 04:18+0200 by dell26/agent-3): TOTP re-enrollment: start from any session replaces the pending secret, confirm only accepts the latest one, and a successful confirm ends all other sessions of the account (incl. remember-device sessions), so password-only restricted sessions are never upgraded (review CR-1). Binding the pending secret to one session would need a schema change.
- ASSUMPTION (2026-10-21 04:41+0200 by dell26/agent-3): Re-review CR-1 (login racing the TOTP re-enrollment confirm) is fixed by a PESSIMISTIC_WRITE lock on the single app_user row in login/device-token verify and in re-enrollment start/confirm, not by a per-session restriction column: single-user app, logins serialize for one Argon2 check, simplest change without a migration.
