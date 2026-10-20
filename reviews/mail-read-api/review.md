# Code Review

Date: 2026-10-20

Task: mail-read-api - Mail read API

Scope: final task code after the fixes for the first review: `git diff 77cb393e..HEAD`. Task commits are 225500c8..84759e20, merged into HEAD 55866563. Committed changes only. The working tree was clean at review time. A test-writer was adding tests under `server/src/test/.../mail/api` at the same time, and those tests were not reviewed.

Requirements:
- tasks/mail-read-api.md acceptance criteria #1-#4 and the recorded assumptions #1-#6 (`python tasks.py assumptions`)
- mail-header-sync assumptions #3/#5 (`loadOlder` as a service method, with "mail-read-api owns the API")
- SPEC.md §4.1 (untrusted HTML, sandbox, image proxy), §4.2 (cursor pagination, Problem Details, ETag), §7.2 (body cache LRU, older mail loaded on demand), §7.3, §7.4
- docs/ARCHITECTURE.md and AGENTS.md project notes (module boundaries, migrations, logging, generated OpenAPI/client)
- The previous review and fix report: reviews/mail-read-api/review.md (first round), reviews/mail-read-api/fixes.md

Git range:
- Branch: main
- HEAD: 55866563d9cde373b6d35cbf7259a604b71f57ad
- Base/start: 77cb393e735d1eb17c8061e23f5163007652910a
- Commits reviewed: 10 non-merge commits (225500c8..84759e20, plus the claim/release bookkeeping commits) and merge 55866563

Summary: 0 P0, 0 P1, 1 P2, 2 P3

Earlier findings, checked against the current code:
- CR-1 (no image proxy web tests) is resolved. `MailImageProxyWebTests` checks the 200 headers (type, nosniff, CSP sandbox, private cache, inline) and the 400/502/504 Problem Details with `errorCode`.
- CR-2 (no eviction test) is resolved. `MailBodyCacheEvictionGreenMailTests` runs with `body-cache-max-bytes=1`: reading B evicts A's `mail_body` and `mail_body_attachments` rows, and reading A again goes back to IMAP. `MailBodyAttachmentStoreTests` covers the cascade.
- CR-3 (time budget) is resolved. `DeadlineSocket` sets `soTimeout` to the time left before every read, and the TLS handshake reads through the same stream. DNS runs on a helper thread bounded by the deadline. Connect uses `min(connectTimeout, remaining)`. There are tests for the trickle, the handshake and the hanging resolver.
- CR-4 (client abort) is resolved. `MailReadService.copy` returns write and flush failures instead of throwing them, so `withStore` keeps the connection. `AttachmentDownloadAbortedException` is handled at DEBUG. There are unit and GreenMail tests.
- CR-5 (sort index) is resolved. `V202610202300__mail_message_list_sort.sql` adds partial expression indexes that match `coalesce(received_at, created_at) desc, id desc`. It is a new migration, and no applied one was edited.
- CR-6 (cid decoding) is resolved. Escapes are percent-decoded with `+` kept as a plus, with the value as written as a fallback, and there is a test.
- CR-7 (OpenAPI) is resolved. `getMailMessage` declares 400/404/502/503 and `downloadMailAttachment` declares 404/502/503 in `api/openapi.yaml`.

Also checked and found correct:
- Keyset pagination in flat and threaded mode: the cursor applies after `position = 1`.
- Scope validation (404 for an unknown account or folder, 400 for a folder of another account).
- The read-only IMAP path: `READ_ONLY`, `peek = true`, UIDVALIDITY check, and the folder closed in `finally`.
- Attachment part ids: they must match the cached list and the strict section regex.
- Attachment response headers: octet-stream plus `attachment`, inline only for raster types, nosniff, CSP sandbox, `no-store`, and an encoded filename with directories and control characters removed.
- SSRF controls: scheme and userinfo checks, port policy, every resolved address checked, the connection pinned to the checked IP, every redirect checked again, TLS endpoint identification plus SNI, raster-only types, identity encoding, size limits with Content-Length, chunked or neither, and limits on header count and line length.
- `allow-private-networks` is not set in any main resource.
- Body and attachment metadata are written in one transaction, and a body cached without metadata counts as a cache miss.
- Nothing logs URLs, bodies or server text at INFO.
- The new code stays inside the mail module's service and repository boundaries.

