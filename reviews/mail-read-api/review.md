# Code Review

Date: 2026-10-21

Task: mail-read-api - Mail read API

Scope: `git diff f13bb21ecd893e1596050797c46652c3769ab095...HEAD` (third review round, on the final code including the test-writer commit). Reviewed revision: `aad84511681ee208d0276130f1bb660d1e60db06`. Production code: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/*` (7 files), migrations `V202610202000__mail_body_attachments.sql` and `V202610202300__mail_message_list_sort.sql`, `api/openapi.yaml`, and the generated web client. I also read the existing `MailBodyCache`, `MailMessageLocator`, `CursorPage` and `SessionCookies` for context.

Requirements:
- `tasks/mail-read-api.md` acceptance criteria 1-4 and the ASSUMPTION notes (list scopes, threading, no \Seen, the attachment metadata table, image proxy limits and SSRF, attachment download headers, older mail deferred to `mail-load-older`)
- SPEC.md §4.1 (HTML mail is untrusted, image proxy), §4.2 (cursor pagination, Problem Details, ETag), §7.2 (body cache LRU, size-bounded), §7.3, §7.4
- `docs/ARCHITECTURE.md` conventions, `AGENTS.md` project notes
- the earlier `reviews/mail-read-api/review.md` and `fixes.md`, plus `reviews/mail-read-api/tests.md` (TW-1)

Git range:
- Branch: main (local clone of the task work, with origin/main merged)
- HEAD: aad84511681ee208d0276130f1bb660d1e60db06
- Base/start: f13bb21ecd893e1596050797c46652c3769ab095 (merge-base with origin/main)
- Commits reviewed: 16 (including 2 merge commits). There are no uncommitted changes.

Summary: 0 P0, 1 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | The SSRF address policy allows non-public IPv6 ranges, and the committed tests for them fail (AC2, AC3, .taskcheck)**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/RemoteImageFetcher.kt:98-117`
    - Category: Security
    - Problem: This is the same issue as TW-1 in `tests.md`, confirmed by reading the code. `isPublicV6` takes an embedded IPv4 address only from the well-known NAT64 prefix `64:ff9b::/96`, where bytes 4-11 must all be zero. The local-use NAT64 prefix `64:ff9b:1::/48` (RFC 8215) therefore falls through to the generic check and counts as public, even when it embeds `10.0.0.1`. The same happens to benchmarking `2001:2::/48`, because the code only checks `2001:0::/32` and `2001:db8::/32`, and to documentation `3fff::/20`. The KDoc at lines 58-62 says NAT64, benchmarking and documentation ranges are rejected. A related gap: the IPv4-translated form `::ffff:0:a.b.c.d` (`::ffff:0:0:0/96`, used by SIIT) is not treated as embedding IPv4 either, because the mapped-address check requires bytes 0-9 to be zero. `PublicAddressesTests` (line 82) now has a test for the first three ranges that fails on purpose, so `./gradlew test --tests 'ch.sonensei.cockpit.mail.api.*'` fails (3 of 202) and so does `sh scripts/check.sh`.
    - Why it matters: In a deployment that uses a local NAT64 or SIIT translator, an attacker-controlled DNS name that resolves to `64:ff9b:1::<private IPv4>` reaches internal IPv4 hosts through the image proxy. That bypasses the SSRF protection AC2 requires. Until this is fixed, AC3 and the `done` check cannot pass.
    - Suggested fix: In `isPublicV6`, treat all of `64:ff9b::/32`-style NAT64 prefixes as not public: block `64:ff9b:1::/48` outright, or extract the embedded IPv4 address and check it. Also block `2001:2::/48` and `3fff::/20`, and either block `::ffff:0:0:0/96` or check its embedded IPv4 address. Then rerun the TW-1 test, the mail.api package and `.taskcheck`.

## P2 Medium

None.

## P3 Low

- [ ] **CR-2 | Persistence, IMAP and HTTP client code is in the `mail.api` package**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailContentLoader.kt:28` (`MailBodyAttachmentStore`, JDBC), `MailMessageListQuery.kt:47` (JDBC), `RemoteImageFetcher.kt:133` (socket HTTP client)
    - Category: Architecture
    - Problem: `docs/ARCHITECTURE.md` line 191 says server packages are `<module>.{api,domain,infra}`. This task puts a JDBC store, a SQL list query, the IMAP content loader and a raw-socket HTTP client into `mail.api`, next to the controller. The mail module already departs from the convention (`model`, `imap`, `sanitize`, `sync`), and the task's `touches` only allowed `mail/api`, so this is a deliberate trade-off rather than an error.
    - Why it matters: Later mail tasks (`mail-actions-api`, `mail-search`, `mail-load-older`) will probably reuse the content loader and the list query. With infrastructure in the web layer's package, it is less clear where new code belongs.
    - Suggested fix: Do not change this inside this task. Record it with `tasks.py assume` or a note in `quality.json`, and move these classes to the mail module's infra/model packages in a later task that touches them.

## Verified without findings

- AC1: list scopes match the assumptions (unified Inbox by role, an account's Inbox, a folder with an account check). Flat and threaded SQL use a keyset on `coalesce(received_at, created_at) desc, id desc`, and partial expression indexes match it. The cursor round-trips at microsecond precision against `timestamptz`, and malformed cursors and limits give 400. Threaded counts are per scope. The thread endpoint is account-bound, deduplicated by Message-ID (keeping the requested copy) and sorted oldest first. `GET /messages/{id}` returns sanitized HTML, text, `cid:` rewriting with percent-decoding, the PROXY rewrite, an ETag, and attachment metadata.
- No \Seen side effect: folders are opened with `READ_ONLY` and messages fetched with `peek = true`.
- AC2 attachments: streamed in 16 KiB chunks while the IMAP connection is held. Downloads are always `application/octet-stream` + `attachment`, except `inline=true` for the raster allow-list. `nosniff`, a `default-src 'none'; sandbox` CSP and `no-store` are set. Filenames have control characters removed and are encoded by Spring's `ContentDisposition` (RFC 5987), so there is no header injection. The part id is validated by a regex before navigating the MIME tree, and text/html body parts are never downloadable.
- AC2 image proxy (apart from CR-1): http/https only, no userinfo, ports 80/443. Every resolved address is checked, and the connection is pinned to the checked address (DNS rebinding). Each redirect is checked again, up to `maxRedirects`. One deadline covers DNS, connect, TLS and every read. Content-Length and chunked bodies are capped at `maxSize`. The type must be on a raster allow-list (no SVG), and compressed encodings are rejected. The request target comes from `java.net.URI` raw components, which reject CR/LF, so there is no request-line injection. No upstream headers are passed on, and error details hold no URL.
- AC2 body cache: `MailBodyCache.put` evicts LRU down to `body-cache-max-bytes`. `mail_body_attachments` cascades with its body, so a cache hit needs both. Reads touch `last_accessed_at`.
- AC4: `api/openapi.yaml` and the generated client contain the five operations with their 400/404/502/503/504 responses. According to `tests.md`, `OpenApiContractTest` passes without the flag. The previous rounds' CR-2/CR-3 (pooled connection on 404, socket close and trailing-dot SNI) are fixed in the current code.

## Review conclusion

Material issues found; fix before completing the task. CR-1 (TW-1) must be fixed so that the SSRF protection matches its documented contract and the mail.api tests and `.taskcheck` pass again. CR-2 needs only a recorded disposition.
