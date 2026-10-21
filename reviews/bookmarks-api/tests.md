# Test Report

Date: 2026-10-21

Task: bookmarks-api - Bookmarks API (SPEC.md §8.4)

Scope: base `2780ebe8f6e8fccfece1c3cf9f3582fd51ed5f55`, task commits `5f84adf2..41cda1b2` (given by the parent worker), merged into `main` at HEAD `bad14d5afd5c9801146ae09c3e19262225acfd48` via merge commit `bad14d5a`. This round focuses on the code changed since the previous test report: `2f581586` (CR-5..CR-7 fixes: first fetch result wins, `BookmarkUrls.readable` in the search document, shutdown leaves bookmarks PENDING; bodies of non-HTML/non-2xx answers not downloaded) and `41cda1b2` (size-limit test adjustments). Task production files are the 14 Kotlin files under `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks` plus the bookmarks migration and `api/openapi.yaml`. Calendar files in `git diff 2780ebe8..HEAD` come from other tasks (cal-invitations) merged meanwhile and are excluded. Uncommitted input: one test file (below); the tree was otherwise clean.

Requirements:
- tasks/bookmarks-api.md: acceptance criteria 1-4 and recorded assumptions
- SPEC.md §8.4, §4.2, §4.3
- docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md
- reviews/bookmarks-api/review.md (CR-5..CR-7) and fixes.md (round 2)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/BookmarkUrlsTests.kt` (+1 test, uncommitted; sha256 0a6e0a46549fa54b67595fb07c44107abc4a4f6655614df2f031817e9726dcfe)

No existing test was changed, weakened, skipped or deleted. No production code was changed.

## Tests added or updated

Count: 1 (added)

- `BookmarkUrlsTests.the readable form decodes lowercase escapes and keeps incomplete ones and a port as they are`: lowercase `%c3%a9` decodes, a trailing incomplete `%4` and a lone `%` stay as they are while the rest is decoded, and a punycode host with a port and no path reads in Unicode with the port kept.

Review of the tests that came with 2f581586/41cda1b2 (committed by the fixer): they cover the changed behavior.
- First result wins (CR-5): `BookmarkEdgeCaseTests.only the first result of a fetch is stored, a second one of the same fetch is ignored`. It checks the status, title, version and that no change event is published. The existing applyMetadata tests now reset the status to PENDING (`pendingAgain`) so they still exercise the fill/overwrite logic. This is a correct adjustment to the new contract, not a weakening.
- Readable URL in search (CR-7): three `BookmarkUrlsTests` (Unicode host, userinfo, port, IPv6, invalid UTF-8, control characters, `%25`, no-op and non-URL) plus `BookmarkEdgeCaseTests.a URL with a Unicode host and non-ASCII path is found by its words as typed`, which goes end to end through `/api/v1/search`.
- Shutdown leaves PENDING (CR-6): `BookmarkMetadataWorkerTests.after shutdown requests store nothing ...`. The queue-full store failure moved to a real full queue (`a rejected request that cannot be stored does not fail the caller`).
- Non-HTML bodies are not downloaded: `PageFetcherSizeLimitTests` covers a large slow `video/mp4` body (NOT_HTML fast), a 500 HTML page (HTTP_ERROR fast), a 302 with a slow body (followed fast) and the HTTP/1.1 cut-off closing the connection.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1a CRUD, tags, read_later/read_at | BookmarkWebTests, BookmarkEdgeCaseTests (limits, blank fields, list params, If-Match, 51st tag on PATCH) | `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.bookmarks.*' --tests '*OpenApiContractTest' jacocoTestReport` | Pass |
| 1b Async OpenGraph fetch | BookmarkWebTests, BookmarkEdgeCaseTests (fill/overwrite, first result wins, stale fetch, restore re-fetch, re-queue), BookmarkMetadataWorkerTests, PageMetadataParserTests | same | Pass |
| 1c SSRF protection | PageFetcherTests, PageFetcherEdgeCaseTests, BookmarkMetadataWorkerTests | same | Pass |
| 1d Timeouts | PageFetcherTests (slow DNS, slow server); PageFetcherSizeLimitTests (slow non-HTML/error/redirect bodies return well before the timeout) | same | Pass |
| 1e Size limits | PageFetcherTests, PageFetcherSizeLimitTests (exact boundary, uncompressed and gzip cut-off, broken body, repeated cut-off over HTTP/2 (TW-2 regression), HTTP/1.1 connection closed, non-HTML not downloaded) | same | Pass |
| 2 Resolver BOOKMARK, search, trash | BookmarkWebTests, BookmarkEdgeCaseTests (resolver read/trashed, readable-URL search, Trash guards), BookmarkUrlsTests (readable form) | same | Pass |
| 3 Bookmarks suite passes (Testcontainers Postgres, WireMock) | all bookmark test classes | same | Pass (bookmark classes plus OpenApiContractTest: 110 tests, 0 failures) |
| 4 OpenApiContractTest passes without the flag | `OpenApiContractTest` | same (no `UPDATE_OPENAPI`) | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.BookmarkUrlsTests'`

