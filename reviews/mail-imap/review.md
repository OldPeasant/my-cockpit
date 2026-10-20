# Code Review

Date: 2026-10-20

Task: mail-imap - IMAP/SMTP connectivity and folder sync

Scope: second review round. All `mail-imap:` commits from the base 5b0dcae7 to HEAD 2f82ff2f. That covers the production code in `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap`, its tests in `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap`, `api/openapi.yaml` and the regenerated `web/src/app/api/generated/`. I focused on the fixes for the first round's findings (CR-1..CR-5) and on the final code. Only committed code was reviewed. Reviewed revision: 2f82ff2fcf35ae0065a278a61082ce9b68df5d83.

Requirements:
- `tasks/mail-imap.md` acceptance criteria and the assumptions recorded there
- SPEC.md §7.1 (accounts), §7.2 (connection pool, folders), §4.2 (Problem Details, ETag/If-Match, UUIDv7)
- docs/ARCHITECTURE.md and the AGENTS.md project notes (secrets are never logged, conventions)
- `reviews/mail-imap/review.md` (round 1) and `reviews/mail-imap/fixes.md`
- Parent instructions: confirm that each earlier finding is resolved

Git range:
- Branch: main
- HEAD: 2f82ff2fcf35ae0065a278a61082ce9b68df5d83
- Base/start: 5b0dcae7b4f9e3235f258c58249633cae05a72c6
- Commits reviewed: 10 (6245400f, 3b87215e, 04aab757, a6be9f1f, d036e34c, 09d31868, e2419dea, 7128f112, 6989ea3f, 2f82ff2f)

Summary: 0 P0, 0 P1, 0 P2, 3 P3

## Status of round-1 findings

- CR-1 (parallel syncs give a 500): resolved. `MailFolderService.sync` (`MailFolderService.kt:47-58`) now locks the `mail_account` row with `PESSIMISTIC_WRITE` in the store transaction before it reads the cached folders. Stores for the same account therefore run one after the other, and a deleted account still gives 404. `MailFolderSyncTests` "two parallel first syncs of one account both succeed and store every folder once" uses a `CyclicBarrier` to make both syncs list before either one stores, against real Postgres.
- CR-2 (no host-name mismatch test): resolved. `ImapGreenMailTests.kt:176-192` connects to `127.0.0.1`. That host is in `ssl-trust`, but the certificate does not cover it (`san=dns:localhost`). The test asserts `TLS_FAILED` for both IMAP and SMTP, a `CertificateException` root cause about subject alternative names, and that no connection stays open. `classify()` already maps a `CertificateException` in the cause chain to `TLS_FAILED`.
- CR-3 (wrong field names): resolved. `MailAccountService.kt:118-119` now reports `imap` and `smtp`. Neither `api/openapi.yaml` nor the generated client mentions `imapHost` or `smtpHost`.
- CR-4 (lazy idle eviction): resolved. `ImapConnectionPool.evictExpired` (`ImapConnectionPool.kt:102-106`) is `@Scheduled` every 60 s. Scheduling is switched on for the whole application by the `@EnableScheduling` on `TrashPurgeJob.kt:48-49`, which has no condition. Unit tests cover both cases: expired connections closed without a borrow, and connections in use left alone.
- CR-5 (stale snapshot kept in a new pool): resolved. Each idle connection keeps the `ImapConnectionSettings` it was opened with. `reusable()` (`ImapConnectionPool.kt:167-172`) closes idle connections whose settings differ from the borrower's. `MailAccountService` uses the same class to decide when to publish `MailAccountConnectionChanged`. `EncryptedSecret` compares by value (`EncryptedSecret.kt:20-22`), so reloaded accounts with unchanged settings still reuse connections, and `toString` stays redacted.

Also checked in the final code, with no findings:
- CRUD under `/api/v1/mail/accounts`. The password is write-only: `writeOnly: true` in `openapi.yaml`, and responses carry only `hasPassword`.
- If-Match (412/428) on PATCH/DELETE of accounts and on PATCH of folders.
- TLS, required STARTTLS and `checkserveridentity` for every mode except plain. XOAUTH2 limits `auth.mechanisms`.
- Pool limits and the POOL_EXHAUSTED answer (503).
- Problem Details with fixed messages. The connection-failure handler logs only the error code at INFO.
- Deleting a vanished folder keeps its messages (`on delete set null` in `V202610201402__mail_model.sql:92`).
- `openapi.yaml` at HEAD contains every mail-account path, after it was regenerated following the merge of main.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | An older folder listing can be stored after a newer one**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailFolderService.kt:47-58`
    - Category: Correctness
    - Problem: `sync` reads the remote list before it takes the row lock, and the lock only orders the stores. Suppose sync A lists at t0, the owner creates or deletes a folder on the server, and sync B lists at t1 and stores first. A then stores its older list after B. A folder that exists on the server is deleted from the cache, or a deleted folder comes back with a new id. The deleted row's messages lose their `folder_id` (`on delete set null`). This is the out-of-order-result case: the CR-1 fix serialised the stores but not the reads they are based on.
    - Why it matters: the window is small and the next sync repairs the folder list. But once the header-sync/IDLE tasks call `sync` in the background, a stale store can detach cached messages from a folder that still exists.
    - Suggested fix: take the account lock before listing, so that list and store run as one critical section per account. A per-account in-process lock around the whole `sync` works, or the row lock taken first if the transaction may span the IMAP call. Alternatively, skip the store when another sync of the account committed after this one started listing.

- [ ] **CR-2 | No test shows that a reloaded password account reuses its idle connection**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap/ImapConnectionPoolTests.kt:130-139`
    - Category: Tests
    - Problem: since CR-5, reuse depends on `ImapConnectionSettings` equality, which includes `EncryptedSecret` and therefore depends on `EncryptedSecret.equals`. The reuse test builds the account without a password (`password = null`). The GreenMail pool tests pass the same `MailAccount` instance each time, and `MailFolderSyncTests` mocks the pool. In production every call loads the account again and gets a new `EncryptedSecret`, and no test covers that path.
    - Why it matters: if value equality on `EncryptedSecret` were removed or changed, every borrow for a password account would close the idle connection and open a new one. The pool would never reuse a connection, and no test would fail.
    - Suggested fix: in the reuse test, give both instances a password with the same value but separate `EncryptedSecret` objects. Or reload the account through `MailAccountService.get` in a GreenMail pool test and assert `openConnections == 1`.

- [ ] **CR-3 | The fix report cites commits that are not in the history**
    - File: `reviews/mail-imap/fixes.md:7`, `:13`, `:29`, `:49`, `:64`, `:79`
    - Category: Quality
    - Problem: `fixes.md` names commits 9e0536a8 and a3a14475, but `git log --all` contains neither. The fixes are in e2419dea (CR-1..CR-4) and 7128f112 (CR-5).
    - Why it matters: anyone auditing the quality evidence cannot match the report to the commits that contain the fixes.
    - Suggested fix: have the fixer (or a new fixer run) update the commit references. The parent must not edit the helper's report by hand.

## Review conclusion

No material issues found. The five round-1 findings are resolved in the code at 2f82ff2f. The remaining findings are low priority and optional, so they do not block completion.
