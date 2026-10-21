# Code Review

Date: 2026-10-21

Task: backup-secrets - Backup secrets with passphrase

Scope: final re-review on HEAD 2b4c5c3f20f433286bf7a80b4294522b43c7284d. The previous review covered the production code at 6fba2352, which was rebased as 56669f1fbc7f4facbf5e29c0d7ac82ff87dc1ea3. This review checks:
- that the task's production code has not changed since 56669f1f (`git diff --stat 56669f1f HEAD -- server/src/main api web/src`);
- the test changes in `git diff 56669f1f..HEAD -- server/src/test` that belong to this task: `TotpEnrollmentWebTests` (4 new tests and a `heldTransaction` helper, from b98b10e9), `BackupSecretHidingTests` and `TotpEnrollmentSecretHidingTests` (new, from 2b4c5c3f).

Merge bdc787f6 brought in code from task backup-contrib-files: `files/backup/FilesBackupContributor.kt`, `FilesBackupContributorTests.kt` and `FilesBackupFixture.kt`. That code is outside this scope and was not reviewed.

Requirements:
- tasks/backup-secrets.md (acceptance criteria 1-4, notes, recorded assumptions)
- SPEC.md §10.1, §10.2
- docs/ARCHITECTURE.md, AGENTS.md project notes (fictitious test data, no secrets in logs)
- the previous review (reviews/backup-secrets/review.md at 56669f1f, P3 CR-1 and CR-2)
- the test report reviews/backup-secrets/tests.md
- parent instructions: confirm the production code is unchanged; review the added tests for correctness, flakiness, fictitious test data and secrets handling; restate the status of earlier findings

Git range:
- Branch: main
- HEAD: 2b4c5c3f20f433286bf7a80b4294522b43c7284d (reviewed revision)
- Base/start: 56669f1fbc7f4facbf5e29c0d7ac82ff87dc1ea3 (the previously reviewed production revision)
- Commits reviewed: 2 task commits with code changes (b98b10e9, 2b4c5c3f). The other commits in the range are main-merge, heartbeat, claim and release commits, or commits from task backup-contrib-files.
- Uncommitted changes: none (the working tree was clean at the start of the review)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Production code unchanged

`git diff --stat 56669f1f HEAD -- server/src/main api web/src` lists one file: `server/src/main/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributor.kt`. It was last changed in 0f136e52 (task backup-contrib-files) and came in through the main merge. The task's production code under `backup/`, `core/auth/`, `core/backupcontrib/`, the migrations, `api/openapi.yaml` and the generated web client have no changes. The production analysis of the previous review therefore still applies unchanged.

## Review of the added tests

`TotpEnrollmentWebTests`, the concurrency tests and the `heldTransaction` helper:
- **Correctness.** `heldTransaction` runs its block in a `TransactionTemplate` on a worker thread. It returns only after the block has run, so the account row lock (`findFirstForUpdate`, taken by `pendingAccount` and `LoginService.verify`) is already held when the competing operation is submitted. Each test's final assertions match the production code:
    - A late `start` reads the committed confirm and gets `ConflictException` (`TotpEnrollmentService.kt:76-78`). The flag and the confirmed secret stay as they are.
    - A late `confirm` reads the secret written by the concurrent `start` and returns `InvalidCode` (`:63-65`). After `throttle.recordSuccess()`, a code of the new secret confirms.
    - A late device `issue` with a recovery code sees the cleared flag and is issued (`LoginService.kt:94-103`). `totpEnrollmentRequired` is false, and exactly one device exists. The confirm ran first, so its `deleteOtherSessions` cannot remove that device.
    - The non-re-enrolling test checks that the row lock does not break the normal device and web logins, and that a used recovery code is rejected the second time.
- **Throttle.** `tryBeginAttempt` counts an attempt before the lock is taken. At most two attempts are open at the same time, below `FREE_ATTEMPTS = 5`, and `@BeforeEach` resets the throttle. No test can become throttled because of this.
- **TOTP reuse.** The tests move the clock forward one step before they use the same secret again. Production has no replay protection (no last-used-step state was found), so the step advance is only defensive.
- **Flakiness.** The "waits" checks expect a `TimeoutException` after 500 ms. They cannot fail on a slow machine: a slow competing thread still times out. At worst they pass without proving the wait, but the final assertions (409, `InvalidCode`, a device with full access) prove the serialization on their own. All other waits are bounded at 10 s. The `finally` blocks release the latch before `shutdownNow()`, so a failing test cannot leave a transaction holding the lock. The next test's `delete from app_user` would wait for such a lock anyway, not corrupt data.
- **Earlier P3 CR-2 closed.** The tests "a start that runs while a confirm is not committed yet ..." and "a confirm that runs while another session's start is not committed yet ..." hold one operation's transaction open and race the other against it in both directions, which is what CR-2 asked for.

`BackupSecretHidingTests` and `TotpEnrollmentSecretHidingTests`:
- They check that `StartBackupRequest`, `ConfirmRestoreRequest`, `UserSecrets` (`UserBackupContributor.kt:160-165`), `TotpEnrollment` (`TotpEnrollmentService.kt:82-87`), `TotpEnrollmentResponse` and `ConfirmTotpEnrollmentRequest` hide their secret in `toString`. They follow the pattern of the existing `*SecretHidingTests` and assert behavior, not implementation details.

Test data and secrets:
- All values are fictitious: the user `jane.doe`, the password `fictitious-password-1234`, a passphrase with "fictitious" in it, the widely published Base32 example secret, and a made-up 6-digit code. No real credentials appear.
- The tests do not log secrets.

Evidence: `reviews/backup-secrets/tests.md` reports `./gradlew test --tests 'ch.sonensei.cockpit.backup.*' --tests 'ch.sonensei.cockpit.core.auth.*' --tests 'ch.sonensei.cockpit.core.backupcontrib.*' jacocoTestReport --rerun` with Testcontainers: 398 tests, 0 failures. This review did not run the tests again.

## Status of earlier findings

- First review CR-1 (no web client for the forced re-enrollment): still moved to task `web-totp-reenroll`.
- First review CR-2 (password-only login): still an accepted assumption, and tests cover it.
- First review CR-3..CR-7, first re-review CR-1 and CR-2, second re-review CR-1 (P2, concurrent login vs confirm): still fixed. The production code has not changed.
- Previous review CR-1 (P3, `verify` does not enforce its caller's transaction): **open**, carried over below as CR-1.
- Previous review CR-2 (P3, no test for the start/confirm serialization): **resolved** by the two new concurrency tests in `TotpEnrollmentWebTests` (see above).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | `verify` relies on its caller's transaction for the lock, but does not enforce one** (carried over from the previous review, still open)
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/LoginService.kt:69-83`
    - Category: Quality
    - Problem: The row lock must last until the caller has written the session or device. `verify` is `@Transactional` with the default propagation (REQUIRED). A future caller without its own transaction would release the lock before it writes its grant, and the login/confirm race would come back. Both current callers (`login`, `DeviceTokenService.issue`) are correct.
    - Why it matters: Nothing enforces this invariant, so it can break without anyone noticing.
    - Suggested fix: Use `@Transactional(propagation = Propagation.MANDATORY)` on `verify`, or state the requirement in its KDoc.

## Review conclusion

No material issues found.

The task's production code is the same as the reviewed revision 56669f1f. The tests added since then are correct, use fictitious data and do not leak secrets. Their concurrency assertions cannot fail just because a run is slow. They close the earlier P3 CR-2. The only open finding is the optional P3 CR-1.
