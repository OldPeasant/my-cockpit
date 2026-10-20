# Fix Report

Date: 2026-10-20

Task: mail-imap - IMAP/SMTP connectivity and folder sync

Scope: findings CR-1..CR-5 of `reviews/mail-imap/review.md`. The parent worker fixed CR-1, CR-3 and CR-4 and added the CR-2 test in commit 9e0536a8. The fixer checked those fixes and fixed CR-5 in commit a3a14475.

## Fixed findings

### CR-1 - Concurrent folder syncs of the same account fail with a generic 500

Files changed (parent commit 9e0536a8, checked by the fixer):
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailFolderService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap/MailFolderSyncTests.kt`

Problem:
Two syncs of one account could both insert the same new folders. The second insert then broke the `(account_id, path)` unique index, and the caller got a 500.

Fix:
`sync()` now takes a `PESSIMISTIC_WRITE` lock on the `mail_account` row inside the store transaction, before it reads the cached folders. Stores of the same account run one after the other, and the second one sees the folders the first one committed. Fixer check: the lock comes before `store()` reads the cache. A deleted account still gives `NotFoundException`. The new test `two parallel first syncs of one account both succeed and store every folder once` uses a `CyclicBarrier` so that both syncs list the remote folders before either one stores them. The test exists and passes.

Validation:
- `./gradlew test --tests '*MailFolderSync*'` (with the other focused suites) - Pass
- `./gradlew ktlintFormat check` - Pass

### CR-2 - TLS host-name verification is never tested against a mismatching certificate

Files changed (parent commit 9e0536a8, checked by the fixer):
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap/ImapGreenMailTests.kt`

Problem:
Only the session properties showed that host names are checked. No test connected to a host name the certificate does not cover, and nothing confirmed which error such a failure maps to.

Fix:
New `external` GreenMail test `a trusted certificate whose name does not match the host is refused with TLS_FAILED`. It connects to the IMAPS and SMTPS ports through `127.0.0.1`, which the `localhost` certificate does not cover. `ssl-trust` includes `127.0.0.1`, so the refusal must come from the host-name check and not from the CA check. The test asserts:
- the connection test reports `TLS_FAILED` for both IMAP and SMTP;
- `pool.withStore` throws `MailConnectionException(TLS_FAILED)`;
- the root cause is a `CertificateException` whose message mentions "subject alternative names", which is the host-name check;
- no connection stays open.

`classify()` needed no change. Angus fails the identity check inside the TLS handshake with a `CertificateException` in the cause chain, and `classify()` already maps that to `TLS_FAILED`. It does not throw a plain `IOException`. The passing test confirms this against the Angus jar this project uses.

Validation:
- `./gradlew integrationTest --tests '*Imap*'` - Pass (ImapGreenMailTests: 15 tests, 0 failures, 0 errors, 0 skipped)

### CR-3 - Create validation reports field names that do not exist in the request

Files changed (parent commit 9e0536a8, checked by the fixer):
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailAccountService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap/MailAccountWebTests.kt`

Problem:
A missing IMAP or SMTP server was reported under the fields `imapHost` and `smtpHost`. The request fields are named `imap` and `smtp`.

Fix:
The errors are now reported as `imap` and `smtp`. The web test `IMAP_SMTP accounts need servers, username and password` expects `["imap", "password", "smtp", "username"]`. Fixer check: neither `web/src` (outside the generated client) nor `api/openapi.yaml` refers to `imapHost` or `smtpHost`, so no client change is needed.

Validation:
- `./gradlew test --tests '*MailAccount*'` - Pass

### CR-4 - Expired idle IMAP connections are only closed on the next borrow

Files changed (parent commit 9e0536a8, checked by the fixer):
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/ImapConnectionPool.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap/ImapConnectionPoolTests.kt`

Problem:
Expired idle connections were closed only when someone borrowed again, so sockets of accounts nobody used stayed open.

Fix:
`evictExpired()` is a `@Scheduled` job that runs every 60 s and closes idle connections at or beyond `idleTimeout`. It leaves connections in use alone. Fixer check: scheduling is enabled for the whole application, without any condition, by `@EnableScheduling` on `TrashSchedulingConfiguration` (`core/trash/domain/TrashPurgeJob.kt`). `ReminderSchedulingConfiguration` also enables it, but that one is conditional. So the sweep runs in the app. The two new unit tests pass: expired connections are closed without a borrow while fresh ones are kept, and connections in use are left alone.

Validation:
- `./gradlew test --tests '*ImapConnectionPoolTests'` - Pass

### CR-5 - A connection opened with an account snapshot loaded before an update can be kept in the new pool

Files changed (fixer commit a3a14475):
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/ImapConnectionPool.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailAccountService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap/ImapConnectionPoolTests.kt`

Problem:
A caller could load an account, and then an update could commit and evict the pool. That caller then opened a new pool with the old settings, and its connection was kept idle. Later callers could reuse it for up to `idleTimeout`.

Fix:
- New internal data class `ImapConnectionSettings`. It holds the IMAP host, port, security, username, password (compared by value; `EncryptedSecret.toString` is redacted) and OAuth connection id.
- Each idle connection stores the settings it was opened with.
- In `withStore`, before reusing anything, the pool closes every idle connection whose settings differ from those of the `MailAccount` passed in.
- `MailAccountService.connectionSettings` now uses the same class. The "settings changed" event and the pool therefore compare exactly the same fields.

New unit tests:
- `an idle connection opened with an account loaded before an update is closed instead of reused`: a stale snapshot opens a connection after `evict`. The updated account then gets a new connection, and the stale one is closed.
- `an idle connection is reused by another instance of the account with the same settings`: a reloaded account that differs only in a non-connection field, such as the display name, still reuses the connection.

Validation:
- `./gradlew ktlintFormat test --tests '*ImapConnectionPoolTests' --tests '*MailAccount*' --tests '*MailFolderSync*'` - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat test --tests '*ImapConnectionPoolTests' --tests '*MailAccount*' --tests '*MailFolderSync*'` - Pass (0 failures in MailFolderSyncTests, ImapConnectionPoolTests, MailAccountServiceTests, MailAccountWebTests)

Full test suite:
- `cd server && ./gradlew ktlintFormat check` - Pass (BUILD SUCCESSFUL; includes all server unit tests, ktlint and OpenApiContractTest)
- `cd server && ./gradlew integrationTest --tests '*Imap*'` - Pass (ImapGreenMailTests: 15 tests, 0 failures, 0 errors, 0 skipped)

Lint/type-check/build:
- Covered by `./gradlew ktlintFormat check` - Pass. The API is unchanged, so neither `api/openapi.yaml` nor the web client needed regenerating.

`.taskcheck`:
- Not run by the fixer. The parent worker runs `sh scripts/check.sh` before `done`. The server part of it (`./gradlew check`) passed.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
