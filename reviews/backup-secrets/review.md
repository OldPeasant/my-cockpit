# Code Review

Date: 2026-10-21

Task: backup-secrets - Backup secrets with passphrase

Scope: production code of the three backup-secrets commits as given by the parent (`git diff b1d6bd9e..c69c4e83`). The branch was later rebased onto main (now `6abc3481..c6dfb6fb`). `git patch-id --stable` of both ranges is identical (`d2cd67279f879598b31538764a021f1f9dc5bb61`), so this review applies unchanged to the rebased commits. Tests were read only to judge coverage. Uncommitted changes: none at review time.

Requirements:
- tasks/backup-secrets.md (acceptance criteria 1-4, notes from backup-contrib-core)
- SPEC.md §4.1, §4.2, §10.2 (lines 580-583), phase 2 smoke step 5 (line 737)
- docs/ARCHITECTURE.md (Flyway layout, out-of-order), AGENTS.md project notes
- Parent instructions (crypto, restore atomicity, re-enrollment security, module boundaries, migration naming, Problem Details, openapi)

Git range:
- Branch: main
- HEAD: c6dfb6fb63d374c9e1efee7b273ebeda7182cac8 (rebased; reviewed as c69c4e83 with the same patch-id)
- Base/start: b1d6bd9e0665d76026c8b94e6712d9698e847335 (after the rebase: 6abc348143659b55181b1e2585f95241697285b7)
- Commits reviewed: 3 (02b3cf4a/435ba2b7, 219fac9b/e0754c75, c69c4e83/c6dfb6fb)

Summary: 0 P0, 0 P1, 3 P2, 4 P3

Verified and found correct (no finding):
- Crypto (`BackupSecrets.kt`): Argon2id v1.3, 64 MiB / 3 passes / 1 lane, 16-byte random salt, 32-byte key. AES-256-GCM with a 128-bit tag and a fresh random 96-bit nonce under a fresh salt-derived key for every backup, so nonces are never reused. All header fields (version, cipher, algorithm, salt, KDF parameters, nonce) go into the AAD. A failed tag check (wrong passphrase or tampering) becomes `WrongPassphraseException`. The KDF parameters are checked against bounds before derivation. `secrets.enc.json` is listed in the manifest with its sha256. Passphrase, key and secrets never reach logs, exception messages or `toString` (request DTOs are masked too).
- Wrong passphrase: `RestoreService.replace` calls `checkPassphrase` before the pre-restore backup, the stash and the wipe. `confirm` keeps the upload. The REST answer is a 422 Problem Details with `problems: ["The passphrase is wrong"]`. The CLI prints the problem and "nothing was changed".
- Without a passphrase no secret is written (the writer drops `secret()` calls). The OAuth connections import as `RECONNECT_REQUIRED` with a null token. The owner gets `totp_reenroll_required`, so TOTP does not log in, and the device-token login is refused with 403 only after valid credentials (the recovery code is not used up).
- Forced re-enrollment authorization: a re-enrolling account gets only `ROLE_TOTP_ENROLLMENT`, and that is derived from the account row on every request (sessions and bearer tokens). `fullAccess` rejects that role on `/api/**`, `/actuator/**`, the docs and every non-GET request. Only `GET /auth/me`, logout and `POST /auth/totp-enrollment[/confirm]` are reachable, and they need CSRF. Enrollment works only while the flag is set (409 otherwise), so a normal session cannot replace a working TOTP secret.
- Module boundaries follow the existing contributor pattern (core/backupcontrib → backup.domain SPI). The migration `core/V202610210100__core_auth_totp_reenroll.sql` follows the naming rule, and `spring.flyway.out-of-order=true` covers its timestamp. Errors are Problem Details. `OpenApiContractTest` guards `api/openapi.yaml`, and the generated web client was regenerated in the same commits.
- IMAP passwords: there is no mail backup contributor yet. `backup-contrib-mail` (depends on this task) adds them through the `BackupDataWriter.secret` mechanism. A probe contributor in `BackupSecretsTest` exercises that mechanism, so this is not a gap of this task.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Forced TOTP re-enrollment has no client and no follow-up task: after a restore without secrets the web app cannot be used**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/infra/SecurityConfiguration.kt:68`
    - Category: Integration
    - Problem: The server now gives a re-enrolling account a session that gets 403 on every API call except `me`, logout and `/api/v1/auth/totp-enrollment`. The web login (`web/src/app/features/auth/login/login.ts:48-49,98`) knows nothing about `totpEnrollmentRequired`. It requires a TOTP or recovery code (so the password-only path the server offers is unreachable) and navigates to the app after login, where every request answers 403. No task in `tasks/` covers the enrollment UI: only backup-secrets and backup-contrib-core mention re-enrollment, and `web-settings-backup` covers only backup/restore.
    - Why it matters: SPEC.md phase 2 smoke step 5 ("Without a backup passphrase, TOTP re-enrollment is forced") cannot pass through the product UI. An owner who restores without a passphrase is stuck in a broken web app.
    - Suggested fix: Create a missing-task entry (`tasks.py new`, deps `backup-secrets`, touches `web/src/app/features/auth`). In it: show enrollment (QR from `otpauthUri`, confirm code) when login or `me` answers `totpEnrollmentRequired: true`, and allow an empty second factor in that state. The parent decides; no server change is needed here.

- [ ] **CR-2 | The password-only login after a restore is a security-relevant decision that is neither recorded nor tested**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/LoginService.kt:143-145`
    - Category: Security
    - Problem: When the account must re-enroll and has no unused recovery code, `checkSecondFactor` returns `SecondFactor.None`, so the password alone logs in. That session can then bind any new TOTP secret (`TotpEnrollmentService.start/confirm`) and gets full access. SPEC.md §10.2 says only "the user re-enrolls TOTP on first login". Requiring a recovery code first, and dropping to password only when none is left, is this task's own choice. No `tasks.py assume` entry or ADR records it: the task file has no assumptions, and `docs/adr` has no matching ADR. No test covers this branch: the only references to it in `server/src/test` are the restore-warning strings.
    - Why it matters: For the time between the restore and the owner's first login, the account is protected by one factor only. Whoever knows the password first can take over the TOTP enrollment. That may be acceptable (it avoids a lockout), but a human should confirm it, and a regression could silently widen it (for example to accounts that still have recovery codes).
    - Suggested fix: Record the decision with `tasks.py assume backup-secrets "..."`. Add tests: (a) a re-enrolling account with no unused code logs in with neither factor and gets a restricted session (`totpEnrollmentRequired: true`, 403 on `/api/v1/auth/devices`); (b) a re-enrolling account with unused codes and neither factor gets 401; (c) the device-token login with neither factor gets 403 or 401 and creates no device.

