# Code Review

Date: 2026-10-21

Task: backup-secrets - Backup secrets with passphrase

Scope: third re-review of the final code of the task, `git diff b18d8200c9154fdb777423c4f469fe5b02c6f20a..6fba2352708fa28e10040cebd46050dbc72dd6b1` (whole task diff). The focus is the fix commit 6fba2352 for CR-1 of the previous re-review (a login running concurrently with the TOTP re-enrollment confirm; a start/confirm lost update). Production code was read from the commit (`git show 6fba2352:<path>`), not from the working tree, because a test-writer may be changing test files in parallel. `git range-diff bff56756..611c5048 b18d8200..c1b8e0a7 -- server/src/main api web/src` shows that the rebased task commits up to c1b8e0a7 are identical to those of the previous review (611c5048). The only production change since then is 6fba2352.

Requirements:
- tasks/backup-secrets.md (acceptance criteria 1-4, notes, recorded assumptions: password-only login, re-enrollment session handling, row lock instead of a per-session restriction column)
- SPEC.md §10.1, §10.2
- docs/ARCHITECTURE.md, AGENTS.md project notes (module boundaries, Flyway layout, logging rules, API contract rules)
- Previous re-review (reviews/backup-secrets/review.md at 3cdfcdf0, CR-1)
- Parent instructions (verify the lock fix, including deadlock risk, the device-token path and transaction boundaries; state the status of earlier findings; look for new issues across the whole diff)

Git range:
- Branch: main
- HEAD: 6fba2352708fa28e10040cebd46050dbc72dd6b1 (reviewed revision)
- Base/start: b18d8200c9154fdb777423c4f469fe5b02c6f20a
- Commits reviewed: 11 (ee896e35, 159bbcc6, 4ef3beb3, 237284b6, e5667519, d55c5f85, f0726c31, c1b8e0a7, 3cdfcdf0, 741b7735, 6fba2352)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Status of earlier findings

- First review CR-1 (no web client for the forced re-enrollment): moved to task `web-totp-reenroll` (unchanged).
- First review CR-2 (password-only login): the assumption is recorded in the task, and tests cover it (unchanged).
- First review CR-3..CR-7: still resolved. The production code for them did not change.
- First re-review CR-1 (other sessions upgraded on confirm) and CR-2 (OpenAPI `minLength` vs empty passphrase): still fixed. The code is identical to 611c5048 (range-diff above).
- **Previous re-review CR-1 (P2, concurrent login vs confirm, start/confirm lost update): fixed.** Details:
    - Lock: `UserAccountRepository.findFirstForUpdate()` (`UserAccountRepository.kt:18-21`) is a `@Lock(PESSIMISTIC_WRITE)` JPQL query on the single `app_user` row. The unique index `app_user_single_row` keeps the un-limited `@Query` single-valued. `LoginService.verify` (`LoginService.kt:83`) and `TotpEnrollmentService.pendingAccount` (`TotpEnrollmentService.kt:76-78`, used by `start` and `confirm`) both take it. Both use the same lock mode, so they serialize on the row whatever SQL Hibernate generates for it on PostgreSQL.
    - Transaction boundaries: `verify` is `@Transactional` (REQUIRED). Its two callers, `LoginService.login` (`:47`) and `DeviceTokenService.issue` (`DeviceTokenService.kt:33`), are `@Transactional` themselves, so the lock is held until the session or device row is written and committed. `login` calls `verify` on `this`, so the call bypasses the proxy, but it already runs inside `login`'s transaction. `spring.jpa.open-in-view=false` (`application.properties:10`), so each transaction starts with a fresh persistence context, and the locked query returns the current row, not an entity cached earlier in the request.
    - Ordering: a login that locks first writes its session under the lock. The confirm then waits, sees the session after the commit, and `deleteOtherSessions` removes it. A confirm that locks first commits `totp_reenroll_required = false`. A password-only login after that gets no second factor (`checkSecondFactor` returns null) and is rejected; a recovery-code login is a real second factor. A `start` after a committed confirm sees the cleared flag and gets 409, so the lost update is gone.
    - Device-token path: `issue` holds the same lock through `verify`. While the flag is set it is rejected before a recovery code is used. After a concurrent confirm it needs the new TOTP or a recovery code. Existing device tokens (`authenticate`, `refresh`) do not read the flag and were already out of scope of the restriction.
    - Deadlock risk: none found. Every lock-taking path takes the `app_user` row lock first and only then touches `auth_session` / `auth_device` (`deleteExpired`, insert, `deleteOtherSessions`). The paths that lock `auth_session` rows without the account lock (`authenticate`: `touch`, `deleteByTokenHash`; `logout`) never wait for `app_user`. They read it only through a plain join fetch. A restore (`TRUNCATE ... CASCADE`) racing a login could already conflict on table locks before this change, because the login read `app_user` and then wrote `auth_session`. PostgreSQL detects such a deadlock and aborts one side. The fix adds no new lock-order inversion.
    - Load: the lock is held for the Argon2 password check plus the recovery-code checks. This serializes logins of the single owner. The concurrency is bounded by `LoginThrottle.tryBeginAttempt`, which runs before the lock is taken, so waiting requests cannot use up the connection pool. This is acceptable under the recorded assumption.
    - Test: `TotpEnrollmentWebTests` "a login whose transaction runs while the confirm runs is ended by the confirm and never gets full access" keeps a login's transaction open after its session is written. It then checks that the confirm blocks (a 500 ms `TimeoutException`), that the login's grant is restricted, that its cookie gets 401 after the confirm, and that one session remains. The `finally` releases the latch before `shutdownNow`, so a failing run does not leave a blocked transaction behind.