Result:

Pass (BUILD SUCCESSFUL)

## Full test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.bookmarks.*' --tests '*OpenApiContractTest' jacocoTestReport` (2026-10-21 22:43-22:44 +02:00)

Result:

Pass: BUILD SUCCESSFUL, 110 tests (11 result files), 0 failures or errors; ktlintCheck passed. The full server suite was not run in this round.

## .taskcheck

Result:

Not run in this round. `sh scripts/check.sh` takes about 7 minutes, and the parent runs it before `done`. The previous round's check failures were TW-2 (now fixed, and its regression test passes) and the unrelated flaky `calendar.infra.ics.IcsFeedClientTests`.

## Bugs found

None in this round. TW-2 from the previous round is fixed (d82afa2f). Its regression test `PageFetcherSizeLimitTests.pages larger than max-size are cut off reliably, also over a reused HTTP 2 connection` passes at HEAD.

## Coverage

Status: verified (fresh run, exit 0, final revision).

Metric: whole-file coverage of task-changed production files. Changed-line coverage: equal to whole-file line coverage. Every file in scope was added by this task (`git diff 2780ebe8..HEAD`), so every executable line is a changed line.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: bookmarks-api; base 2780ebe8f6e8fccfece1c3cf9f3582fd51ed5f55; HEAD bad14d5afd5c9801146ae09c3e19262225acfd48; task commits 5f84adf2..41cda1b2 from the parent, which match the task's `touches` paths and `git diff --name-only 2780ebe8 HEAD -- src/main` restricted to `bookmarks`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD bad14d5a plus uncommitted `BookmarkUrlsTests.kt` (sha256 0a6e0a46...dcfe, the same before and after the run). The combined sha256 of `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/*.kt` is 8f37050ad4493b2e2f5496c27ab04c3067dd48f694ff08429c2889588bfbb2a9.
- Exact included files; renamed/deleted/excluded files and reasons: the 13 measured files in the table. `infra/BookmarkRepository.kt` is a Spring Data interface with no counters (N/A, not in the aggregate). The migration SQL is not instrumentable; Testcontainers applies it and BookmarkMigrationTests checks it. `api/openapi.yaml` and the web client are generated; OpenApiContractTest checks them. The report also lists `bookmarks/api/Comparisons.kt` with no counters. It has no source file at HEAD (a stale class in `build/`), so it is not a task file and is excluded. No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin, Gradle 9.8.1. Working directory `server/`. Command as under "Full test execution". The selection is the bookmark tests plus OpenApiContractTest only.
- Run start/completion time and exit status: 2026-10-21T22:43:20+02:00 to 22:44:05+02:00, exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 3c83af0d6a0d89baf4ef2c8d5a0762acf0c6bf082377a757f036fdf76e2827d8 (written 22:44:05).
- Evidence outputs are fresh and inputs stayed unchanged: yes. Test inputs changed, so Gradle reran `test`, and the report timestamp falls inside the run. The test file hash was the same before and after.
- Final run after latest test additions and production fixes: yes.

