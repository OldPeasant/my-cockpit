# Code Review

Date: 2026-10-21

Task: mail-read-api - Mail read API

Scope: `git diff f13bb21ecd893e1596050797c46652c3769ab095...HEAD`, fourth review round on the final code. Reviewed revision: `c9a1f81c1a4efa0c6834bd374b988b748422f9c3`. The only production change since the third round's reviewed revision `aad84511` is in `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/RemoteImageFetcher.kt` (`PublicAddresses.isPublicV6` and its KDoc, commits 5ec7ae51 and c9a1f81c), plus `PublicAddressesTests.kt`. Everything else in that range that is not under `mail/api` came in with the origin/main merge (files-api, web-task-detail) and belongs to other tasks. I re-read in full: `MailMessageController.kt`, `MailReadService.kt`, `MailContentLoader.kt`, `MailMessageListQuery.kt` and `RemoteImageFetcher.kt`.

Requirements:
- `tasks/mail-read-api.md` acceptance criteria 1-4 and its ASSUMPTION notes (image proxy limits and SSRF, attachment download headers, list scopes, no \Seen, older mail deferred to `mail-load-older`, infra placed in `mail.api`)
- SPEC.md §4.1, §4.2, §7.2-7.4
- `docs/ARCHITECTURE.md` conventions, `AGENTS.md` project notes
- the third round's `reviews/mail-read-api/review.md` (CR-1, CR-2), `reviews/mail-read-api/fixes.md`, `reviews/mail-read-api/tests.md` (TW-1)

Git range:
- Branch: main (local task clone)
- HEAD: c9a1f81c1a4efa0c6834bd374b988b748422f9c3
- Base/start: f13bb21ecd893e1596050797c46652c3769ab095
- Commits reviewed: the task's commits in the range; this round looked closely at 5ec7ae51 and c9a1f81c (the CR-1 fix) on top of the previously reviewed aad84511. There were no uncommitted changes before this report was written.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None. The third round's CR-2 (persistence, IMAP and HTTP client code in `mail.api`) is still in the code. It is recorded as a task assumption (2026-10-21 01:14, dell26/agent-5) and deferred by decision, so I do not raise it again.

## Verified without findings

- Third round's CR-1 / TW-1 is fixed. I checked each new range in `PublicAddresses.isPublicV6` (`RemoteImageFetcher.kt:112-126`) against its prefix bits:
  - `2001:2::/48`: bytes `20 01 00 02 00 00`. Correct.
  - `3fff::/20`: `b[0] == 0x3f && b[1] == 0xff && (b[2] and 0xf0) == 0`. This is exactly the first 20 bits `0011 1111 1111 1111 0000`. The draft's `b[1] >= 0xf0` covered `3ff0::/12`, which is too broad, and that is corrected.
  - `64:ff9b:1::/48`: bytes `00 64 ff 9b 00 01`. Correct.
  - `::ffff:0:0:0/96` (SIIT): bytes 0-7 zero, then `ff ff 00 00`. Correct. It does not overlap the IPv4-mapped branch, which requires bytes 0-9 to be zero.
  - Well-known NAT64 `64:ff9b::/96` still has its embedded IPv4 address checked, because the `embeddedV4` branch runs first and requires bytes 4-11 to be zero. The new `/48` check therefore does not shadow it.
- The KDoc (lines 58-64) now matches the policy.
- The tests cover both ends of each range. Blocked: `64:ff9b:1:ffff:...`, `2001:2:0:ffff::1`, `3fff:fff:ffff::1`. Still public, just outside the ranges: `3fff:1000::1`, `3ffe:ffff::1`, `3ff0::1`, `2001:2:1::1`, `2001:3::1`, `64:ff9b:2::1`. The SIIT test is kept. Together these would catch the draft's over-broad `3fff` check and any regression to the original gap.
- The fix does not change the API surface, so `api/openapi.yaml` and the generated client did not need to be regenerated for it.
- The rest of AC1/AC2 (list scopes and keyset cursor, threaded counts, thread endpoint, read-only EXAMINE + peek, attachment streaming and headers, image proxy pinning, deadline, size and type limits, body cache with attachment metadata) is unchanged since the third round and still matches its verified description.
- Test execution: the sandbox denied my request to run Gradle, so I could not re-run the tests myself. The existing result files in `server/build/test-results/test/` (written 02:01, just before commit c9a1f81c at 02:04, which changed only the code above) show `PublicAddressesTests` 85 tests, 0 failures, and `OpenApiContractTest` 1 test, 0 failures. That matches `fixes.md` (213 mail.api tests passing). The parent worker should still record its own runs of AC3 (including the `external` GreenMail tests) and AC4 with `tasks.py verify`.

## Review conclusion

No material issues found.
