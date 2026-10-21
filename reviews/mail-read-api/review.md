# Code Review

Date: 2026-10-21

Task: mail-read-api - Mail read API

Scope: `git diff 33db686da665f2a87732abd7e7375383b1673af7...575c63f257e714bdfcae81977c48b0613808fc40`. This is the fifth and final review round. Reviewed revision: `575c63f257e714bdfcae81977c48b0613808fc40`. The base is the merge-base with origin/main after the rebase. The previous round reviewed `c9a1f81c`, which became `eb1342c4` after the rebase. Since then, the only change to the task's code is commit 575c63f2, which changes test code only:
- `RemoteImageFetcherTests`: IPv6 local-use NAT64, SIIT, benchmarking and documentation addresses are blocked both as URL literals and as DNS answers.
- `RemoteImageFetcherProtocolTests`: a request thread that is interrupted during the name lookup.

`git diff c9a1f81c 575c63f2` shows no change under `server/src/main/kotlin/ch/sonensei/cockpit/mail` or `server/src/main/resources/db/migration/mail`. The only difference in `api/openapi.yaml` is the `/api/v1/restore` endpoints and the `Restore*` schemas. Those came from origin/main through the rebase and belong to another task. Within the task's range, the `api/openapi.yaml` changes are the mail endpoints only.

Requirements:
- `tasks/mail-read-api.md`: acceptance criteria 1-4 and its ASSUMPTION notes. These cover the list scopes, threading, no \Seen, the image proxy limits and SSRF policy, the attachment download headers, older mail being deferred to `mail-load-older`, and the infrastructure classes being placed in `mail.api`.
- SPEC.md §4.1, §4.2, §7.2-7.4
- `docs/ARCHITECTURE.md` conventions and the `AGENTS.md` project notes
- Earlier reports in `reviews/mail-read-api/`: `review.md` (round four), `fixes.md` and `tests.md`

Git range:
- Branch: main (local task clone)
- HEAD: 575c63f257e714bdfcae81977c48b0613808fc40
- Base/start: 33db686da665f2a87732abd7e7375383b1673af7
- Commits reviewed: 18 in the range. This round looked closely at 575c63f2 on top of the previously reviewed eb1342c4 (formerly c9a1f81c). The only uncommitted change is `reviews/mail-read-api/tests.md` (the test-writer's report), which is not code.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None. The earlier CR-2 is unchanged: persistence, IMAP and HTTP client code lives in `mail.api` instead of `mail.infra`. It is recorded as a task assumption (2026-10-21 01:14, dell26/agent-5) and deferred by decision, so it is not raised again.

## Verified without findings

- **The new fetcher-level IPv6 test is meaningful** (`RemoteImageFetcherTests.kt:181-204`).
  - The four DNS answers are `64:ff9b:1::808:808`, `::ffff:0:808:808`, `2001:2::1` and `3fff::1`. They hit the prefix checks at `RemoteImageFetcher.kt:117-123`.
  - Two of them embed the public address 8.8.8.8. They therefore show that the block comes from the prefix, not from the embedded IPv4 address.
  - For `::ffff:0:808:808`, bytes 8-9 are `ff ff` and bytes 10-11 are `00 00`. `InetAddress.getByName` returns an `Inet6Address` for it, not an IPv4-mapped `Inet4Address`, so the test really exercises the SIIT branch.
  - The literal case goes through `target()`. There `uri.host` keeps the brackets, and the test resolver strips them. The production resolver (`InetAddress.getAllByName`) accepts bracketed literals as well.
  - Each case runs through `fetch` → `address()` → `allowAddress` and expects `BLOCKED_ADDRESS`. This is the integration-level check that the third round's TW-1 asked for.
- **The interrupt test matches the production path** (`RemoteImageFetcherProtocolTests.kt:326-350`).
  - The calling thread's interrupt flag is set before `fetch`, so `lookup.get` at `RemoteImageFetcher.kt:249` throws `InterruptedException` immediately.
  - The handler (`:253-256`) cancels the lookup, restores the flag and maps the error to `TIMEOUT`. The test asserts both effects.
  - It runs on its own pool thread, so the JUnit thread is not left interrupted. The lookup thread is blocked on the latch, and `release.countDown()` frees it, so no lookup thread is left blocked longer than the latch's own 5 s bound.
- **The production code is unchanged since the round-four verification**, which covered:
  - list scopes and the keyset cursor;
  - threaded counts and the thread endpoint;
  - read-only EXAMINE with BODY.PEEK;
  - attachment streaming and the download headers;
  - image proxy address pinning, deadline, size and type limits, and the SSRF policy, including the CR-1 IPv6 ranges;
  - the LRU body cache with its size bound and the attachment metadata cache.

  These findings still hold.
- **The test counts match the test sources.** `integrationTest` runs the `external` tag (`server/build.gradle.kts:105-115`). `MailReadGreenMailTests` has 7 `@Test` methods and `MailBodyCacheEvictionGreenMailTests` has 1, which matches the parent's reported 8 passing `external` tests.
- **Evidence status.** I did not re-run Gradle myself in this round; my checks were read-only. The parent reports these results on HEAD:
  - `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.mail.api.*'`: 215 tests, 0 failures.
  - `./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.api.*'`: 8 passed.
  - `OpenApiContractTest` passes without the flag.
  - The test-writer reports that `sh scripts/check.sh` passes.

  `python tasks.py verify mail-read-api` currently lists all four criteria as unverified. The parent worker still has to record AC1-AC4 with these commands and results before `done`.

## Review conclusion

No material issues found.