Re-checked across the whole diff, no new finding: the crypto envelope (Argon2id with bounded parameters, AES-256-GCM with a fresh nonce and the header as AAD, `WrongPassphraseException` as 422 before the pre-restore backup and the wipe); no `secrets.enc.json` without a passphrase; OAuth `RECONNECT_REQUIRED`; the re-enrollment flag on the restored owner; `fullAccess` authorization; the passphrase `@Pattern` and the OpenAPI schema; the Flyway migration name and location; no secrets in logs or `toString`. The test-writer tests in 741b7735 assert behavior and do not weaken existing tests. The 6fba2352 changes in `LoginServiceThrottleTests` only rename the mocked repository method.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | `verify` relies on its caller's transaction for the lock, but does not enforce one**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/LoginService.kt:69-83`
    - Category: Quality
    - Problem: The KDoc says `verify` "joins the caller's transaction", and the fix depends on it: the row lock must last until the caller has written the session or device. But `verify` is annotated `@Transactional` (REQUIRED). A future caller without its own transaction would run `verify` in a transaction of its own and release the lock before the grant is written. That would silently reopen the race that CR-1 of the previous re-review described. Both current callers are correct.
    - Why it matters: Nothing enforces the invariant, so it is easy to break without noticing. No test would catch it, because the concurrency test goes through `login`.
    - Suggested fix: Annotate `verify` with `@Transactional(propagation = Propagation.MANDATORY)`, so a caller without a transaction fails immediately. Alternatively, state in the KDoc that the caller must hold a transaction until its grant is written.

- [ ] **CR-2 | The start/confirm serialization has no test**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/TotpEnrollmentWebTests.kt:509-545` (production: `TotpEnrollmentService.kt:41-48`, `:76-78`)
    - Category: Tests
    - Problem: The new concurrency test covers only a login racing the confirm. The second half of the previous CR-1 was a `start` that read the account before a concurrent `confirm` committed and then wrote `totp_reenroll_required = true` back. The fix covers it through the same lock, and the class KDoc promises it, but no test holds a `confirm` transaction open while a `start` runs.
    - Why it matters: If someone drops the lock from `pendingAccount` (for example, back to `findFirstBy`), the login test still passes as long as `verify` keeps its lock. The start/confirm lost update would then come back unnoticed.
    - Suggested fix: Add a test in the style of the existing one. Hold a `confirm` (or `start`) inside an open `TransactionTemplate`, run the other operation in a second thread, and assert that it waits. After the release, the late `start` should get a `ConflictException`, the flag should stay `false`, and the confirmed secret should stay unchanged.

## Review conclusion

No material issues found.

Acceptance criteria 1-3 are implemented in the production code at 6fba2352, and the tests cover them. The previous CR-1 is fixed by the row lock, with correct transaction boundaries on the web-login and device-token paths, and this review found no deadlock risk from the change. The two P3 findings are optional hardening. Criterion 4 (the `./gradlew test --tests 'ch.sonensei.cockpit.backup.*'` run with Testcontainers) and the new concurrency test have to be run by the parent worker. This review did not run them.
