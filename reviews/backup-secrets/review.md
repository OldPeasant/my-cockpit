# Code Review

Date: 2026-10-21

Task: backup-secrets - Backup secrets with passphrase

Scope: second re-review of the final code of the task, `git diff bff56756ab7c7f00a421f70daf0de7f85dfcb155..611c5048f68fe2e1cd5e45bc80e498633b08127c` (whole task diff). The fix commit 611c5048 (CR-1, CR-2 of the previous re-review) was checked separately against `reviews/backup-secrets/fixes.md`. Production code was read from the commit (`git show 611c5048:<path>`), not from the working tree, because a test-writer may be changing test files in parallel. While this review ran, local `main` was rebased again (HEAD 695ccb86 holds the same task commits on top of newer main). This review did not compare the rebased commits with the reviewed ones.

Requirements:
- tasks/backup-secrets.md (acceptance criteria 1-4, notes, recorded assumption on the password-only login)
- SPEC.md §10.1, §10.2
- docs/ARCHITECTURE.md, AGENTS.md project notes (module boundaries, Flyway layout, logging rules, API contract rules)
- Previous re-review (CR-1, CR-2) and reviews/backup-secrets/fixes.md
- Parent instructions (verify the fixes, state the status of earlier findings, look for new issues across the whole diff)

Git range:
- Branch: main
- HEAD: 611c5048f68fe2e1cd5e45bc80e498633b08127c (reviewed revision as given by the parent; local HEAD is now the rebased 695ccb86)
- Base/start: bff56756ab7c7f00a421f70daf0de7f85dfcb155
- Commits reviewed: 8 (4012b3db, 27d0c87e, 8b5e6b85, 115cf061, 1adaf2a9, ce187fb1, 4056b3ab, 611c5048)

Summary: 0 P0, 0 P1, 1 P2, 0 P3

## Status of earlier findings

First review (CR-1..CR-7) and first re-review:
- First review CR-1 (no web client for the forced re-enrollment): moved to task `web-totp-reenroll` (unchanged).
- First review CR-2 (password-only login): the assumption is recorded in the task, and tests cover it (unchanged).
- First review CR-3..CR-7: they stay resolved. The production code for these parts did not change in 611c5048, apart from the `StartBackupRequest` annotations (see CR-2 below).

Previous re-review:
- **CR-1 (P2, other re-enrollment sessions upgraded on confirm): fixed for the sequential case, see the new CR-1 for the concurrent case.** `TotpEnrollmentService.confirm(code, sessionToken)` (`TotpEnrollmentService.kt:54-71`) now calls the new `AuthSessionRepository.deleteOtherSessions(userId, keepTokenHash)` (`AuthSessionRepository.kt:35-41`, a JPQL bulk delete with `<>` on the token hash). This runs in the same transaction, after the account is saved. The controller passes the caller's cookie token (`SessionCookies.read`). When no token is given, the empty hash makes every session of the account end. This is safe, because device tokens are separate (`auth_device`) and cannot reach the endpoint. The log line gives only a count. The new web test `a confirm ends the other sessions of the account, also remembered and password-only ones, and keeps its own` checks the reported scenario: the other sessions get 401 after the confirm, the confirming session gets 200, and one row remains. The "secret replaced by another session's start" part is handled by documentation plus the session cleanup. The test `a start from another session replaces the secret, ...` covers it, and this is acceptable under the recorded assumption.
- **CR-2 (P3, OpenAPI `minLength: 12` vs empty passphrase): fixed.** `@Size` became `@Pattern(regexp = BACKUP_PASSPHRASE_PATTERN)` with `^$|^[\s\S]{12,256}$` (`BackupController.kt:140-162`). This keeps the server behavior: `""` is normalized to null before validation, 11 or 257 characters still give 400, and line breaks are allowed as before. `api/openapi.yaml` now publishes `pattern` plus `maxLength: 256` and no `minLength`, so the schema and the server agree. The regex is valid in both Java and ECMAScript.