Values below use covered/total (percentage). JaCoCo has no statement counter, so "Statements" shows INSTRUCTION.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/api/BookmarkController.kt` | 202/205 (98.5%) | 4/6 (66.7%) | 12/12 (100%) | 25/25 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/api/BookmarkDtos.kt` | 401/434 (92.4%) | 12/14 (85.7%) | 36/41 (87.8%) | 71/71 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/api/BookmarksWebSupport.kt` | 293/295 (99.3%) | 25/26 (96.2%) | 3/3 (100%) | 21/21 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/domain/Bookmark.kt` | 233/263 (88.6%) | 22/30 (73.3%) | 25/27 (92.6%) | 27/27 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/domain/BookmarkCommands.kt` | 192/213 (90.1%) | N/A (0/0) | 27/28 (96.4%) | 27/28 (96.4%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/domain/BookmarkService.kt` | 1546/1557 (99.3%) | 168/174 (96.6%) | 33/33 (100%) | 168/168 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/domain/BookmarkUrls.kt` | 891/906 (98.3%) | 112/118 (94.9%) | 9/9 (100%) | 98/99 (99.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/BookmarkFetchProperties.kt` | 147/147 (100%) | 22/22 (100%) | 9/9 (100%) | 18/18 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/BookmarkItemResolver.kt` | 45/45 (100%) | 6/6 (100%) | 3/3 (100%) | 12/12 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/BookmarkMetadataWorker.kt` | 243/243 (100%) | 12/12 (100%) | 9/9 (100%) | 56/56 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/BookmarkRepository.kt` | N/A (interface) | N/A | N/A | N/A |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/BookmarkTrashContributor.kt` | 143/152 (94.1%) | 19/20 (95.0%) | 5/5 (100%) | 28/28 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/PageFetcher.kt` | 1110/1188 (93.4%) | 83/93 (89.2%) | 36/40 (90.0%) | 168/181 (92.8%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/PageMetadataParser.kt` | 396/437 (90.6%) | 39/52 (75.0%) | 5/5 (100%) | 31/31 (100%) |
| **Aggregate (13 measured files, summed counters; BookmarkRepository.kt N/A; migration and generated files excluded)** | **5842/6085 (96.0%)** | **524/573 (91.4%)** | **212/224 (94.6%)** | **750/765 (98.0%)** |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `BookmarkUrls.kt:84` (IDN.toUnicode failure fallback), `122` (hex-digit partial combinations), `66`/`166` (URI parse failure after encoding) | Readable search form; last-resort refusal | Added the lowercase/incomplete-escape test. The remaining branches are defensive: `toUnicode` with ALLOW_UNASSIGNED does not throw for a host that `normalize` stored, and everything the encoder produces parses. |
| `PageFetcher.kt:156-159, 192-195` (InterruptedException in the DNS and response waits) | A shutdown interrupts a running fetch | Left uncovered: only timing could force it. The worker-level outcome (store nothing) is tested with a mocked fetcher. |
| `PageFetcher.kt:184-185, 126` (IllegalArgumentException from building the request or resolving a redirect) | Defensive mapping | Not material: `BookmarkUrls` and `check()` reject such URIs earlier. |
| `PageFetcher.kt:197-199, 211` (timeout via `future.get` vs `HttpTimeoutException`; deadline already passed) | Timeouts | The result (TIMEOUT within the limit) is asserted. Which catch block runs depends on timing. |
| `PageFetcher.kt:223` (partial: the 2xx/HTML combinations) | Bodies of non-HTML or non-2xx answers are not downloaded | The 200 non-HTML, 500 HTML and 302 cases are tested. The missing combination (non-2xx and non-HTML) takes the same branch outcome. |
| `PageFetcher.kt:140, 290` | Closing brace or static initializer attribution | Not behavior. |
| `Bookmark.kt:75` (`check` in `metadataFetched` when not PENDING or a different fetch), `89-90` | Invariant; title fallback without a host | Not reachable through the service: `applyMetadata` filters on fetch number and PENDING first, and normalized URLs always have a host. |
| `PageMetadataParser.kt:19, 38, 61-67` | og/twitter/meta and icon fallback variants | The main orders are tested. The rest are null/blank variants; low risk. |
| `BookmarkService.kt:183, 251, 255, 300, 383`; `BookmarkDtos.kt:50-51`; `BookmarkCommands.kt:31`; `BookmarkController.kt:65`; `BookmarksWebSupport.kt:33`; `BookmarkTrashContributor.kt:34` | Degenerate inputs, data-class generated methods, invariants | Not material, as in the previous round. |

## Unable to test

- Real HTTPS sites (TLS with ALPN h2) are not tested. WireMock over plain HTTP covers HTTP/2 (h2c) and HTTP/1.1. No follow-up needed.
- DNS rebinding between the address check and the connect is a documented limitation (assumption #4), not a tested requirement.
- The CR-5 startup double fetch is not reproduced end to end. Its effect (the first result wins) is tested at the service level.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The changes in 2f581586/41cda1b2 come with tests, and this round added one more `BookmarkUrls.readable` edge-case test. 110 bookmark and OpenAPI contract tests pass. Task-scoped coverage is 98.0% of lines (750/765) and 91.4% of branches (524/573). `.taskcheck` was not run in this round.
