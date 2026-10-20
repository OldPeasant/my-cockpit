# Test Report

Date: 2026-10-21

Task: mail-read-api - Mail read API

Scope: `git diff f13bb21ecd893e1596050797c46652c3769ab095...e0d997572c809138b22be327ddbff666caeaf50b` (base = origin/main f13bb21e supplied by the parent; HEAD e0d99757 on branch main, which merges the wip/mail-read-api commits 5f2ef2e8..48037111). Task-owned production code: the 7 files of `server/src/main/kotlin/ch/sonensei/cockpit/mail/api` (all added by the task) plus two Flyway migrations. Working tree was clean before testing; the only uncommitted changes are the two test files listed below.

Requirements:
- `tasks/mail-read-api.md` acceptance criteria 1-4 and the recorded ASSUMPTION notes (list scopes, threading, no \Seen, attachment metadata table, image proxy limits/SSRF, attachment download headers)
- SPEC.md §7.4 (via the task goal), `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`
- the SSRF contract documented on `PublicAddresses` in `RemoteImageFetcher.kt` (lines 58-62)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailReadWebTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/PublicAddressesTests.kt`

The existing suite (12 test classes, 182 unit + 8 external GreenMail tests, written in earlier rounds) already covered the criteria broadly; only gaps were added. No existing test was changed in behavior; the `message()` helper of `MailReadWebTests` now accepts a null Message-ID (default unchanged).

## Tests added or updated

Count: 4 test methods (20 test invocations)

- `MailReadWebTests.the thread endpoint keeps the requested copy of a duplicated message` - the thread dedup by Message-ID keeps the copy that was asked for (previously untested branch of `MailReadService.thread`).
- `MailReadWebTests.messages without Message-ID are never merged in the thread endpoint` - two thread messages without Message-ID both appear (dedup falls back to the row id; MailReadService.kt:125 branch).
- `PublicAddressesTests.addresses just outside the blocked ranges are public` (15 cases) - near-miss boundaries for every blocked IPv4 range (169.253/169.255, 192.0.1, 192.88.98, 198.17/198.20, 198.51.99, 203.0.112, 223.255.255.254, ...) and IPv6 2001:4860::/2003:: so the SSRF policy does not over-block.
- `PublicAddressesTests.IPv6 local-use NAT64, benchmarking and documentation addresses are not public` (3 cases) - FAILS, see TW-1.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 list: unified / account Inbox / folder, flat and threaded, cursor | MailReadWebTests (unified list, account/folder, detached, cursor paging incl. ties, threaded counts and scope, empty folder, exact-limit page, null arrival time, parameter validation, malformed cursors) | `./gradlew test --rerun --tests 'ch.sonensei.cockpit.mail.api.*'` | Pass |
| AC1 thread endpoint (across folders, once per Message-ID, account-bound) | MailReadWebTests thread tests incl. the 2 new ones | same | Pass |
| AC1 GET by id: sanitized HTML / text, cid images, PROXY rewrite, moved copy, ETag, attachment metadata, 404/400, login required | MailReadWebTests, MailBodyAttachmentStoreTests, MimeContentTests; MailReadGreenMailTests (fetch from server, sanitize, cache, never sets \Seen) | unit command above; `./gradlew integrationTest --rerun --tests 'ch.sonensei.cockpit.mail.api.*'` | Pass |
| AC2 attachment download streaming, inline only for raster images, nosniff/CSP, client abort, server errors 502/503 | MailAttachmentDownloadTests, MailMessageControllerTests, MailReadGreenMailTests (download, abort keeps connection, expunged/deleted folder/UIDVALIDITY 404, unreachable 502) | both commands above | Pass |
| AC2 image proxy size/time limits | RemoteImageFetcherTests, RemoteImageFetcherProtocolTests, MailImageProxyWebTests | unit command | Pass |
| AC2 image proxy SSRF protection (public unicast only, ports 80/443, DNS pinning, redirects rechecked) | PublicAddressesTests, RemoteImageFetcherTests, MailReadWebTests image proxy test | unit command | Fail: 3 cases of the new IPv6 test fail (TW-1); all other SSRF tests pass |
| AC2 body cache LRU with size bound (attachment metadata evicted with the body) | MailBodyAttachmentStoreTests (LRU eviction removes metadata, cache hit/miss rules), MailBodyCacheEvictionGreenMailTests | both commands | Pass |
| AC3 `./gradlew test --tests 'ch.sonensei.cockpit.mail.api.*'` plus external GreenMail tests pass | all mail.api tests | both commands | Fail: 202 unit tests, 3 failed (TW-1); external 8/8 passed |
| AC4 OpenAPI documented and `OpenApiContractTest` passes without the flag | `ch.sonensei.cockpit.core.web.OpenApiContractTest` | ran inside `sh scripts/check.sh` (server `./gradlew check`), without UPDATE_OPENAPI | Pass (1/1); regeneration with UPDATE_OPENAPI=1 not run by the test-writer |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.mail.api.PublicAddressesTests' --tests 'ch.sonensei.cockpit.mail.api.MailReadWebTests'` and `./gradlew ktlintTestSourceSetCheck`