Re-checked across the whole diff, no new finding: the crypto envelope (Argon2id v1.3 with bounded parameters, AES-256-GCM with a fresh nonce and the header as AAD, `WrongPassphraseException` as 422); the wrong passphrase is checked before the pre-restore backup and the wipe, and the upload is kept for a retry; no `secrets.enc.json` without a passphrase; OAuth `RECONNECT_REQUIRED`; TOTP re-enrollment flag on the restored owner; `fullAccess` authorization rules; the Flyway migration name and location; no secrets in logs or `toString`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A password-only login that runs concurrently with the re-enrollment confirm still ends up as a full-access session**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/TotpEnrollmentService.kt:68` (with `LoginService.kt:81-84`, `:56`, `:122`, `:144`)
    - Category: Security
    - Problem: The CR-1 fix deletes the account's other sessions that exist when `confirm` runs its delete. The restriction is still not stored on the session; it comes from the account row on every request (`principalOf(session.user)`, `LoginService.kt:122`). `LoginService.login` reads the account (`:81`), with `totpReenrollRequired = true`. It then spends one Argon2 verification (`:84`) and grants the password-only second factor (`:144`). Only after that does it insert the session (`:56`). Neither transaction locks the `app_user` row, and `UserAccount` has no `@Version`. A login that read the flag before the confirm committed, and inserts its session after the confirm's `deleteOtherSessions` ran, is not deleted: under READ COMMITTED the bulk delete does not see the uncommitted insert. Once the confirm commits, that session's principal has `totpEnrollmentRequired = false`, so it gets `ROLE_OWNER`. The window is the length of an Argon2 verification. Under the recorded assumption, an attacker who has only the password (no unused recovery code left) can keep logging in during the re-enrollment window. Each successful login resets the throttle. Sooner or later one login overlaps with the owner's confirm and becomes a full-access session without a second factor: the scenario CR-1 was meant to close. A related lost update has the same cause: a `start` that read the account before a concurrent `confirm` committed writes the whole row (Hibernate updates all columns) after the confirm. This sets `totp_reenroll_required` back to `true` with the `start` caller's secret, and the owner's just-confirmed session becomes restricted again.
    - Why it matters: The restricted session is the only control that contains the single-factor path. The fix closes it for sessions that already exist, but not for sessions created in parallel, and an attacker can repeat the login cheaply. The tests run sequentially, so they cannot catch this.
    - Suggested fix: Store the restriction on the session row. For example, add an `auth_session.totp_enrollment_only` column, set at login from `account.totpReenrollRequired`. Compute the principal as `account.totpReenrollRequired || session.totpEnrollmentOnly`, and never clear the column, except on the confirming session in `confirm`. Then a session that never proved a second factor can never be upgraded, whatever the timing. Alternatively, serialize on the account row: a `@Lock(PESSIMISTIC_WRITE)` lookup in `LoginService.verify`/`login` and in `TotpEnrollmentService.start`/`confirm`, or `@Version` on `UserAccount` for the start/confirm lost update. Add a test that interleaves the steps deterministically, e.g. a session inserted with the flag still set after the confirm's delete. At minimum, a domain-level test should check that such a session does not get full access.

## P3 Low

None.

## Review conclusion

Material issues found; fix before completing the task.

Acceptance criteria 1-3 are implemented in the production code at 611c5048, and the tests cover them. The previous CR-2 is fixed. The previous CR-1 is fixed for the sequential case, which its regression test covers. The new CR-1 (P2) is the remaining concurrency gap in that fix. It is reachable only under the recorded password-only assumption, when no unused recovery code is left. It should be fixed, or a human should explicitly accept it next to that assumption, before `done`. Criterion 4 (the `./gradlew test --tests 'ch.sonensei.cockpit.backup.*'` run with Testcontainers) is for the parent worker to verify; this review did not run it.