Acceptance-criterion evidence: `python tasks.py verify mail-read-api` shows no criterion recorded yet (#1-#4 are all open). The parent worker has to record what was run, including the `external` GreenMail run for #3 and the `OpenApiContractTest` run without the flag for #4, before `done`. I did not run the Gradle suites myself, because a test-writer was changing the same test source set at the same time.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Nothing calls the older-mail loader, so the list ends at the cached 12-month window**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailMessageListQuery.kt:51` (list reads only cached rows); `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt:111` (`loadOlder`, no caller)
    - Category: Integration
    - Problem: SPEC.md §7.2 says headers are cached for a window, with "older loaded on demand when scrolling". The mail-header-sync task built this only as `MailHeaderSyncService.loadOlder(folderId, limit)`. Its assumption #3 says "No REST endpoint here (mail-read-api owns the API)". `GET /api/v1/mail/messages` reads only the header cache ("Only cached headers are read") and returns `nextCursor: null` at the end of the cache. No other endpoint calls `loadOlder` (grep over `server/src/main`, `web/src`, `android` finds no caller), and no task on the board (web-mail-list, mail-search, mail-sync-perf) plans it. mail-read-api also recorded no assumption that defers it.
    - Why it matters: Once the cache window ends, clients cannot scroll to older mail in a folder. The infinite scroll in web-mail-list will stop there. The open decision fell between two tasks and nobody recorded it.
    - Suggested fix: Pick one. (a) For folder-scoped lists, call `loadOlder` when the cached rows run out and keep paging (or add a small endpoint such as `POST /api/v1/mail/folders/{id}/load-older`), with a test. (b) Record a `tasks.py assume` that older-on-demand is out of this task and add a follow-up task so the gap is tracked.

## P3 Low

- [ ] **CR-2 | A 404 from the IMAP path closes a working pooled connection**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailContentLoader.kt:110` (also lines 115 and 116, and `MailReadService.kt:147`)
    - Category: Quality
    - Problem: `NotFoundException` is thrown inside `pool.withStore` in four cases: the folder is gone, UIDVALIDITY changed, the message was expunged, or a part is missing. `ImapConnectionPool.withStore` (lines 86-92) sets `healthy = true` only when the block returns normally, so every such 404 throws away a working connection. This is the same pattern CR-4 of the first review fixed for client aborts, here on the not-found path.
    - Why it matters: A client that opens messages expunged since the last sync, which is common between syncs, causes a reconnect and login for every request. That is extra load on accounts with few connections allowed.
    - Suggested fix: Return a "gone" result from the block and throw `NotFoundException` after `withStore` returns, the same way `clientFailure` is handled in `MailReadService.attachment`.

- [ ] **CR-3 | The image proxy gives 500 and leaks the socket on runtime exceptions while setting up the request, for example an https host with a trailing dot**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/RemoteImageFetcher.kt:337` (with the catch clauses at lines 278-288)
    - Category: Correctness
    - Problem: `request()` closes `plain` and maps errors only for `RemoteImageException`, `SocketTimeoutException` and `IOException`. Any other exception escapes without closing the connected socket. One that can happen: `SNIHostName(target.host)` throws `IllegalArgumentException` for a host name the JDK refuses as an SNI name, such as a fully qualified name with a trailing dot (`https://example.com./a.png`, which `URI` accepts and DNS resolves). The generic `ProblemDetailsAdvice` handler then answers 500 instead of a `RemoteImageError` problem.
    - Why it matters: The image URLs come from untrusted mail. A crafted URL produces 500 responses and leaves an open TCP connection until GC finalizes it, instead of the documented 400/502.
    - Suggested fix: Close `plain` in a `catch (e: RuntimeException)` branch (or use a `finally` with a success flag). Strip a single trailing dot from the host before SNI, or skip SNI when `SNIHostName` refuses the name. Map whatever is left to `UPSTREAM_FAILED`/`INVALID_URL`, and add a protocol test for the trailing-dot host.

## Review conclusion

Material issues found; fix before completing the task. There are no P0 or P1 findings, and all seven findings of the first review are resolved in the current code. CR-1 needs a fix or a recorded assumption plus a follow-up task. CR-2 and CR-3 are small resilience fixes, or their disposition can be recorded in `quality.json`.