Result:

Fail - 104 tests, 3 failed (only the TW-1 cases); ktlint on the test sources passes.

Package runs:

- `cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.mail.api.*'` (2026-10-21 01:00:17-01:01:09 +0200): Fail - 202 tests, 199 passed, 3 failed (TW-1).
- `cd server && ./gradlew integrationTest --rerun --tests 'ch.sonensei.cockpit.mail.api.*'` (GreenMail, tag external; 2026-10-21 00:59:24-01:00:13 +0200): Pass - 8/8 (MailReadGreenMailTests 7, MailBodyCacheEvictionGreenMailTests 1).
- Before adding tests (baseline, 00:55:45-00:57:17): `./gradlew test --rerun --tests 'ch.sonensei.cockpit.mail.api.*' jacocoTestReport` Pass - 182/182.

## Full test execution

Command:

`sh scripts/check.sh` (the full server suite via `./gradlew check`, 2026-10-21 01:02:27-01:09:13 +0200)

Result:

Fail - server: 2347 tests, 4 failed: the 3 TW-1 cases and `ch.sonensei.cockpit.core.persistence.ClockConfigurationTests.the application clock is the system clock in UTC` (asserts `2026-10-20T23:07:06.750970Z should be >= 2026-10-20T23:07:06.750970401Z`, a precision race outside this task; rerun alone with `./gradlew test --rerun --tests 'ch.sonensei.cockpit.core.persistence.ClockConfigurationTests'` -> Pass, so it is flaky, not caused by this task). The web and android steps did not run because the script stops at the first failing step.

## .taskcheck

Result:

Fail - `sh scripts/check.sh` fails at the server step for the reasons above (TW-1 plus one unrelated flaky test). It will keep failing until TW-1 is fixed.

## Bugs found

### The SSRF address policy treats non-public IPv6 ranges it documents as blocked as public

Finding ID:

`TW-1`

Test:

