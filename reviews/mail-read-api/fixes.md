# Fix Report

Date: 2026-10-21

Task: mail-read-api - Mail read API

Scope: findings CR-1..CR-3 of the second review in `reviews/mail-read-api/review.md`, on the task code since base
`275126bc94944b3e2d01e35563f46d8c60b54362` (HEAD `c94ac938`). This report covers this round only. The round 1 fixes
(first review CR-1..CR-7) are in git history (commit `54587ede`, report in `bea2707b`). All changes stay inside
`server/src/{main,test}/kotlin/ch/sonensei/cockpit/mail/api`.

## Fixed findings

### CR-2 — A 404 from the IMAP path closes a working pooled connection

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailContentLoader.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailReadService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailAttachmentDownloadTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailReadGreenMailTests.kt`

Problem:
`NotFoundException` was thrown inside `ImapConnectionPool.withStore` in four cases: the folder is gone, UIDVALIDITY
changed, the message was expunged (`MailContentLoader.withRemoteMessage`), or the part is missing
(`MailReadService.attachment`). `withStore` marks the connection healthy only when the block returns normally, so each
of these 404s closed a working connection.

Fix:
`withRemoteMessage` now returns a private `Remote.Found(value)` / `Remote.Gone` result from the `withStore` block and
throws `NotFoundException` after `withStore` has returned. `MailReadService.attachment` returns a private
`AttachmentCopy` result (`Done`, `PartMissing`, `ClientFailed`) from its block and throws `NotFoundException` or
`AttachmentDownloadAbortedException` afterwards. This follows the existing `clientFailure` pattern. The public
behaviour stays the same: the same exceptions and messages, so the same 404 Problem Details.

Tests:
- New unit test `a part missing on the server is 404 without leaving the IMAP block as a failure`: the server copy
  has no part 2. The test expects `NotFoundException`, and expects the IMAP block to end normally, so the pool keeps
  the connection.
- The GreenMail test `a message expunged on the server since the last sync is 404` now also checks
  `pool.idleConnections(account.id) shouldBe 1` after the 404. Before the fix, the connection borrowed from the idle
  pool was dropped there.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.api.*'` — Pass (169 tests, 0 failures)
- `cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.api.*'` — Pass
  (MailReadGreenMailTests 5/5, MailBodyCacheEvictionGreenMailTests 1/1)

### CR-3 — The image proxy gives 500 and leaks the socket on runtime exceptions while setting up the request

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/RemoteImageFetcher.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/RemoteImageFetcherProtocolTests.kt`

Problem:
`request()` closed the socket and mapped the error only for `RemoteImageException`, `SocketTimeoutException` and
`IOException`. Any other exception left the connected socket open and reached the generic 500 handler. One URL from
untrusted mail that triggers this is `https://example.com./a.png`, because `SNIHostName` refuses a host name with a
trailing dot.

Fix:
- `request()` has a new `catch (e: RuntimeException)` branch. It closes the socket and throws
  `RemoteImageException(UPSTREAM_FAILED, e)`. `RemoteImageException` is still caught first, so its error codes
  (for example `INVALID_URL` and `BLOCKED_ADDRESS`) stay as they are.
- `tls()` removes one trailing dot from the host. The dot-less name is used as the TLS peer host (for HTTPS
  hostname verification) and for SNI. If `SNIHostName` still refuses the name, the handshake runs without SNI, and
  endpoint identification still checks the certificate. The DNS lookup, the SSRF address check and the `Host` header
  are unchanged.

Tests:
- `HTTPS checks the certificate against the host name` now also fetches `https://localhost.:<port>/a.png` from the
  WireMock TLS server with the `localhost` certificate and expects the image. Before the fix this threw
  `IllegalArgumentException`.
- New test `a runtime failure while setting up the connection fails upstream and closes the socket`. Its SSL socket
  factory throws `IllegalStateException`. The test expects `UPSTREAM_FAILED`, and expects the server side to see
  end-of-stream right away, which shows the socket was closed.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.api.*'` — Pass

## Not fixed

### CR-1 — Nothing calls the older-mail loader, so the list ends at the cached 12-month window

Status:
Out of scope (deferred by parent)

Reason:
The parent worker handles this finding. It recorded an assumption and added the follow-up task `mail-load-older`
(commits `c92cd377`, `c94ac938`). The fixer made no code change for it.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` — Pass (no remaining issues)
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.api.*'` — Pass (169 tests, 0 failures, 0 skipped)
- `cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.api.*'` — Pass (6 tests, 0 failures)

Full test suite:
- `cd server && ./gradlew check` — Pass

Lint/type-check/build:
- Covered by `./gradlew check` (ktlint and compile) and `.taskcheck` — Pass

`.taskcheck`:
- `sh scripts/check.sh` — Pass ("all checks passed")

Remaining unresolved findings:
1 (CR-1, deferred by parent to `mail-load-older`)

## Final status

All validated findings fixed and validation passed. CR-1 is deferred by the parent.
