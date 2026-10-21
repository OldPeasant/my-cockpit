# Code Review

Date: 2026-10-21

Task: backup-secrets - Backup secrets with passphrase

Scope: re-review of the final code of the task, `git diff 91577831dc85fed80a4efca4844951dd0c6ec0ab..a7a7166a` (whole task diff, production code read in full; tests read only to judge coverage and the status of earlier findings). The fix commit a7a7166a (CR-4..CR-7) was checked separately against `reviews/backup-secrets/fixes.md`. While this review ran, the local `main` was rebased (HEAD moved to other hashes on top of newer main commits), and test files may change in parallel (test-writer). Everything below was read from the commits with `git show a7a7166a:<path>`, not from the working tree.

Requirements:
- tasks/backup-secrets.md (acceptance criteria 1-4, notes, recorded assumption on the password-only login)
- SPEC.md §10.1, §10.2 (secrets, lines 580-583), phase 2 smoke step 5 (line 737)
- docs/ARCHITECTURE.md, AGENTS.md project notes (module boundaries, Flyway layout, logging rules)
- Previous review reviews/backup-secrets/review.md (CR-1..CR-7) and reviews/backup-secrets/fixes.md
- Parent instructions (verify fixes, look for new issues, state the status of earlier findings)

Git range:
- Branch: main
- HEAD: a7a7166a198d7179d61cdff3610986d968b1b6cc (as given by the parent; the local branch has since been rebased)
- Base/start: 91577831dc85fed80a4efca4844951dd0c6ec0ab
- Commits reviewed: 5 task commits (594a04a2, 67f46179, 6b8102bd, 1d311db0, a7a7166a) plus task-board commits in the range (4139fc5c new task web-totp-reenroll, assume, heartbeat)

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## Status of earlier findings

- CR-1 (no web client for the forced re-enrollment): resolved outside this task. `tasks/web-totp-reenroll.md` exists (from 4139fc5c), and `web-passkeys` now depends on it.
- CR-2 (unrecorded, untested password-only login): resolved. The assumption is recorded in `tasks/backup-secrets.md` (the ASSUMPTION note). `TotpEnrollmentWebTests` covers it at a7a7166a: password alone without unused codes gives a restricted session (403 on `/api/v1/settings`); with unused codes it gives 401; a non-re-enrolling account never logs in with the password alone; device tokens get 403 and the recovery code is not used up.
- CR-3 (tamper/bounds/enrollment error paths untested): resolved at a7a7166a. `BackupSecretsCryptoTests` covers tampered ciphertext, AAD fields, salt and nonce, unknown version/cipher/algorithm, out-of-bounds KDF parameters and malformed Base64. `BackupSecretsZipTests` covers a passphrase for a backup without secrets and invalid envelopes. `TotpEnrollmentWebTests` covers 409, 400, 429 and CSRF. `RestoreCliTests` covers a right and a wrong passphrase. The test-writer may still be adding tests; this review did not evaluate them.
- CR-4 (empty backup passphrase rejected): fixed. `StartBackupRequest` (`BackupController.kt:136-151`) turns `""` into `null` before Bean Validation runs, and the dead `takeIf` is gone. The test now covers `{"passphrase":""}` -> 202 without `data/secrets.enc.json`. See CR-2 below for a remaining contract detail.
- CR-5 (zip hashed three times): fixed. `RestoreService.validate` gets `hasSecrets` from the manifest (`RestoreService.kt:135-136`) only after the inspection found no problems, and the inspection checks that zip and manifest match. `BackupService.hasSecrets` is removed, and nothing else used it.
- CR-6 (README lacks the CLI passphrase): fixed. The README restore section names `COCKPIT_RESTORE_PASSPHRASE` (`BackupService.kt:167-168`), and `BackupServiceTests` asserts this against `RestoreCli.PASSPHRASE_ENV`.
- CR-7 (409 confirm counted by the throttle): fixed. `pendingAccount()` now runs before `throttle.tryBeginAttempt()` (`TotpEnrollmentService.kt:41-45`), and a regression test was added.

