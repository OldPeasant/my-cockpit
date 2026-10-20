# Code Review

Date: 2026-10-20

Task: backup-contrib-core - Backup contributors: core entities

Scope: task code commit 0bf2a50b on top of base a8bc9af4 (`git diff a8bc9af4 0bf2a50b`): `server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib/*` (production) and `server/src/test/kotlin/ch/sonensei/cockpit/core/backupcontrib/CoreBackupFixtures.kt`. Test files the test-writer may be adding at the same time are not part of this review.

Requirements:
- tasks/backup-contrib-core.md acceptance criteria and its four recorded assumptions (`python tasks.py assumptions`)
- SPEC.md §10.2 (zip format, secrets, BackupContributor), §4.1 (auth), §12 (round-trip test), §14
- docs/ARCHITECTURE.md, AGENTS.md project notes
- Backup interface: `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupContributor.kt`, `BackupService.kt`; `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupRoundTripTest.kt`, `BackupFixture.kt`
- Related code: core auth (`SetupService`, `LoginService`, `RecoveryCodeService`), `core/crypto/SecretCipher`, `EncryptedSecretConverter`, core Flyway migrations (app_user, recovery_code, user_settings, link, reminder), tasks/backup-secrets.md, tasks/backup-restore.md

Git range:
- Branch: main
- HEAD: 0bf2a50b94558ed5a43967813a6e66e1b3efae3a
- Base/start: a8bc9af4a560dd82724d89669dac06c0d9dd5bdc
- Commits reviewed: 1 (0bf2a50b); no uncommitted changes when the review started

Summary: 0 P0, 0 P1, 2 P2, 0 P3

Checks that found no issue:
- Every column of `app_user`, `recovery_code`, `user_settings`, `link` and `reminder` is exported and re-inserted, except the deliberately omitted `totp_secret`; ids, timestamps and `version` are kept, as the JDBC assumption intends.
- The TOTP secret written on import uses `SecretCipher.encrypt`, the same format `EncryptedSecretConverter` reads, so JPA can load the restored account. No plaintext secret goes into `data/` or `readable/`. Only Argon2id hashes are exported, which SPEC §10.2 allows ("The password hash is always included").
- `queryForStream` streams are closed by `use`. Markdown table cells escape `\`, `|` and line breaks. Nothing is logged.
- Contributor ids are valid, and the dependency (`settings` -> `user`) resolves in `BackupContributorRegistry`.
- The fixtures are plain classes under `ch.sonensei.cockpit`, as `BackupFixture` asks. They seed every exported data name and cover unicode, null and empty labels, a used recovery code, and fired and pending reminders. The fixture data is fictitious.
- `server/build/test-results/test/TEST-ch.sonensei.cockpit.backup.BackupRoundTripTest.xml` (2026-10-20T20:39Z) shows 1 test, 0 failures. The reviewer could not re-run Gradle in this sandbox. No criterion evidence has been recorded yet (`tasks.py verify backup-contrib-core` shows both unticked). This is not a finding because the task is not done yet.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A restored account is active with a TOTP secret nobody knows, and there is no re-enrollment path or tracked hand-off**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib/UserBackupContributor.kt:47-59`
    - Category: Integration
    - Problem: `import` writes a fresh random TOTP secret (line 58) but keeps `totp_confirmed_at` (line 59), so the account is active (`UserAccount.isActive`) and setup is closed. The only way to log in is a recovery code, and each login uses one up (`LoginService.verify`). The new codes cannot be regenerated because `RecoveryCodeService.regenerate` needs a valid TOTP code. No endpoint re-enrolls TOTP. If the backup has no unused recovery codes, the owner is locked out right after the restore. Otherwise the owner is locked out once the remaining codes are used. SPEC §10.2 requires that "the user re-enrolls TOTP on first login". Assumption #1 hands this to `backup-secrets`. But `tasks/backup-secrets.md` has no dependency on `backup-contrib-core`, its `touches` covers only `server/.../backup` (not `core/backupcontrib` or `core/auth`), and its notes do not mention the hand-off. The note exists only in this task's assumption, so the follow-up can easily be missed.
    - Why it matters: Restore is not reachable in production yet (`BackupService.import` has no caller until `backup-restore`). Once it is, a restore without the follow-up can make the instance permanently inaccessible without manual database edits. This is the user-facing outcome SPEC §10.2 tries to avoid.
    - Suggested fix: Make the hand-off explicit and enforceable. Add a note to `backup-secrets`, or a new task, saying that the forced TOTP re-enrollment must change `UserBackupContributor.import` and core auth, with matching `touches` and a dependency on `backup-contrib-core`. Within this task, at least document the interim lockout risk in the KDoc and the assumption (for example, log a WARN without secrets on import when no unused recovery code remains).

- [ ] **CR-2 | Import-only behavior and the readable mirrors have no tests**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/core/backupcontrib/CoreBackupFixtures.kt:1-88` (behavior in `UserBackupContributor.kt:47-72`, `LinksBackupContributor.kt:28-47`, `RemindersBackupContributor.kt:27-45`, `SettingsBackupContributor.kt:32-54`)
    - Category: Tests
    - Problem: At 0bf2a50b the only verification is the shared round trip, which compares `data/` only. It cannot see two things:
        - What import does that export does not show: the TOTP secret is replaced, the stored value decrypts through `SecretCipher`, the account stays active, and an imported unused recovery code can log in.
        - The `readable/` output: `links.md` escaping of `|` and line breaks in labels, `reminders.md` listing only pending reminders in `remind_at` order, `settings.md` showing "All settings are at their defaults." when no row exists, and `export` writing no `settings.json`/`user.json` when the tables are empty.
    - Why it matters: These behaviors are what makes criterion 1 work (TOTP handling, readable sections), and the round trip passes even if they regress. For example, a change that copies an empty or garbage `totp_secret` would only fail at the owner's first login.
    - Suggested fix: Add focused tests in `server/src/test/kotlin/ch/sonensei/cockpit/core/backupcontrib/`. Use Postgres/Testcontainers as the round trip does, or in-memory `BackupDataWriter`/`BackupReadableWriter`/`BackupDataReader` fakes with a real JdbcTemplate. They should assert the imported account's decryptable new secret and recovery-code login, the mirror contents and escaping, and the empty-table export. This may be covered by the test-writer's concurrent work; check it against the final revision.

## P3 Low

None.

## Review conclusion

Material issues found; fix before completing the task. Neither finding blocks correctness of the backed-up data. CR-1 needs at least an explicit, tracked hand-off, and CR-2 needs tests (possibly the test-writer's additions) before `done`.
