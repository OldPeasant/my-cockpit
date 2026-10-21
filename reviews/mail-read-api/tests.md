# Test Report

Date: 2026-10-21

Task: mail-read-api - Mail read API

Scope: `git diff 33db686da665f2a87732abd7e7375383b1673af7...575c63f257e714bdfcae81977c48b0613808fc40` (base = merge-base with origin/main, supplied by the parent; HEAD 575c63f2 on branch main = final code after the CR-1/TW-1 fix in eb1342c4 and its tests in 575c63f2). Task-owned production code: the 7 files of `server/src/main/kotlin/ch/sonensei/cockpit/mail/api` (all added by the task) plus two Flyway migrations. Working tree clean before and after testing; this round changed no test file.

Requirements:
- `tasks/mail-read-api.md` acceptance criteria 1-4 and the recorded ASSUMPTION notes (list scopes, threading, no \Seen, attachment metadata table, image proxy limits/SSRF, attachment download headers)
- SPEC.md §7.4 (via the task goal), `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`
- the SSRF contract documented on `PublicAddresses` in `RemoteImageFetcher.kt` (lines 58-64), including the ranges added for CR-1/TW-1

## Test files changed

None in this round. The tests from earlier rounds (12 test classes in `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/`) and the CR-1 tests the fixer committed in eb1342c4/575c63f2 are the final suite.

## Tests added or updated

Count: 0

Final-round review of the CR-1/TW-1 fix tests (committed by the fixer, reviewed here, judged adequate):

- `PublicAddressesTests.IPv6 local-use NAT64, benchmarking and documentation addresses are not public` (6 cases): the former TW-1 cases plus the upper ends of 64:ff9b:1::/48, 2001:2::/48 and 3fff::/20 (`3fff:fff:ffff::1`). Passes now.
- `PublicAddressesTests.addresses just outside the blocked IPv6 ranges are public` (6 cases): `3fff:1000::1`, `3ffe:ffff::1`, `3ff0::1`, `2001:2:1::1`, `2001:3::1`, `64:ff9b:2::1`. These check the /20 nibble mask and the /48 lengths, so the fix does not over-block.
- `PublicAddressesTests.IPv6 SIIT IPv4-translated addresses are not public` (2 cases, one embedding the public 8.8.8.8, so it is blocked by prefix, not by the embedded address).
- `RemoteImageFetcherTests.IPv6 translation, benchmarking and documentation addresses are blocked as literals and DNS answers`: the fetcher refuses all four new ranges (BLOCKED_ADDRESS) as bracketed URL literals and as DNS answers with `allowPrivate = false`.
- `RemoteImageFetcherProtocolTests.a request thread interrupted during the name lookup stops at once and stays interrupted`: closes the earlier gap at `RemoteImageFetcher.kt:255-256` (interrupt -> TIMEOUT, interrupt flag kept). It is deterministic (latch, no sleeps) and runs on its own thread.

