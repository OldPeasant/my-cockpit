# Test Report

Date: 2026-10-21

Task: bookmarks-api - Bookmarks API (SPEC.md §8.4)

Scope: `git diff a1266a77abadc786016ab1e7ee9b2bb7ba7e18e8..cc9aa3a49249570de047a87f56c8f4822d2f5397` on branch `main`. Base `a1266a77` is `git merge-base HEAD origin/main`, as the parent worker gave it. The task-owned commits, also from the parent, are 64f41ef7, b7de8214, 0174d377, 123322de, ae88d53d and cc9aa3a4 (HEAD). In scope: the production code under `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks`, the migration under `db/migration/bookmarks`, `api/openapi.yaml`, and the tests under `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks`. Uncommitted inputs are the test changes listed below and nothing else. The working tree was clean when this session started.

This session continues an earlier test-writer session. That session added BookmarkEdgeCaseTests, BookmarkMetadataWorkerTests, BookmarkMigrationTests, PageFetcherEdgeCaseTests and BookmarkUrlsTests, and its TW-1 is already fixed (see fixes.md). It stopped before it wrote this report. This session reviewed those tests against the criteria, then added tests for the gaps that coverage and the review showed.

Requirements:
- tasks/bookmarks-api.md: acceptance criteria 1-4 and the recorded assumptions (favicon URL, fill-empty and refresh, metadataStatus, SSRF/1MB/15s, URL normalization, PENDING re-queue)
- SPEC.md §8.4 (bookmarks / read-later), §4.2 (ETag/If-Match, Problem Details), §4.3 (resolver)
- docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md (report contract)
- reviews/bookmarks-api/review.md (round 2, open P3 findings CR-5..CR-7) and fixes.md

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/PageFetcherSizeLimitTests.kt` (new, untracked; sha256 496c57340cae7d68e21ac030cf4797101108119797cb4232f27dafa18d1a6b17)
- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/BookmarkMetadataWorkerTests.kt` (+1 test; sha256 fe2470da19d74458f3c1e9f55fc77fd58ff307c607d06a68e5085eceb6ef8d52)
- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/BookmarkUrlsTests.kt` (+1 test; sha256 4c5bd7299f5014d4945eb668ab434b4580af4ca92cfef15ba9f5a7a4031e6de1)
- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/BookmarkEdgeCaseTests.kt` (+1 test; sha256 df3552ac400da7525d22cd300a6ec2fe3235efdd4fc62988f0aab20c32990326)

No existing test was changed, weakened, skipped or deleted. No production code was changed.

## Tests added or updated

Count: 8 (added)

- `PageFetcherSizeLimitTests`. Its WireMock server has gzip turned off. Every existing size-limit test ran against a WireMock server that gzips its answers by itself, because PageFetcher sends `Accept-Encoding: gzip`. So no existing test ever reached the truncation of an uncompressed body (`TruncatedBody` lines 308-311 were uncovered).
  - `an uncompressed page larger than max-size is cut off at exactly max-size bytes`: 5 MB body, 4 KB limit. The fetch returns exactly 4096 bytes and returns quickly.
  - `a page of exactly max-size bytes is returned whole, one byte less too`: the boundary cases.
  - `a gzip page whose compressed body is cut off at max-size gives what could be decompressed`: the compressed body is cut off at the limit, and the gunzip `EOFException` path with partial output is used (line 264).
  - `a connection closed in the middle of the body fails as unreachable`: a raw socket sends the headers and half the body, then closes. Expected: `UNREACHABLE` (the `TruncatedBody.onError` path).
  - `pages larger than max-size are cut off reliably, also over a reused HTTP 2 connection`: regression test for TW-2. It fails on the current code.
- `BookmarkMetadataWorkerTests.an unexpected error while fetching or parsing is stored as failed and the worker goes on`: covers the `RuntimeException` branch of `BookmarkMetadataWorker.run` (lines 78-80, uncovered before).
- `BookmarkUrlsTests.percent escapes are kept in either case, a percent without two hex digits is encoded`: lowercase and mixed-case `%xx` escapes are kept. `%zz`, `%4g`, a trailing `%4` and a trailing `%` become `%25`.
- `BookmarkEdgeCaseTests.the resolver shows a read bookmark of the read-later list as a plain bookmark and hides a trashed one`: covers the resolver subtitle once the bookmark is read, and the resolver returning `null` for a trashed bookmark.

