# Fix Report

Date: 2026-10-20

Task: mail-read-api - Mail read API

Scope: findings CR-1..CR-7 from `reviews/mail-read-api/review.md` on the task commits `d0c4d544..00000998`
(base `341416659fa12b07a8055f1de2711b9eba6d1245`). I also fixed two pre-existing test defects in the tests committed
in 00000998, because they kept `./gradlew test --tests 'ch.sonensei.cockpit.mail.api.*'` red (see "Additional fixes").

## Fixed findings

### CR-1 — The image proxy HTTP endpoint has no tests for success or error handling

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailImageProxyWebTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailReadWebTests.kt`

Problem:
Commit 00000998 already added `MailImageProxyWebTests` (200 with content type, nosniff, CSP sandbox, private
Cache-Control, inline disposition; 502 NOT_AN_IMAGE / UPSTREAM_FAILED; 400 INVALID_URL), but the class was red:
the proxy URL was put into the MockMvc URL template already percent-encoded, so MockMvc encoded it again and every
request came back as 400 INVALID_URL. The 200 test also asserted that no `Set-Cookie` header exists, although
the app's own CSRF filter sets `XSRF-TOKEN`. The 504 TIMEOUT and TOO_LARGE status classes were not covered.

Fix:
- The URL is now passed with `param("url", ...)` (also in `MailReadWebTests`' "refuses internal addresses" test,
  which failed the same way).
- The cookie assertion now checks what it should: the image server's `tracking=` cookie is not passed on.
- The test context now has `max-size=1KB` and `timeout=1s`. A new test covers 502 TOO_LARGE and 504 TIMEOUT
  (`application/problem+json` with `errorCode` and the fixed `detail`).

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.api.*'` — Pass (MailImageProxyWebTests 4/4,
  MailReadWebTests 21/21)

### CR-2 — No test that the body cache size limit also evicts `mail_body_attachments`, or that a message whose body was evicted is fetched again

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailBodyCacheEvictionGreenMailTests.kt` (new)

Problem:
`MailBodyAttachmentStoreTests` (00000998) already shows, at the persistence level, that evicting a body removes
its `mail_body_attachments` row. Nothing showed the end-to-end path: that reading B under a small limit evicts A,
and that reading A again goes back to IMAP and returns the same attachments.

Fix:
New `@ExternalTest` GreenMail test with `cockpit.mail.body-cache-max-bytes=1`. It reads A, then B, and asserts
that A has neither a `mail_body` nor a `mail_body_attachments` row while B has both (the two always occur
together). It then reads A again: the attachments JSON is identical to the first read, the body text is the same,
A is cached again and B is evicted.

Validation:
- `cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.api.*'` — Pass
  (MailBodyCacheEvictionGreenMailTests 1/1, MailReadGreenMailTests 5/5)

### CR-3 — The image proxy's time limit is not a hard total

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/RemoteImageFetcher.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/RemoteImageFetcherProtocolTests.kt`

Problem:
`soTimeout` was set once after connecting. A server sending a byte just before the deadline could keep the next
blocking read (or the TLS handshake) open for another full budget. DNS resolution was not counted.

Fix:
- The connection uses a private `DeadlineSocket` (a `Socket` subclass). Its input stream sets
  `soTimeout = time left` before every read, or throws `SocketTimeoutException` once the budget is used up. The
  TLS socket is layered on it and reads through the same stream, so this also bounds the handshake. The test
  below shows it.
- An `IOException` whose cause chain holds a `SocketTimeoutException` (TLS wraps it in an `SSLException`), or that
  happens after the deadline, maps to TIMEOUT.
- Name resolution runs on a small daemon-thread pool and is awaited with `get(time left)`. A hanging lookup gives
  TIMEOUT, and its thread is left to finish on its own (the JDK resolver cannot be interrupted).
- The KDoc states what the budget covers.
- New tests (each fails on the old code and passes now): a byte 700 ms into an 800 ms budget does not extend it;
  a trickling TLS handshake is bounded; a hanging resolver gives TIMEOUT. Each one asserts TIMEOUT within 1300 ms.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.api.RemoteImageFetcher*'` — Pass (30 tests); the 3
  new tests failed against the unchanged `RemoteImageFetcher.kt` (checked by temporarily restoring it)

### CR-4 — A client that stops an attachment download is reported as a mail server failure and closes a working IMAP connection

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailReadService.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailMessageController.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailAttachmentDownloadTests.kt` (new)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailReadGreenMailTests.kt`

Problem:
The servlet output stream was written inside `withRemoteMessage`, so an `IOException` from the client side was
mapped to `MailConnectionException(CONNECTION_FAILED)`, logged as a mail server failure, and the pooled IMAP
connection was closed.

Fix:
`MailReadService.attachment` now takes `open: (MailAttachment) -> OutputStream` (the controller sets the
headers and returns `response.outputStream`) and copies the data itself. Read failures (IMAP) are still thrown
inside the IMAP block and mapped as before. A write or flush failure (client) ends the copy and is *returned* from
the block, so `withStore` sees a normal end and keeps the connection. Only after that is
`AttachmentDownloadAbortedException` thrown. The controller handles it at DEBUG without writing a body.
Tests:
- Unit (`MailAttachmentDownloadTests`, mockk): streaming works; a write or flush failure gives
  `AttachmentDownloadAbortedException` while the IMAP block ends normally; a server read failure still leaves the
  block with an `IOException`.