Each new range now has a positive test, a negative boundary test and a fetcher-level test. No further gap justified a new test. The SIIT prefix has no "just outside" case: the neighbouring addresses lie in the reserved ::/8 space, so asserting them as public would encode questionable behavior.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 list: unified / account Inbox / folder, flat and threaded, cursor | MailReadWebTests (unified list, account/folder, detached, cursor paging incl. ties, threaded counts and scope, empty folder, exact-limit page, null arrival time, parameter validation, malformed cursors) | `cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.mail.api.*' jacocoTestReport` | Pass |
| AC1 thread endpoint (across folders, once per Message-ID, requested copy kept, no-Message-ID messages not merged, account-bound) | MailReadWebTests thread tests | same | Pass |
| AC1 GET by id: sanitized HTML / text, cid images, PROXY rewrite, moved copy, ETag, attachment metadata, 404/400, login required | MailReadWebTests, MailBodyAttachmentStoreTests, MimeContentTests; MailReadGreenMailTests (fetch from server, sanitize, cache, never sets \Seen) | unit command above; `cd server && ./gradlew integrationTest --rerun --tests 'ch.sonensei.cockpit.mail.api.*'` | Pass |
| AC2 attachment download streaming, inline only for raster images, nosniff/CSP, client abort, server errors 502/503 | MailAttachmentDownloadTests, MailMessageControllerTests, MailReadGreenMailTests (download, abort keeps connection, expunged/deleted folder/UIDVALIDITY 404, unreachable 502) | both commands above | Pass |
| AC2 image proxy size/time limits | RemoteImageFetcherTests, RemoteImageFetcherProtocolTests (incl. interrupted name lookup), MailImageProxyWebTests | unit command | Pass |
| AC2 image proxy SSRF protection (public unicast only incl. the CR-1 IPv6 ranges, ports 80/443, DNS pinning, redirects rechecked) | PublicAddressesTests, RemoteImageFetcherTests, MailReadWebTests image proxy test | unit command | Pass (TW-1 resolved) |
| AC2 body cache LRU with size bound (attachment metadata evicted with the body) | MailBodyAttachmentStoreTests, MailBodyCacheEvictionGreenMailTests | both commands | Pass |
| AC3 `./gradlew test --tests 'ch.sonensei.cockpit.mail.api.*'` plus external GreenMail tests pass | all mail.api tests | both commands | Pass: 215/215 unit, 8/8 external |
| AC4 OpenAPI documented and `OpenApiContractTest` passes without the flag | `ch.sonensei.cockpit.core.web.OpenApiContractTest` | ran inside `sh scripts/check.sh` (server `./gradlew check`), without UPDATE_OPENAPI | Pass. The test-writer did not run regeneration with UPDATE_OPENAPI=1 (the parent reports it passes) |

## Focused test execution

Command:

`cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.mail.api.*' jacocoTestReport` (2026-10-21 02:26:29-02:27:32 +0200, exit 0)

`cd server && ./gradlew integrationTest --rerun --tests 'ch.sonensei.cockpit.mail.api.*'` (GreenMail, tag external; 02:27:39-02:28:32 +0200, exit 0)

Result:

Pass. Unit: 215 tests, 0 failures, 0 errors, 0 skipped (counted from `build/test-results/test/TEST-ch.sonensei.cockpit.mail.api.*.xml`). External: 8/8 (MailReadGreenMailTests 7, MailBodyCacheEvictionGreenMailTests 1).

## Full test execution

Command:

`sh scripts/check.sh` (2026-10-21 02:28:55-02:38:36 +0200, exit 0)

Result:

Pass. TFP gate tests pass. Server `./gradlew check` (ktlint and all unit tests) passes: 2512 tests, 0 failures, 0 errors, 0 skipped. Web: 57 files, 1074 tests passed, and lint and build passed. Android: ktlint, unit tests and lint passed. The script printed "all checks passed".

## .taskcheck

Result:

Pass (`sh scripts/check.sh`, see above).

## Bugs found

None open. TW-1 from the previous round (`PublicAddresses.isPublicV6` treated 64:ff9b:1::/48, 2001:2::/48 and 3fff::/20 as public) is fixed in eb1342c4. The fix also blocks ::ffff:0:0:0/96. Its tests pass.

## Coverage

