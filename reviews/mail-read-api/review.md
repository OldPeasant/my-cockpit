# Code Review

Date: 2026-10-20

Task: mail-read-api - Mail read API

Scope: task commits `cd6e1030..59c9fdd9` (committed changes only; working tree clean at review time)

Requirements:
- tasks/mail-read-api.md acceptance criteria #1-#4 and recorded assumptions #1-#6 (`python tasks.py assumptions`)
- SPEC.md §4.1 (HTML email untrusted, sandbox, image proxy), §4.2 (cursor pagination, Problem Details, ETag), §7.2 (body cache LRU), §7.3, §7.4
- AGENTS.md project notes (logging, migrations, generated OpenAPI/client)
- Existing code: MailBodyCache, ImapConnectionPool, MailSessions, HtmlMailSanitizer, MailMessageLocator, CursorRequest, ETags, V202610201402__mail_model.sql

Git range:
- Branch: main
- HEAD: 59c9fdd9
- Base/start: cd6e1030d394e16592b5257b91729b9e72e5a059
- Commits reviewed: 5 (16b05690, e2d6ad78, 0b3da3f4, 2a4ee89f, 59c9fdd9)

Summary: 0 P0, 0 P1, 2 P2, 5 P3

What I checked and found correct: keyset pagination on (sort_at, id) in both flat and threaded SQL (thread counts come from the window over the whole scope, and the cursor applies after `position = 1`); cursor round trip at microsecond precision; scope validation (404/400); the IMAP read path (READ_ONLY/EXAMINE, `peek = true`, UIDVALIDITY check, folder closed in `finally`, failures mapped to MailConnectionException with fixed messages and logged at INFO without server text); MIME classification and section numbering against `findPart`; part ids checked against the cached attachment list and a strict regex; attachment headers (octet-stream + attachment, inline only for raster types, nosniff, CSP sandbox, no-store, RFC 5987 filename with control characters removed); image proxy SSRF controls (scheme, userinfo, port, every resolved address checked, connection pinned to the checked IP, each redirect checked again, TLS hostname verification plus SNI, raster-only types, identity encoding, size limit with and without Content-Length or chunked, header count and line length limits, no cookies or referrer, URL not logged); `allow-private-networks` not set in any main or test resource; body cache and `mail_body_attachments` written in one transaction, with the stored body exempt from eviction and attachment rows removed with their body by the FK cascade; the generated OpenAPI matching the controllers.

Acceptance-criterion evidence: `python tasks.py verify mail-read-api` shows no criterion recorded yet. The parent worker still has to record evidence for #1-#4. That is not a finding at this stage.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | The image proxy HTTP endpoint has no tests for success or error handling**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailMessageController.kt:205`
    - Category: Tests
    - Problem: `RemoteImageFetcherTests` tests the fetcher directly. At the web layer, `MailReadWebTests` only covers the proxy URL rewriting (line 252) and the 401 without a login (line 291). Nothing calls `GET /api/v1/mail/image-proxy` through MockMvc with a stubbed fetcher. So nothing checks the 200 response (`Content-Type` from the fetched image, `X-Content-Type-Options: nosniff`, `Content-Security-Policy: default-src 'none'; sandbox`, private `Cache-Control`). Nothing checks that `handleImageFailure` maps errors to 400/502/504 `application/problem+json` with `errorCode` either.
    - Why it matters: Criterion #2 is about this endpoint. The headers and status mapping are the security contract with the client, and a broken handler or a dropped header would go unnoticed.
    - Suggested fix: Add a web test that swaps in a fetcher stub (`@MockitoBean`/`@TestConfiguration`). Assert the 200 headers and the problem response for one error from each status class (BLOCKED_ADDRESS 400, TOO_LARGE 502, TIMEOUT 504).