Review of the earlier session's tests: they cover criterion 1 well (fill-empty/overwrite, NUL characters and limits, surrogate-safe cut, stale generations, restore re-fetch, startup re-queue, queue-full and shutdown, SSRF on redirects, credentials, `_` hosts, encodings, config validation) and criterion 2 (resolver, search document, trash restore/purge guards). They are deterministic: WireMock, mocked service, latches and no real sleeps for ordering. Only the polling helper `settled` sleeps, and it has a 10 s deadline. I found no redundant or implementation-coupled tests worth changing.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1a CRUD, tags, read_later/read_at | BookmarkWebTests: create, PATCH, 404, read-later queue/filters, tags, cursor paging; BookmarkEdgeCaseTests: tag/field limits, blank fields, invalid list params, stale If-Match | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.*'` | Pass |
| 1b Async OpenGraph fetch of title/description/favicon | BookmarkWebTests: create then fetch, user title kept, URL change, refresh, stale fetch ignored; BookmarkEdgeCaseTests: fill-empty, overwrite, cleaning, surrogate cut, stale generation, restore re-fetch, re-queue; BookmarkMetadataWorkerTests (13 incl. the new one); PageMetadataParserTests | same | Pass |
| 1c SSRF protection | PageFetcherTests: private/loopback/link-local hosts, ports, schemes, credentials, redirect to non-http; PageFetcherEdgeCaseTests: empty resolution, redirect with credentials, `_` host; BookmarkMetadataWorkerTests: blocked address, no request sent | same | Pass |
| 1d Timeouts | PageFetcherTests: slow DNS and slow server give TIMEOUT within the limit | same | Pass |
| 1e Size limits | PageFetcherTests: large page, gzip; PageFetcherSizeLimitTests: uncompressed cut-off, exact boundary, cut gzip, broken body, repeated cut-off over HTTP/2 | same | **Fail**: `pages larger than max-size are cut off reliably, also over a reused HTTP 2 connection` fails (TW-2). The other 4 tests pass. |
| 2 Resolver BOOKMARK, search, trash | BookmarkWebTests: resolve, searchable by title/URL/description/tags, Trash/restore/purge, change events; BookmarkEdgeCaseTests: resolver read/trashed (new), Trash guards, restore re-fetch | same | Pass |
| 3 Bookmarks test suite passes (Testcontainers Postgres, WireMock pages) | all 9 bookmark test classes, 99 tests | same | **Fail**: 98 passed, 1 failed (TW-2) |
| 4 API documented, OpenApiContractTest passes without the flag | `OpenApiContractTest` | `cd server && ./gradlew ktlintCheck test --tests '*OpenApiContractTest'` | Pass (BUILD SUCCESSFUL, 21:34) |
| Migration convention (AGENTS.md) | BookmarkMigrationTests (2) | bookmarks suite | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.PageFetcherSizeLimitTests' --tests 'ch.sonensei.cockpit.bookmarks.BookmarkMetadataWorkerTests' --tests 'ch.sonensei.cockpit.bookmarks.BookmarkUrlsTests'`, followed by 3 runs of `./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.PageFetcherSizeLimitTests' --rerun` once the repeated test was added

Result:

Fail. The new worker, URL and resolver tests and 4 of the 5 size-limit tests pass. The repeated HTTP/2 cut-off test failed in 3 of 3 runs, with 2 to 3 of 50 fetches failing as `UNREACHABLE (Stream N cancelled)`.

## Full test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.*'` (the command criterion 3 names; Testcontainers Postgres and WireMock; 2026-10-21 21:43)

Result:

Fail: 99 tests, 1 failed (`PageFetcherSizeLimitTests > pages larger than max-size are cut off reliably, also over a reused HTTP 2 connection`, TW-2). Without that class, the other 94 tests pass (the coverage run below, BUILD SUCCESSFUL).

`cd server && ./gradlew ktlintCheck`: Pass, run on the final test files.

## .taskcheck

Result:

Fail. `sh scripts/check.sh` ran from 21:35 to 21:42, on the test files before the repeated test was added. Server: 3709 tests, 3 failed:
- `PageFetcherSizeLimitTests` "an uncompressed page larger than max-size ..." and "a gzip page whose compressed body is cut off ...". Both are `UNREACHABLE` caused by `IOException: Stream 1/7 cancelled`. This is TW-2: under the load of the full suite, even single cut-off fetches lose the race.
- `calendar.infra.ics.IcsFeedClientTests` "a feed of exactly the maximum size is read, a larger Content-Length is refused before reading": IOException instead of "larger than". This test is outside this task. The task notes already list it as the only failure of an earlier check, and it is likely flaky.