`PublicAddressesTests.IPv6 local-use NAT64, benchmarking and documentation addresses are not public` (cases `64:ff9b:1::a00:1`, `2001:2::1`, `3fff::1`)

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/mail/api/RemoteImageFetcher.kt:98-117` (`PublicAddresses.isPublicV6`)

Requirement:

AC2 "remote image proxy with ... SSRF protection"; task assumption "only public unicast addresses"; the `PublicAddresses` contract (RemoteImageFetcher.kt:58-62) says it rejects documentation and benchmarking ranges and IPv6 transition addresses embedding blocked IPv4 addresses (NAT64).

Expected behavior:

`isPublic` returns false for the RFC 8215 local-use NAT64 prefix `64:ff9b:1::/48` (here embedding 10.0.0.1: on a network with a local NAT64 translator this reaches a private IPv4 host), for IPv6 benchmarking `2001:2::/48` (RFC 5180, the counterpart of the blocked 198.18.0.0/15) and for IPv6 documentation `3fff::/20` (RFC 9637, the counterpart of the blocked 2001:db8::/32). All three are "Globally Reachable: False" in the IANA IPv6 special-purpose registry.

Actual behavior:

`isPublic` returns true for all three: only the well-known NAT64 prefix `64:ff9b::/96`, `2001::/32` and `2001:db8::/32` are handled.

Severity:

Low (exploiting the NAT64 case needs a local-use NAT64 translator in the server's network; the other two are unrouted ranges the policy itself promises to block).

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: partial-failed - the coverage runs are fresh but come from a unit run with 3 failing tests (TW-1); the numbers are diagnostic until the fix is in and coverage is rerun. The failing tests only exercise the pure `PublicAddresses` function, so the numbers for the other files are unaffected.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to whole-file line coverage below, because all 7 scoped files were added in full by the task (diff line counts equal file lengths: 147, 440, 116, 44, 274, 189, 517), so every executable line is a changed line. Changed-branch coverage is not separately derived.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-read-api; base f13bb21ecd893e1596050797c46652c3769ab095 (from the parent); measured HEAD e0d997572c809138b22be327ddbff666caeaf50b; scope = `git diff --name-only f13bb21e...HEAD -- server/src/main` as supplied by the parent and matching the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD e0d99757 plus uncommitted `MailReadWebTests.kt` sha256 a013d6abb7cbdad5162f85cad2b77c628b09605c0cfba228c42b759dad2a772c and `PublicAddressesTests.kt` sha256 42d8ec77b9b1f9b709a91f7d9ec424331934285e0e77121f5c4d523732590b9f; sha256 of every mail.api main and test file recorded before the runs and verified unchanged afterwards.
- Exact included files; renamed/deleted/excluded files and reasons: included `MailContentLoader.kt`, `MailMessageController.kt`, `MailMessageListQuery.kt`, `MailReadProperties.kt`, `MailReadService.kt`, `MimeContent.kt`, `RemoteImageFetcher.kt` (all under `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/`). Excluded: `db/migration/mail/V202610202000__mail_body_attachments.sql` and `V202610202300__mail_message_list_sort.sql` (SQL, not instrumented by JaCoCo; exercised by every Postgres-backed test), `api/openapi.yaml` and `web/src/app/api/generated/**` (generated), `reviews/**`, `tasks/**` (not code). The JaCoCo entry `Comparisons.kt` in the package is Kotlin stdlib inline code (compareBy) mapped into the package, not a task file. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (Gradle 9.8.1), working directory `server/`. (1) `./gradlew test --rerun --tests 'ch.sonensei.cockpit.mail.api.*'` then `./gradlew jacocoTestReport -x test` (the documented report; test task excluded only because it had failed) -> unit-only report. (2) `./gradlew integrationTest --rerun --tests 'ch.sonensei.cockpit.mail.api.*'`, then a combined JaCoCo report over `build/jacoco/test.exec` + `build/jacoco/integrationTest.exec` produced with an ad hoc Gradle init script kept in the Git-ignored `.tmp/coverage-init.gradle.kts` (task `taskCombinedCoverage`; no project file changed), because `jacocoTestReport` only reads `test.exec` and `MailContentLoader`'s IMAP path is exercised only by the external GreenMail tests. (3) The same combined report regenerated after the full `sh scripts/check.sh` server run gave identical counters for all 7 files.
- Run start/completion time and exit status: unit 2026-10-21 01:00:17-01:01:09 +0200, exit nonzero (3 TW-1 failures); integration 00:59:24-01:00:13, exit 0; reports generated 01:01:24 (unit) and 01:02:09 (combined), exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 1bfb2255613baae26a77225625e209548cd771d554562233951bb8f24bf1c1fc; `server/build/reports/jacoco/taskCombined/report.xml` sha256 062098a26a5becb675e2c31ff9461d99064b946d438242c20d0cf910895c7c9f (from test.exec 1053f52a..., integrationTest.exec 4e4f611d...); full-suite combined report sha256 57dc3c8a67bb45743141c115c6d1f09a681bd4f3114f36ee8d03bf6dcbdb9e66.
- Evidence outputs are fresh and inputs stayed unchanged: yes - both test tasks ran with `--rerun` (not UP-TO-DATE), exec files carry the run timestamps, and the source snapshot hashes were verified unchanged after the runs.
- Final run after latest test additions and production fixes: yes for the test additions; no production fix has been made yet (TW-1 open), so coverage must be rerun after the fix.

Values below use covered/total (percentage); unit + external GreenMail tests combined. "Statements" are JaCoCo instructions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailContentLoader.kt` | 348/390 (89.2%) | 22/28 (78.6%) | 16/17 (94.1%) | 57/63 (90.5%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailMessageController.kt` | 978/1016 (96.3%) | 18/22 (81.8%) | 84/85 (98.8%) | 186/186 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailMessageListQuery.kt` | 174/174 (100.0%) | 8/8 (100.0%) | 15/15 (100.0%) | 44/44 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailReadProperties.kt` | 122/122 (100.0%) | 16/16 (100.0%) | 10/10 (100.0%) | 18/18 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailReadService.kt` | 796/798 (99.7%) | 57/58 (98.3%) | 32/32 (100.0%) | 118/118 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MimeContent.kt` | 763/783 (97.4%) | 112/120 (93.3%) | 27/27 (100.0%) | 96/97 (99.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/RemoteImageFetcher.kt` | 2016/2101 (96.0%) | 243/273 (89.0%) | 65/66 (98.5%) | 254/267 (95.1%) |
| **Aggregate (7 files, summed counters; no file missing or unmeasured)** | 5197/5384 (96.5%) | 476/525 (90.7%) | 249/252 (98.8%) | 773/793 (97.5%) |

Unit tests only (`jacocoTestReport`, the documented command): aggregate instructions 5008/5384 (93.0%), branches 463/525 (88.2%), methods 244/252 (96.8%), lines 746/793 (94.1%); the only difference is `MailContentLoader.kt` (lines 30/63, 47.6%; branches 9/28), whose IMAP path is covered by the GreenMail tests. Before this round the unit-only branch total was 450/525 (85.7%); the new tests added 13 covered branches (PublicAddresses boundaries, thread dedup).

No task-level coverage threshold is defined (`docs/ARCHITECTURE.md`: coverage informs, it is not a gate).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailContentLoader.kt:114-115` | `MessagingException` (other than folder-not-found) when opening the folder -> `MailConnectionException` (502) | Justified gap: needs IMAP fault injection GreenMail does not offer; the 502 mapping itself is covered by `MailMessageControllerTests` and the unreachable-server GreenMail test |
| `MailContentLoader.kt:122-125`, branches 118, 127 | IMAP/IO failure while fetching inside the open folder -> 502, folder closed | Same: no deterministic fault injection; the equivalent attachment-stream failure is covered by `MailAttachmentDownloadTests.a failure reading from the mail server still leaves the IMAP block as a failure` |
| `MailContentLoader.kt:103-104` (branches) | message without uid/folder/account -> 404 before IMAP | Non-material: the locator only returns located messages; defensive |
| `RemoteImageFetcher.kt:98-117` | SSRF policy for IPv6 special ranges | Tests added; 3 fail -> TW-1 |
| `RemoteImageFetcher.kt:243-246` | interrupted name lookup -> TIMEOUT | Non-material: needs interrupting the request thread; defensive |
| `RemoteImageFetcher.kt:250` | resolver throwing a RuntimeException is rethrown (would be a 500, not 502) | Not reachable with the JDK resolver for URI-validated hosts; noted, no test |
| `RemoteImageFetcher.kt:212-213` | redirect Location the URI resolver rejects with IllegalArgumentException -> 502 | Low value; the URISyntaxException path and other invalid redirects are tested |
| `RemoteImageFetcher.kt:278-280`, 287, 298 | time budget expiring between socket reads in the response head (RemoteImageException path) | Timing-dependent; head/body/TLS/DNS budget tests already cover the deadline behavior |
| `RemoteImageFetcher.kt:313-314` | single-byte `read()` on the deadline socket | Not used by BufferedInputStream/TLS in practice |
| `RemoteImageFetcher.kt:346` | host name that is not a valid SNI name -> handshake without SNI | Edge case; certificate host-name check is tested |
| `RemoteImageFetcher.kt:77` | non-IPv4/IPv6 InetAddress | Unreachable |
| `MailMessageController.kt:381, 404, 437, 438` (branches) | `checkNotNull` on folderId/createdAt/updatedAt | Unreachable for located, persisted messages |
| `MailReadService.kt:84` (branch) | a listed row whose message disappeared between the list query and the fetch | Race, not deterministically testable without production hooks; the code drops the row |
| `MimeContent.kt:130` and branches 70, 71, 84, 106, 108 | null Content-Type / non-Multipart multipart content / non-MimePart parts | Defensive paths for malformed providers; the malformed MIME cases that real messages produce are tested in `MimeContentTests` |

## Unable to test

- IMAP failures in the middle of a fetch (MailContentLoader.kt:114-125): GreenMail cannot inject them; follow-up only if a fault-injecting IMAP stub is added to the project.
- OpenAPI regeneration (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) was not run by the test-writer, since it rewrites `api/openapi.yaml` (a non-test file); the contract test without the flag passed.
- Web and Android steps of `scripts/check.sh` did not run (the script stopped at the failing server step); they are outside this task's touches.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion (TW-1: `PublicAddresses.isPublicV6` treats 64:ff9b:1::/48, 2001:2::/48 and 3fff::/20 as public; Low severity). All other task tests, including the 8 external GreenMail tests, pass. One unrelated flaky test (`ClockConfigurationTests`) failed once in the full suite and passed on rerun.