- [ ] **CR-3 | Tampering, bounds and enrollment error paths are untested**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupSecrets.kt:93-113`, `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/TotpEnrollmentService.kt:29-57`
    - Category: Tests
    - Problem: `BackupSecretsTest` covers the main flows (criteria 1-3), but not the security branches the code adds. Untested are: an envelope with a changed AAD field (e.g. `iterations` or `salt`), nonce or ciphertext, which must fail as wrong passphrase or invalid; KDF parameters out of bounds or an unknown `formatVersion`/`cipher`, which must be "not valid" without running Argon2; a passphrase given for a backup without `secrets.enc.json`; enrollment `start`/`confirm` while not required (409), an invalid code (400) and throttling (429); and `RestoreCli` with `COCKPIT_RESTORE_PASSPHRASE` (right and wrong).
    - Why it matters: These are the checks that make the encryption and the restricted session safe. A regression (e.g. dropping the AAD or the bounds check) would pass the current suite.
    - Suggested fix: Add focused unit tests for `BackupSecrets.encrypt/decrypt` (round trip, tamper each header field, bounds) and web tests for the enrollment error statuses. A CLI test with an explicit `passphrase` argument is enough.

## P3 Low

- [ ] **CR-4 | An empty backup passphrase is rejected, although the code treats it as none**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/backup/api/BackupController.kt:63,138`
    - Category: Quality
    - Problem: `@Size(min = 12)` rejects `""` with 400 before `request?.passphrase?.takeIf { it.isNotEmpty() }` runs, so that `takeIf` is dead code. The restore endpoint accepts `""` as "no passphrase" (`RestoreController.kt:100`), so the two endpoints differ.
    - Why it matters: A client that sends an empty field from an untouched form input gets a validation error instead of a backup without secrets.
    - Suggested fix: Either treat blank as absent before validation (e.g. a `@Pattern`/custom constraint that allows empty), or drop the `takeIf` and document that the field must be omitted.

- [ ] **CR-5 | Restore validation now reads and hashes the whole zip three times**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/RestoreService.kt:126-137`
    - Category: Quality
    - Problem: `validate` calls `ZipBackupReader.inspect` (hashes every entry). Then `backups.hasSecrets(path)` and `backups.restoreWarnings(path)` each call `ZipBackupReader.open`, which runs `validate()` and hashes every entry again. `hasSecrets` adds a third full pass only to check whether one entry exists.
    - Why it matters: Backups hold all files (e.g. 60 MB uploads), so the upload response time grows by a full extra read.
    - Suggested fix: Derive `hasSecrets` from the inspection's manifest (`manifest.files.any { it.path == BackupSecrets.PATH }`), or compute it inside the `restoreWarnings` reader.

- [ ] **CR-6 | Restore instructions don't say how to give the passphrase on the CLI**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupService.kt:174`
    - Category: Quality
    - Problem: The README in each backup and the docs describe `java -jar cockpit-server.jar --restore=...` but not `COCKPIT_RESTORE_PASSPHRASE` (`RestoreCli.kt:24`). `grep` finds the variable nowhere outside `RestoreCli`. The CLI prints a hint only after it has started.
    - Why it matters: In a disaster-recovery restore from a passphrase backup, the owner may restore without secrets, and then must re-enroll TOTP and reconnect accounts.
    - Suggested fix: Mention the variable in the README restore section (e.g. "set `COCKPIT_RESTORE_PASSPHRASE` to restore the secrets") and in the ops or ARCHITECTURE docs.

- [ ] **CR-7 | Enrollment `confirm` counts as a failed login attempt even when enrollment is not required**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/TotpEnrollmentService.kt:42-43`
    - Category: Correctness
    - Problem: `throttle.tryBeginAttempt()` (in-memory, counts a failure up front) runs before `pendingAccount()`. A fully authenticated session is allowed to reach `/confirm` (`authenticated()` rule) and gets 409, but the attempt stays counted. A few such calls trigger the login backoff.
    - Why it matters: It is minor (it needs an authenticated owner session and CSRF), but stray client retries can lock the owner out of logging in for a while.
    - Suggested fix: Call `pendingAccount()` before `tryBeginAttempt()`, or call `recordSuccess()` or undo the count on the 409 path.

## Review conclusion

Material issues found; fix before completing the task.

None of the issues blocks the acceptance criteria as written: criteria 1-3 are implemented and covered by `BackupSecretsTest`. CR-1 (missing client/task) and CR-2 (unrecorded, untested single-factor path) need a decision and action by the parent worker. CR-3 is a test gap the test-writer can close.