I did not run the check again after adding the repeated test, because it would fail on TW-2 as expected. Run `.taskcheck` again once TW-2 is fixed.

## Bugs found

### TW-2: A page larger than max-size intermittently fails as UNREACHABLE over HTTP/2, so its metadata is FAILED instead of parsed from the first max-size bytes

Finding ID:

`TW-2`

Test:

`PageFetcherSizeLimitTests.pages larger than max-size are cut off reliably, also over a reused HTTP 2 connection`. The single-fetch tests `an uncompressed page larger than max-size is cut off at exactly max-size bytes` and `a gzip page whose compressed body is cut off at max-size gives what could be decompressed` show the same failure under load (`.taskcheck` run).

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/PageFetcher.kt:308-311` (`TruncatedBody.onNext`: `result.complete(...)`, then `subscription.cancel()`), together with `send` at `:194-199`, which maps the failure to `UNREACHABLE`.

Requirement:

Criterion 1 ("size limits") and assumption #4 ("Only the first 1MB of HTML is read"). The KDoc of PageFetcher says: "Only the first maxSize bytes of a page are read ... the rest is not downloaded". A page larger than the limit must give its first max-size bytes, which the parser then reads.

Expected behavior:

Every fetch of an HTML page larger than `maxSize` returns a `FetchedPage` with exactly `maxSize` bytes, whatever HTTP version the client negotiated.

Actual behavior:

The JDK HttpClient uses HTTP/2: h2c with WireMock, and ALPN h2 with most HTTPS sites. On such a connection, cancelling the subscription inside `onNext` races with the client failing the whole exchange: `java.io.IOException: Stream N cancelled`, thrown from `TruncatedBody.onNext(PageFetcher.kt:311)`. When the exchange fails first, `future.get` throws `ExecutionException`, and `send` reports `PageFetchError.UNREACHABLE`. The comment "the first completion wins" holds for the body future, not for the response future of `sendAsync`. Measured: 2 or 3 of 50 sequential fetches over one client failed in each of 3 isolated runs. Under the full suite, both single-shot tests failed. In production, one PageFetcher (one HttpClient) serves all fetches, and the default limit is 1MB. So bookmarks of large pages randomly end up `metadataStatus = FAILED` with no title, description or favicon. The gzip path is affected the same way, because the compressed body is cut off by the same subscriber.

Severity:

Medium

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

Do not weaken, delete or skip the failing test.

### Open reviewer findings (not new, for context)

review.md CR-5, CR-6 and CR-7 (P3, optional) are still unfixed. I added no tests for them. CR-6 is the current, intended behavior locked in by `BookmarkMetadataWorkerTests.after shutdown requests are stored as failed ...`, so a CR-6 fix must update that test's expectation.

## Coverage

Status: verified for the passing selection (94 tests, every bookmark test class except `PageFetcherSizeLimitTests`). For the full selection the coverage is partial-failed: the command that includes `PageFetcherSizeLimitTests` fails on TW-2, so `jacocoTestReport` (which depends on `test`) does not run. The coverage that only `PageFetcherSizeLimitTests` adds was measured in an earlier run in this session, in which its first 3 tests passed. It is listed below as diagnostic only.

Metric: whole-file coverage of task-changed production files. Changed-line coverage: equal to whole-file line coverage. Every file in scope was added by this task (`git diff a1266a77..HEAD` has no deleted or modified pre-existing lines in them), so every executable line is a changed line. Measured as the JaCoCo LINE counter of these files.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: bookmarks-api, base a1266a77abadc786016ab1e7ee9b2bb7ba7e18e8, HEAD cc9aa3a49249570de047a87f56c8f4822d2f5397. The commits and paths come from the parent worker and match the task's `touches` field and `git diff --name-only a1266a77..HEAD -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD cc9aa3a4 plus the 4 uncommitted test files with the sha256 hashes listed under "Test files changed". The combined sha256 of all `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/*.kt` is 2ba9255b75cf55450082a5dade2beca9e1b47dafbee4dafc34232519b9bb885e, the same before and after the run. Production code is unchanged and the tree was clean at HEAD.
- Exact included files; renamed/deleted/excluded files and reasons: the 14 Kotlin files under `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/{api,domain,infra}` (table below). `infra/BookmarkRepository.kt` is a Spring Data interface with no executable bytecode: it is in the report, but JaCoCo has no counters for it, so it is N/A and not counted in the aggregate. `db/migration/bookmarks/V202610211800__bookmarks_model.sql` is excluded because it is SQL, not instrumentable; Testcontainers runs it in every Spring test, and BookmarkMigrationTests checks its naming. `api/openapi.yaml` and `web/src/app/api/generated/**` are excluded because they are generated; OpenApiContractTest checks them. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (configured in `server/build.gradle.kts`), Gradle 9.8.1. Working directory `server/`. Command: `./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.BookmarkEdgeCaseTests' --tests '...BookmarkMetadataWorkerTests' --tests '...BookmarkMigrationTests' --tests '...BookmarkUrlsTests' --tests '...BookmarkWebTests' --tests '...PageFetcherEdgeCaseTests' --tests '...PageFetcherTests' --tests '...PageMetadataParserTests' jacocoTestReport`. Only the bookmarks tests ran, so hits from other modules' tests (for example backup or search suites) are not included.
- Run start/completion time and exit status: 2026-10-21T21:43:39+02:00 to 21:44:24+02:00, BUILD SUCCESSFUL (exit 0). 94 tests, 0 failures, 0 errors, 0 skipped.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 93b255bd29a4dda0fd121d722bd4b10a1f1cb41cb866a1c0a30c56ad82d45963 (HTML in `server/build/reports/jacoco/test/html/`). The diagnostic run at 21:33-21:34, with `PageFetcherSizeLimitTests` passing and 97 tests in total, has report sha256 de4e330b6e8198d5cfb90ed23aa49d79aca5dde8cc68e3dfb41285f7429c80ed.
- Evidence outputs are fresh and inputs stayed unchanged: yes. Gradle reran `test` because the filter changed, and the test-result timestamps are 19:43-19:44Z. The report was regenerated after the run, and the test file hashes were the same before and after.
- Final run after latest test additions and production fixes: yes for the test additions. No production fix has been made for TW-2 yet, so a fresh run is needed once it is fixed.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. JaCoCo has no statement counter, so the "Statements" column shows the bytecode INSTRUCTION counter.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/api/BookmarkController.kt` | 202/205 (98.5%) | 4/6 (66.7%) | 12/12 (100%) | 25/25 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/api/BookmarkDtos.kt` | 401/434 (92.4%) | 12/14 (85.7%) | 36/41 (87.8%) | 71/71 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/api/BookmarksWebSupport.kt` | 293/295 (99.3%) | 25/26 (96.2%) | 3/3 (100%) | 21/21 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/domain/Bookmark.kt` | 229/259 (88.4%) | 21/28 (75.0%) | 25/27 (92.6%) | 27/27 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/domain/BookmarkCommands.kt` | 192/213 (90.1%) | N/A (0/0) | 27/28 (96.4%) | 27/28 (96.4%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/domain/BookmarkService.kt` | 1535/1546 (99.3%) | 166/172 (96.5%) | 33/33 (100%) | 165/165 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/domain/BookmarkUrls.kt` | 587/593 (99.0%) | 83/86 (96.5%) | 6/6 (100%) | 62/63 (98.4%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/BookmarkFetchProperties.kt` | 147/147 (100%) | 22/22 (100%) | 9/9 (100%) | 18/18 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/BookmarkItemResolver.kt` | 45/45 (100%) | 6/6 (100%) | 3/3 (100%) | 12/12 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/BookmarkMetadataWorker.kt` | 232/232 (100%) | 10/10 (100%) | 9/9 (100%) | 53/53 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/BookmarkRepository.kt` | N/A (interface, no bytecode counters) | N/A | N/A | N/A |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/BookmarkTrashContributor.kt` | 143/152 (94.1%) | 19/20 (95.0%) | 5/5 (100%) | 28/28 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/PageFetcher.kt` | 1037/1140 (91.0%) | 77/89 (86.5%) | 33/38 (86.8%) | 159/177 (89.8%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/PageMetadataParser.kt` | 396/437 (90.6%) | 39/52 (75.0%) | 5/5 (100%) | 31/31 (100%) |
| **Aggregate (13 measured files; summed counters; BookmarkRepository.kt N/A, migration/generated files excluded)** | **5439/5698 (95.5%)** | **484/531 (91.1%)** | **206/219 (94.1%)** | **699/719 (97.2%)** |