Re-verified and found correct in the whole diff (no finding):
- Crypto: Argon2id v1.3 (64 MiB, 3 passes, 1 lane), a 16-byte random salt and a 32-byte key; AES-256-GCM with a fresh 96-bit nonce and key for each backup; every header field is in the AAD; KDF bounds are checked before any derivation; a GCM failure becomes `WrongPassphraseException` (a subclass of `InvalidBackupException`, so it gives 422 Problem Details). Passphrase, key and secrets never reach logs, messages or `toString`.
- Wrong passphrase leaves nothing changed: `RestoreService.replace` calls `checkPassphrase` before the pre-restore backup, the stash and the wipe. `confirm` keeps the upload only for `WrongPassphraseException`. The CLI prints the problem and exits with 1.
- Without a passphrase the writer drops secrets and writes no `secrets.enc.json`. OAuth connections import as `RECONNECT_REQUIRED` with a null token and `last_error = restored_without_token`. The owner gets `totp_reenroll_required = true` only when TOTP had been confirmed.
- Authorization: the role comes from the account row on every request (`LoginService.authenticate`, `DeviceTokenService`). `fullAccess` rejects `ROLE_TOTP_ENROLLMENT` on `/api/**`, `/actuator/**`, the docs and every non-GET request. Only `GET me`, logout and `POST /totp-enrollment[/confirm]` stay open. `start`/`confirm` answer 409 unless the flag is set.
- Module boundaries follow the contributor SPI. The migration `core/V202610210100__core_auth_totp_reenroll.sql` follows the naming rule. The restore wipe truncates all tables, `oauth_connection` included. The insert in `OAuthBackupContributor` covers every NOT NULL column of `oauth_connection`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Restricted re-enrollment sessions get full access when any session completes the enrollment**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/TotpEnrollmentService.kt:49` (with `LoginService.kt:122`, where the principal comes from `session.user`)
    - Category: Security
    - Problem: `CockpitPrincipal.totpEnrollmentRequired` is not stored on the session. Each request derives it from the account row (`principalOf(session.user)`). `confirm()` only clears `totp_reenroll_required` and leaves the account's other sessions in place. So every session created during the re-enrollment window becomes a full-access session at the moment one session confirms a code, including persistent 30-day "remember device" sessions. Under the recorded assumption, a session in that window may hold only the password, when no unused recovery code is left. Example: someone who knows only the password logs in during the window. The owner then enrolls TOTP normally. The other session now has full access, although it never proved a second factor, and the owner gets no sign of it. In the same window, any restricted session can also call `start` again and replace the secret another session is confirming (`TotpEnrollmentService.kt:30-33`).
    - Why it matters: The restricted session is the control that keeps the single-factor path contained (the old CR-2 assumption). A session that never passed a second factor ends up fully authorized. The assumption the human reviews only says that the password alone gives "a restricted session that can only enroll a new TOTP secret". It does not cover this upgrade of other sessions. The existing tests check only the confirming session (`after the re-enrollment the new secret logs in everywhere with full access`), so they don't catch it.
    - Suggested fix: In `confirm()`, delete all other sessions of the account (keep only the caller's), or store the restriction on the session row and do not clear it on other sessions. Add a web test: two re-enrollment sessions, one confirms, and the other must get 401 or 403 on `/api/v1/settings` afterwards.

## P3 Low

- [ ] **CR-2 | The OpenAPI schema still requires `minLength: 12` for a backup passphrase that may now be empty**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/backup/api/BackupController.kt:141-145` (generated `api/openapi.yaml` `StartBackupRequest.passphrase`)
    - Category: Integration
    - Problem: After the CR-4 fix the server accepts `{"passphrase":""}` and treats it as none. The machine-readable contract still says `minLength: 12`, and only the description text mentions the exception. `""` is therefore invalid by the published schema but valid on the server.
    - Why it matters: A client or form that validates against the schema (or a contract test generated from it) rejects the empty case the server explicitly supports. The fix aimed to remove exactly that endpoint difference.
    - Suggested fix: Either document the field as `oneOf` an empty string or a 12-256 character string (e.g. `pattern: "^$|^.{12,256}$"` in `@Schema`), or keep the schema strict and let clients omit the field. In either case, make the schema and the server agree.

## Review conclusion

Material issues found; fix before completing the task.

Acceptance criteria 1-3 are implemented in the production code, and the tests at a7a7166a cover them. All seven earlier findings are resolved, fixed or moved to a task. CR-1 (P2) is a new security finding in the forced re-enrollment: sessions created while TOTP is not enrolled are upgraded silently. It should be fixed, or a human should explicitly accept it in addition to the recorded assumption, before `done`. CR-2 (P3) is a small contract cleanup. Criterion 4 (the `./gradlew test --tests 'ch.sonensei.cockpit.backup.*'` run) is for the parent worker to verify and was not run by this review.