Status: verified (fresh runs on the final revision, all test commands exit 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to the whole-file line coverage below. The task added all 7 scoped files in full (diff line counts equal file lengths: 147, 440, 116, 44, 274, 189, 527), so every executable line is a changed line. Changed-branch coverage was not derived separately.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: task mail-read-api. Base 33db686da665f2a87732abd7e7375383b1673af7 (merge-base with origin/main, from the parent). Measured HEAD 575c63f257e714bdfcae81977c48b0613808fc40. Scope = `git diff --stat 33db686d...HEAD -- server/src/main`, which matches the task's `touches` and the 18 task commits 0e260973..575c63f2.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 575c63f2 with a clean working tree. I recorded the sha256 of every mail.api main and test file in `.tmp/tw-final-snap-before.txt` (file sha256 d4b6dba686eb06368c03a3368f1b00ddfaac0d586b68ef04a113572090077d6c) before the runs. It was verified unchanged after the coverage runs and after `scripts/check.sh`.
- Exact included files; renamed/deleted/excluded files and reasons: included `MailContentLoader.kt`, `MailMessageController.kt`, `MailMessageListQuery.kt`, `MailReadProperties.kt`, `MailReadService.kt`, `MimeContent.kt`, `RemoteImageFetcher.kt` (all under `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/`). Excluded:
  - `db/migration/mail/V202610202000__mail_body_attachments.sql` and `V202610202300__mail_message_list_sort.sql`: SQL that JaCoCo does not instrument; every Postgres-backed test exercises them.
  - `web/src/app/api/generated/**`: generated.
  - `reviews/**`, `tasks/**`: not code.
  - The JaCoCo entry `Comparisons.kt` in the package: Kotlin stdlib inline code, not a task file.
  - No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (Gradle 9.8.1), working directory `server/`.
  - (1) The documented command `./gradlew test --rerun --tests 'ch.sonensei.cockpit.mail.api.*' jacocoTestReport` produced the unit-only report.
  - (2) `./gradlew integrationTest --rerun --tests 'ch.sonensei.cockpit.mail.api.*'`.
  - (3) `./gradlew --init-script ../.tmp/coverage-init.gradle.kts taskCombinedCoverage` built a combined report over `build/jacoco/test.exec` and `build/jacoco/integrationTest.exec`. The init script is ad hoc and Git-ignored; no project file changed. The combined report is needed because `jacocoTestReport` reads only `test.exec`, and only the external GreenMail tests exercise `MailContentLoader`'s IMAP path.
- Run start/completion time and exit status: unit and report 02:26:29-02:27:32 +0200, exit 0. Integration 02:27:39-02:28:32, exit 0. Combined report 02:28:32-02:28:38, exit 0.
- Generated report paths and report hashes/run identifier:
  - `server/build/reports/jacoco/test/jacocoTestReport.xml`: sha256 4c3b3af63f5fec0308098abd4f3e9aa4dff0387e99d9f0b005b678d8aa8c7bce, from `test.exec` sha256 0c552289dd8e7916cb044db9b7443d0deacc4c965c986fd61f9e02c86d4e113d, written 02:27:26.
  - `server/build/reports/jacoco/taskCombined/report.xml`: sha256 700d9dd70fcc0b5bd3a2727b9ec621b75914ec698f9f78015a776652590e1d02, written 02:28:37, with `integrationTest.exec` sha256 4cf328d63c249b1024ee01256f6f3928bbbb1e67ad6d0638760a4146de9e0be3, written 02:28:30.
  - The later `scripts/check.sh` run overwrote `test.exec`; the hashes above are for the measured run.
- Evidence outputs are fresh and inputs stayed unchanged: yes. Both test tasks ran with `--rerun`, the exec and report timestamps fall within the runs, and the snapshot hashes were unchanged afterwards.
- Final run after latest test additions and production fixes: yes. These runs measure 575c63f2, which contains the CR-1 fix and its tests.

Values below use covered/total (percentage) and combine the unit and external GreenMail tests. "Statements" are JaCoCo instructions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailContentLoader.kt` | 348/390 (89.2%) | 22/28 (78.6%) | 16/17 (94.1%) | 57/63 (90.5%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailMessageController.kt` | 978/1016 (96.3%) | 18/22 (81.8%) | 84/85 (98.8%) | 186/186 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailMessageListQuery.kt` | 174/174 (100.0%) | 8/8 (100.0%) | 15/15 (100.0%) | 44/44 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailReadProperties.kt` | 122/122 (100.0%) | 16/16 (100.0%) | 10/10 (100.0%) | 18/18 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailReadService.kt` | 796/798 (99.7%) | 57/58 (98.3%) | 32/32 (100.0%) | 118/118 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MimeContent.kt` | 763/783 (97.4%) | 112/120 (93.3%) | 27/27 (100.0%) | 96/97 (99.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/RemoteImageFetcher.kt` | 2171/2242 (96.8%) | 278/315 (88.3%) | 65/66 (98.5%) | 262/271 (96.7%) |
| **Aggregate (7 files, summed counters; no file missing or unmeasured)** | 5352/5525 (96.9%) | 511/567 (90.1%) | 249/252 (98.8%) | 781/797 (98.0%) |

With unit tests only (`jacocoTestReport`, the documented command), the aggregate is 5163/5525 instructions (93.4%), 498/567 branches (87.8%), 244/252 methods (96.8%) and 754/797 lines (94.6%). The only difference is `MailContentLoader.kt`: 30/63 lines (47.6%) and 9/28 branches, because the GreenMail tests cover its IMAP path.

Compared with the previous (pre-fix) round, `RemoteImageFetcher.kt` grew by 4 lines, 42 branches and 141 instructions. These are the new range checks; the new tests cover them, and lines 255-256 (interrupted lookup) are now covered too. In the combined report, line coverage of that file rose from 254/267 to 262/271 and branch coverage from 243/273 to 278/315.

No task-level coverage threshold is defined (`docs/ARCHITECTURE.md`: coverage informs, it is not a gate).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailContentLoader.kt:114-115` | `MessagingException` (other than folder-not-found) when opening the folder -> `MailConnectionException` (502) | Justified gap: needs IMAP fault injection, which GreenMail does not offer. `MailMessageControllerTests` and the unreachable-server GreenMail test cover the 502 mapping itself |
| `MailContentLoader.kt:122-125`, branches 118, 127 | IMAP/IO failure while fetching inside the open folder -> 502, folder closed | Same: no deterministic fault injection. `MailAttachmentDownloadTests.a failure reading from the mail server still leaves the IMAP block as a failure` covers the equivalent attachment-stream failure |
| `MailContentLoader.kt:103-104` (branches) | message without uid/folder/account -> 404 before IMAP | Non-material: defensive, because the locator only returns located messages |
| `RemoteImageFetcher.kt:104-125` (partial branches of the compound range conditions) | SSRF policy for IPv6 special ranges | Not material: each range has positive, boundary and fetcher-level tests. The missed branches are short-circuit combinations of the byte comparisons (e.g. 2001:xxxx with a non-matching third byte for every alternative) |
| `RemoteImageFetcher.kt:260` | resolver throwing a RuntimeException is rethrown (would be a 500, not 502) | Not reachable with the JDK resolver for URI-validated hosts; noted, no test |
| `RemoteImageFetcher.kt:222` | redirect Location that the URI resolver rejects with IllegalArgumentException -> 502 | Low value: the URISyntaxException path and other invalid redirects are tested |
| `RemoteImageFetcher.kt:288-290`, branches 297, 308 | time budget expiring between socket reads in the response head (RemoteImageException path) | Timing-dependent; the head, body, TLS and DNS budget tests already cover the deadline behavior |
| `RemoteImageFetcher.kt:323-324` | single-byte `read()` on the deadline socket | In practice, BufferedInputStream and TLS do not use it |
| `RemoteImageFetcher.kt:356` | host name that is not a valid SNI name -> handshake without SNI | Edge case; the certificate host-name check is tested |
| `RemoteImageFetcher.kt:79` | non-IPv4/IPv6 InetAddress | Unreachable |
| `MailMessageController.kt:381, 404, 437, 438` (branches) | `checkNotNull` on folderId/createdAt/updatedAt | Unreachable for located, persisted messages |
| `MailReadService.kt:84` (branch) | a listed row whose message disappeared between the list query and the fetch | A race that cannot be tested deterministically without production hooks; the code drops the row |
| `MimeContent.kt:130` and branches 70, 71, 84, 106, 108 | null Content-Type / non-Multipart multipart content / non-MimePart parts | Defensive paths for malformed providers; `MimeContentTests` covers the malformed MIME cases that real messages produce |

## Unable to test

- IMAP failures in the middle of a fetch (MailContentLoader.kt:114-125): GreenMail cannot inject them. Follow-up only if the project adds a fault-injecting IMAP stub.
- OpenAPI regeneration (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`): not run by the test-writer, because it rewrites `api/openapi.yaml`, which is not a test file. The contract test without the flag passed in `scripts/check.sh`.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The CR-1/TW-1 fix is covered by positive, boundary and fetcher-level (literal and DNS) tests, and all of them pass. This round added no test files. Results:
- unit: 215/215 mail.api tests pass;
- external: 8/8 GreenMail tests pass;
- `sh scripts/check.sh`: passes;
- task-scoped combined coverage: 98.0% lines and 90.1% branches.