- [ ] **CR-2 | No test that the body cache size limit also evicts `mail_body_attachments`, or that a message whose body was evicted is fetched again**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailContentLoader.kt:75`
    - Category: Tests
    - Problem: This task makes the attachment metadata depend on the LRU body cache (new FK table with `on delete cascade`, and `cached()` needs both rows). None of the `mail.api` tests set `cockpit.mail.body-cache-max-bytes` or check eviction. `MailBodyCacheTests` (earlier task) does not know about the new table. Nothing shows that reading a second message under a small limit removes the first message's attachment row and keeps the new one. Nothing shows that reopening the evicted message goes back to IMAP instead of failing.
    - Why it matters: Criterion #2 names "body cache LRU with size bound". The cross-table consistency (FK insert after `evictBeyond`, cascade on eviction) is new here and has no tests.
    - Suggested fix: Add a persistence or GreenMail test with a tiny `body-cache-max-bytes`. Read message A, then B, and assert that A has no `mail_body` or `mail_body_attachments` row and B has both. Then read A again and assert it returns the same attachments.

## P3 Low

- [ ] **CR-3 | The image proxy's time limit is not a hard total: one socket read can block past the deadline, and DNS resolution is not counted**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/RemoteImageFetcher.kt:230`
    - Category: Correctness
    - Problem: `soTimeout` is set once per request to the time left when the connection opens (line 230). `checkDeadline` only runs between reads (lines 322, 380). A server that sends a byte just before the deadline can keep the next blocking read (or the TLS handshake) open for up to another full `soTimeout`. So a 10 s budget can take about 20 s. `resolver.resolve(host)` (line 210) runs outside the budget too.
    - Why it matters: The KDoc and the recorded assumption say "10 s total", which the code does not enforce. Each slow image holds a request thread longer than configured.
    - Suggested fix: Before each blocking read and before the handshake, set `socket.soTimeout = remainingMillis(deadline)`. Either document that DNS is not counted, or bound it.

- [ ] **CR-4 | A client that stops an attachment download is reported as a mail server failure and closes a working IMAP connection**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailContentLoader.kt:121`
    - Category: Quality
    - Problem: `write(attachment, it)` runs inside `withRemoteMessage`. An `IOException` from the servlet output stream (the client aborted) is caught as if IMAP had failed and turned into `MailConnectionException(CONNECTION_FAILED)`. `ImapConnectionPool.withStore` then closes the connection (`healthy = false`). The controller logs "Mail server operation failed" at INFO and tries to write a ProblemDetail to a response that is already committed.
    - Why it matters: The logs blame the mail server for normal client cancellations, and good pooled connections get thrown away.
    - Suggested fix: Wrap the response output stream, or catch the servlet-side exception in the controller's `write` lambda, so a client abort is rethrown as a separate exception (or ignored) instead of going through the IMAP error mapping.

- [ ] **CR-5 | The list queries sort by `coalesce(received_at, created_at)`, so the `(folder_id, received_at desc, id desc)` indexes are never used**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailMessageListQuery.kt:98`
    - Category: Quality
    - Problem: The flat (line 98) and threaded (lines 83-84) queries sort and paginate on an expression. Postgres therefore reads and sorts every located message of the scope on every page, instead of using `mail_message_folder_received` / `mail_message_account_received` (V202610201402__mail_model.sql:121-122), which were created for this list. Threaded mode needs a full window anyway, but flat mode would not.
    - Why it matters: Each page costs grow with the size of the folder (the 12-month window plus older mail loaded on demand), instead of with the page size.
    - Suggested fix: Either guarantee `received_at` is set (sync fills INTERNALDATE) and sort on it directly, or add a matching expression index in a new migration.

- [ ] **CR-6 | `cid:` references are compared without URL-decoding**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailReadService.kt:181`
    - Category: Correctness
    - Problem: RFC 2392 defines the part after `cid:` as the URL-encoded Content-ID. The code only strips the angle brackets and compares the raw text, so `cid:part1%40example.test` does not match the Content-ID `part1@example.test` and the image loses its `src`.
    - Why it matters: Inline images from mailers that percent-encode the reference are not shown.
    - Suggested fix: Compare the percent-decoded value (`URLDecoder`/`URI` decoding of `%XX` only, not `+`), and keep the raw-value comparison as a fallback.

- [ ] **CR-7 | The OpenAPI file leaves out two documented error responses**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailMessageController.kt:107`
    - Category: Integration
    - Problem: `handleConnectionFailure` returns 503 for `POOL_EXHAUSTED`, and the tag description mentions it. `getMailMessage` and `downloadMailAttachment` only declare 404/502. A `remoteImages` value outside the enum gives 400, which `getMailMessage` does not declare either.
    - Why it matters: Generated clients and readers of `api/openapi.yaml` do not see these responses (criterion #4: API documented).
    - Suggested fix: Add `@ApiResponse(responseCode = "503", ...)` to both operations and a 400 to `getMailMessage`. Then regenerate `api/openapi.yaml` and the web client.

## Review conclusion

Material issues found; fix before completing the task. There are no P0 or P1 findings. The P2 items are test gaps for criterion #2 and should be closed (or their dispositions recorded in `quality.json`) before `done`.