Diagnostic (run at 21:33, which included PageFetcherSizeLimitTests while its first 3 tests passed; not the final selection): PageFetcher.kt 1058/1140 instructions, 81/89 branches, 34/38 methods, 161/177 lines. The aggregate was 5458/5698 instructions, 487/531 branches, 207/219 methods and 701/719 lines. The size-limit tests cover `TruncatedBody` lines 310-311 and 316-317, gunzip line 264 with partial output, and line 232 (`identity`).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `PageFetcher.kt:308-311, 316-317, 264` (cut-off of large pages, body error, cut gzip) | Criterion 1 size limits | Added `PageFetcherSizeLimitTests` (5 tests). They cover these lines when they pass. The cut-off path has bug TW-2, so the class is not part of the verified run. |
| `PageFetcher.kt:155-158, 190-193` (InterruptedException in DNS wait and response wait) | Shutdown interrupts a running fetch | Left uncovered: the worker-level behavior (an interrupted fetch stores nothing) is tested with a mocked fetcher in `BookmarkMetadataWorkerTests`. Interrupting the JDK client from outside at the right moment would make a timing-dependent test. |
| `PageFetcher.kt:187-189` / `197` (TIMEOUT through `future.get` timeout vs `HttpTimeoutException`) | Criterion 1 timeouts | Covered by `a server that does not answer in time fails with a timeout`. Which of the two catch blocks runs depends on timing, so one of them shows as uncovered in some runs. The result (TIMEOUT within the limit) is asserted in both cases. |
| `PageFetcher.kt:125, 182-183` (IllegalArgumentException from `URI.resolve` / `HttpRequest.newBuilder`) | Defensive mapping to HTTP_ERROR / INVALID_URL | Not material: `check()` and `BookmarkUrls` reject such URIs earlier. The `_`-host test reaches INVALID_URL through another path. |
| `PageFetcher.kt:205` (deadline already passed before a request) | Timeout across redirects | Not material: it can only be forced with timing. The overall deadline is tested through slow DNS and a slow server. |
| `PageFetcher.kt:280`, `139` | Static thread-pool initializer and closing brace (JaCoCo line attribution) | Not behavior. |
| `PageMetadataParser.kt:19, 38, 61-67` (partial branches) | Fallback order of og/twitter/meta, icon rel variants | The main orders are tested in PageMetadataParserTests (7 tests). The remaining combinations are null/blank variants of the same fallbacks. Low risk; no test added. |
| `Bookmark.kt:75` (`check` in `metadataFetched`), `89-90` (displayTitle when the URL has no host / an empty IDN host) | Defensive invariant; title fallback | Not reachable through the service: `applyMetadata` filters by generation first, and normalized URLs always have a host. |
| `BookmarkDtos.kt:50-51`, `BookmarkCommands.kt:31`, `BookmarkController.kt:65`, `BookmarksWebSupport.kt:33` | Kotlin data-class generated methods and `?.takeIf` null branches | Not material (generated code, or blank-tag filter variants covered by `invalid list parameters give 400` and the filter tests). |
| `BookmarkService.kt:178, 246, 250, 295, 378` (partial branches) | Fill/overwrite combinations, tag normalization, cursor parse, cut at `maxLength 0` | The main combinations are covered by the fill/overwrite, tag and cursor tests. The missing branches are degenerate inputs (for example `maxLength = 0`). |
| `BookmarkUrls.kt:108`, `63` (URI parse failure after encoding) | Last-resort refusal | Not reachable with the current encoder: everything it produces parses. It is a defensive guard. |
| `BookmarkTrashContributor.kt:34` (`checkNotNull(deletedAt)`) | Invariant of trashed rows | Not reachable: `findAllDeleted` only returns rows with `deleted_at`. |

## Unable to test

- Fetching real HTTPS sites (ALPN h2, real TLS) is not tested. The tests use WireMock over plain HTTP: h2c reproduces the HTTP/2 path of TW-2, and HTTP/1.1 is used where the server does not upgrade. No follow-up is needed beyond TW-2.
- DNS rebinding between the address check and the connect is not tested. It is a documented limitation (assumption #4: narrowed, not closed) and is not a requirement to test.
- The CR-5 startup window (a bookmark created before `ApplicationReadyEvent` and fetched twice) is not tested. It is a P3 finding left open in review.md, and the parent decides on it.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion. TW-2 (Medium): a page larger than max-size intermittently fails as UNREACHABLE over HTTP/2, so its bookmark metadata ends FAILED. This also makes criterion 3 (`./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.*'` passes) fail at present. Every other criterion has passing tests. Task-scoped coverage, without the failing class, is 97.2% of lines (699/719) and 91.1% of branches (484/531).