- GreenMail: after an aborted download `pool.idleConnections(account) == 1`, and the next download works.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.api.*'` — Pass (MailAttachmentDownloadTests 4/4)
- `cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.api.*'` — Pass

### CR-5 — The list queries sort by `coalesce(received_at, created_at)`, so the received_at indexes are never used

Files changed:
- `server/src/main/resources/db/migration/mail/V202610202300__mail_message_list_sort.sql` (new)

Problem:
The existing indexes are on `received_at`, but the list sorts and pages on `coalesce(received_at, created_at)`,
so every page sorted the whole scope.

Fix:
A new migration adds expression indexes `mail_message_folder_sort (folder_id, coalesce(received_at, created_at)
desc, id desc)` and `mail_message_account_sort (account_id, ...)`, both `where uid is not null`, matching the
query. No applied migration was edited and the old indexes stay. I checked the plan once with a temporary test
(deleted afterwards): `EXPLAIN` of the flat folder query (with the keyset condition) gives
`Index Scan using mail_message_folder_sort`, and the `ROW(coalesce(...), id) < ROW(...)` cursor condition is part
of the index condition.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.api.*'` — Pass (Flyway applies the migration in
  every Postgres test context)
- Temporary `EXPLAIN` test (with `enable_seqscan`/`enable_sort` off) — the index is used

### CR-6 — `cid:` references are compared without URL-decoding

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailReadService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailReadWebTests.kt`

Problem:
`cid:part1%40example.test` did not match the Content-ID `part1@example.test`.

Fix:
The reference is percent-decoded with `URLDecoder` after escaping `+` as `%2B` (only `%XX` is decoded, `+`
stays a plus), then angle brackets are stripped. Matching tries the decoded value first and falls back to the
raw value (also when an escape is malformed, e.g. `100%@...`). New test: `%40`, an encoded `%3C...%3E` form,
`a+b@` (does not match a `a b@` part) and the malformed `100%@` fallback.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.api.*'` — Pass

### CR-7 — The OpenAPI file leaves out two documented error responses

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailMessageController.kt`
- `api/openapi.yaml` (regenerated)

Problem:
`getMailMessage` and `downloadMailAttachment` did not declare 503 (POOL_EXHAUSTED), and `getMailMessage` did not
declare 400.

Fix:
Added `@ApiResponse` 503 to both operations and 400 ("Malformed id or unknown `remoteImages` value") to
`getMailMessage`. Regenerated `api/openapi.yaml` with `UPDATE_OPENAPI=1`. `npm run api:gen` produced no changes
in `web/src/app/api/generated/` (response codes are not part of the generated client).

Validation:
- `cd server && UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'` — Pass
- `cd web && npm run api:gen` — Pass, no diff
- `cd server && ./gradlew check` (runs OpenApiContractTest without the flag) — Pass

## Additional fixes (pre-existing test defects, outside CR-1..CR-7)

These tests were committed in 00000998 and failed on unchanged HEAD. I fixed them because the parent asked for
the mail.api tests and `./gradlew check` to pass:
- `RemoteImageFetcherProtocolTests` "HTTPS checks the certificate against the host name": the test assumed
  WireMock's built-in certificate is issued to `localhost`. It is `CN=Tom Akehurst` with no SAN, so the
  trusting fetch could never succeed. The test now creates a throwaway self-signed `localhost` certificate (EC,
  SAN `dns:localhost`, 2 days) with the JDK's `keytool` in a JUnit `@TempDir` and runs WireMock with it. The
  assertions are unchanged and keep their strength: untrusted fails, trusted `localhost` succeeds, another host
  name fails.
- `MailImageProxyWebTests` / `MailReadWebTests`: double-encoded proxy URL and the `Set-Cookie` assertion (see CR-1).
- `./gradlew ktlintFormat` reformatted a few long lines in `MimeContentTests.kt` and `MailImageProxyWebTests.kt`
  (formatting only), so `ktlintCheck` passes.

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.api.*'` — Pass (PublicAddresses 50,
  MimeContent 40, MailReadWeb 21, RemoteImageFetcherProtocol 19, RemoteImageFetcher 11, MailBodyAttachmentStore 9,
  MailImageProxyWeb 4, MailAttachmentDownload 4; 0 failures)
- `cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.api.*'` — Pass (MailReadGreenMail 5,
  MailBodyCacheEvictionGreenMail 1)

Full test suite:
- `cd server && ./gradlew check` — Pass (ktlint + all unit tests, 5m40s). An earlier run had one failure in
  `core.persistence.ClockConfigurationTests` ("2026-10-20T21:03:30.923885Z should be >= ...923885105Z"). That is a
  timing-dependent comparison of a microsecond clock against `Instant.now()`, unrelated to this task (from
  p0-db-conventions). It passed on rerun and in the full rerun.

Lint/type-check/build:
- `cd server && ./gradlew ktlintFormat` then `ktlintCheck` (part of `check`) — Pass

`.taskcheck`:
- See the final status line below.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
