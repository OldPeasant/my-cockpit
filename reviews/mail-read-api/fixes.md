# Fix Report

Date: 2026-10-21

Task: mail-read-api (Mail read API, third review round)

Scope: current task, findings of the third review (`reviews/mail-read-api/review.md`, `reviews/mail-read-api/tests.md`).
Reports from earlier fix rounds are in the git history of this file.

## Fixed findings

### CR-1 (= TW-1) — The SSRF address policy allows non-public IPv6 ranges

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/RemoteImageFetcher.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/PublicAddressesTests.kt`

Problem:
`PublicAddresses.isPublicV6` treated the local-use NAT64 prefix `64:ff9b:1::/48` (RFC 8215), benchmarking `2001:2::/48`
(RFC 5180), documentation `3fff::/20` (RFC 9637) and SIIT IPv4-translated `::ffff:0:0:0/96` as public. An address in
`64:ff9b:1::/48` or `::ffff:0:0:0/96` embedding a private IPv4 address could reach internal hosts through the image proxy.

Fix:
The draft in commit 5ec7ae51 blocks all four ranges outright (both translation prefixes regardless of the embedded IPv4
address) and updates the KDoc. The checks for 2001:2::/48 (bytes `20 01 00 02 00 00`), 64:ff9b:1::/48
(`00 64 ff 9b 00 01`) and ::ffff:0:0:0/96 (bytes 0-7 zero, `ff ff 00 00`) are correct. The 3fff::/20 check was wrong:
`b[0] == 0x3f && b[1] >= 0xf0` is the same as `(b[1] and 0xf0) == 0xf0` (b holds unsigned 0..255 values), but both
match 3ff0::/12. The first 20 bits of 3fff::/20 are `0x3f`, `0xff` and a zero high nibble in byte 2. As written, the draft
also blocked public addresses such as 3ffe:: and 3ff0:: and all of 3fff:1000:: to 3fff:ffff::, while still blocking the
whole documentation range. This round corrects it to `b[0] == 0x3f && b[1] == 0xff && (b[2] and 0xf0) == 0`.
Tests added: the upper ends of the blocked ranges (`64:ff9b:1:ffff:ffff:ffff:ffff:ffff`, `2001:2:0:ffff::1`,
`3fff:fff:ffff::1`), plus a boundary test showing that the addresses just outside them stay public (`3fff:1000::1`,
`3ffe:ffff::1`, `3ff0::1`, `2001:2:1::1`, `2001:3::1`, `64:ff9b:2::1`). The first three of these failed against the draft.
The draft's SIIT test is kept.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.mail.api.*'` — Pass (213 tests, 0 failures,
  0 errors, 0 skipped; PublicAddressesTests 85/85)

## Not fixed

### CR-2 — Persistence, IMAP and HTTP client code is in the `mail.api` package

Status:
Out of scope (deferred)

Reason:
The reviewer's suggested fix says not to change this inside this task. The parent worker records it as an assumption.
The task's `touches` allow only `mail/api`, so the move belongs to a later mail task.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.mail.api.*'` — Pass, 213 tests, 0 failures

Full test suite:
- Covered by `.taskcheck` (server `./gradlew check`, web and android checks) — Pass

Lint/type-check/build:
- ktlintCheck (main and test source sets) — Pass

`.taskcheck`:
- `sh scripts/check.sh` — Pass

Remaining unresolved findings:
1 (CR-2, deferred by decision; no code change required in this task)

## Final status

All validated findings fixed and validation passed.
