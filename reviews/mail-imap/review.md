# Code Review

Date: 2026-10-20

Task: mail-imap - IMAP/SMTP connectivity and folder sync

Scope: the five `mail-imap:` commits on top of the merge base 768d912 (production code in `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap`, its tests in `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap`, `api/openapi.yaml`, regenerated `web/src/app/api/generated/`). Committed changes only; the working tree was clean.

Requirements:
- `tasks/mail-imap.md` acceptance criteria and recorded assumptions
- SPEC.md §7.1 (accounts), §7.2 (connection pool), §4.2 (Problem Details, ETag/If-Match, UUIDv7)
- docs/ARCHITECTURE.md (conventions, coverage), AGENTS.md project notes (secrets never logged)
- `reviews/mail-imap/tests.md` (test-writer report)
- Parent instructions: secrets write-only and never logged, TLS host verification, pool limits, conventions

Git range:
- Branch: main
- HEAD: 0d28dbd (mail-imap: tests for pool, sessions, account service, folder sync, redaction)
- Base/start: 768d912c5e41dc9fc40fa71cf537212855a9f8b0 (`git merge-base HEAD origin/main`)
- Commits reviewed: 5 (d44ef8c, d270bd7, fd1dbad, f64096d, 0d28dbd)

Summary: 0 P0, 0 P1, 2 P2, 3 P3

Verified without findings: CRUD endpoints under `/api/v1/mail/accounts`. The password is write-only: `writeOnly` in OpenAPI, only `hasPassword` comes back in responses, it is stored as `EncryptedSecret`, and the request and credential `toString` methods are redacted. The connection-failure handler logs only the error code at INFO, and its Problem Details messages are fixed texts. PATCH and DELETE of an account and PATCH of a folder need If-Match (412/428). Sessions are built with TLS (`ssl.enable`) or with STARTTLS where `starttls.required` is set. `ssl.checkserveridentity=true` is set for every non-plain mode. XOAUTH2 limits `auth.mechanisms` to XOAUTH2 and logs in with the connection's MAIL token. The pool cannot hold more than `max` connections (in use plus idle) per live pool. The role-move ordering in `store()` respects the partial unique index on `(account_id, role)`. The regenerated client matches the operations in `openapi.yaml`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Concurrent folder syncs of the same account fail with a generic 500**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailFolderService.kt:43-47`, `:101-130`
    - Category: Correctness
    - Problem: `sync` reads the remote list outside a transaction, then `store()` reads the cached rows and inserts the missing folders in its own `TransactionTemplate`. Two syncs of the same account can run in parallel, because the pool allows 3 connections. Examples: a double click on "sync", or the header-sync/IDLE tasks that the assumptions say will call `MailFolderService.sync`. Both transactions see the same cached set and both insert the same new paths. The second one fails on the unique index `mail_folder_path (account_id, path)` with a `DataIntegrityViolationException`. `ProblemDetailsAdvice.handleUnexpected` turns that into a 500 "An unexpected error occurred" and logs it at ERROR. Concurrent updates of the same row instead give an optimistic-lock 409. Neither outcome means "the other sync already did it". The first sync of a new account always inserts every folder, so it is the most exposed. No test covers concurrent syncs.
    - Why it matters: the owner triggers a sync, or a background task runs one, and gets a 500 or an ERROR log for an operation that actually succeeded. Background callers will need their own retry logic.
    - Suggested fix: serialize syncs per account, for example with a per-account lock in the service or a `SELECT ... FOR UPDATE` on the `mail_account` row inside the store transaction. Alternatively, retry `store()` once on `DataIntegrityViolationException`/`OptimisticLockingFailureException`. Add a test that runs two syncs in parallel.

- [ ] **CR-2 | TLS host-name verification is only tested through properties, never against a server with a mismatching certificate**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailSessions.kt:186-189`; `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap/ImapGreenMailTests.kt:63-71`, `:159-168`
    - Category: Tests
    - Problem: the only evidence for host-name checking is `MailSessionsTests`, which asserts that `mail.<protocol>.ssl.checkserveridentity` equals `"true"`. The GreenMail suite tests only the positive case: `ssl-trust=localhost`, connecting to `localhost` with a certificate whose name matches. No test connects with a host name the certificate does not cover, for example `127.0.0.1` with the same `ssl-trust`. So nothing shows that such a connection is refused. It is also not verified which `MailConnectionError` the refusal maps to. Angus reports a failed identity check as a plain `IOException`, which `classify()` would map to `CONNECTION_FAILED`, not `TLS_FAILED`. I could not confirm that against the Angus jar in this environment.
    - Why it matters: host verification is a security requirement of this task. `ssl.trust` changes the socket factory Angus uses, so a configuration mistake or a library default could silently disable the check while the property test still passes. A misclassified failure would also show the user the wrong hint ("could not be reached" instead of a certificate problem).
    - Suggested fix: add an `external` GreenMail test that connects to the TLS port via a host name or IP not covered by the certificate and asserts the login is refused. Assert `TLS_FAILED`, and if Angus throws a plain `IOException`, extend `classify()` to recognize the identity-check failure.

## P3 Low

- [ ] **CR-3 | Create validation reports field names that do not exist in the request**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailAccountService.kt:118-119`
    - Category: Integration
    - Problem: a missing server on an `IMAP_SMTP` create gives `FieldError("imapHost", ...)` / `FieldError("smtpHost", ...)`. The request fields are `imap` and `smtp`, and Bean Validation reports nested errors as `imap.host`.
    - Why it matters: a client that maps `errors[].field` to form controls cannot attach these errors to the right field.
    - Suggested fix: report them as `imap` and `smtp` to match `CreateMailAccountRequest`.

- [ ] **CR-4 | Expired idle IMAP connections are only closed on the next borrow**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/ImapConnectionPool.kt:120-135`
    - Category: Quality
    - Problem: `idleTimeout` is applied only inside `reusable()`, which runs when the next caller borrows a connection. If nobody borrows again, for example after a one-off test connection or sync, up to `maxConnectionsPerAccount` sockets per account stay open until the server drops them or the application shuts down. There is no scheduled eviction.
    - Why it matters: the open sockets cost file descriptors and server-side connection slots (Gmail allows 15). The KDoc says idle connections are "reused for idleTimeout", which reads as if they are closed after it.
    - Suggested fix: add a small scheduled sweep that closes expired idle connections, or document that eviction is lazy.

- [ ] **CR-5 | A connection opened with an account snapshot loaded before an update can be kept in the new pool**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/ImapConnectionPool.kt:48-53`; `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailFolderService.kt:44-45`
    - Category: Correctness
    - Problem: `withStore` receives a `MailAccount` that the caller loaded earlier. Suppose an update that changes host or credentials commits, and `evict()` removes the pool, after the caller loaded the account but before `computeIfAbsent`. The caller then creates a fresh pool, connects with the old settings, and returns the connection as healthy. It stays idle and is reused for up to `idleTimeout`. The window is small; borrowers that wait inside a retired pool are handled correctly.
    - Why it matters: for a few minutes after a server or credential change, operations can still go to the old server or use the old login. That contradicts the documented guarantee that changing settings closes the account's connections.
    - Suggested fix: store a fingerprint of the connection settings (or the account `version`) with each pooled connection, and drop idle connections whose fingerprint differs from the account passed to `withStore`.

## Review conclusion

Material issues found; fix before completing the task.
