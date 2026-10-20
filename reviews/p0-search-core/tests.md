# Test Report

Date: 2026-10-20

Task: p0-search-core - Global search infrastructure (re-run after fixes CR-1..CR-3)

Scope: `git diff b2995661dceb9c837abdbf543713ab82117f7f3f..0bfcfc454e4bff1aa0e93ce10509c46fe874888d` (task commits e6edf58, 2511cfd, 41a9bd1, 27018d5, 0bfcfc4), branch `main`, plus one uncommitted test-file change made by this run (see snapshot below). Base and commit list supplied by the parent worker.

Requirements:
- `tasks/p0-search-core.md` acceptance criteria and recorded assumptions (fill-up, ranking, resolver handling, limits)
- `reviews/p0-search-core/fixes.md` (CR-1 limit/q contract types, CR-2 MANDATORY transaction for `similarTitles`, CR-3 over-fetch candidates and fill-up)
- SPEC.md §4.4, `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/search/SearchEdgeCaseTests.kt`

## Tests added or updated

Count: 1 updated in this run (2 tests from the interrupted earlier re-run in 0bfcfc4 reviewed and kept)

- `a fuzzy hit its resolver leaves out is replaced by the next fuzzy match` (from 0bfcfc4) - strengthened. As committed, it did not prove the CR-3 fix: all three typo titles can tie on similarity, and ties rank newest first, so the trashed item (created first) could rank last and the old code (fetching only `limit` candidates) would have passed too. Now the two kept items are aged 10/20 days, and a precondition asserts via `word_similarity` that the trashed item is the best fuzzy candidate. With only `limit` candidates the page would then come back one item short.
- `the published contract types limit as an integer with its default and bounds and q with its length` (from 0bfcfc4) - checked and kept as is. It asserts the live `/v3/api-docs` `limit` schema (`integer`, default 20, min 1, max 50) and `q` schema (`string`, minLength 1, maxLength 200) against the `SearchService` constants. This covers CR-1 in addition to the `OpenApiContractTest` snapshot comparison.

The fixer's tests for the fixed behavior already exist and pass: `a hit its resolver leaves out is replaced by the next match` (CR-3 full-text over-fetch), `fuzzy hits fill up places of full-text hits the resolver leaves out` (CR-3 fill-up decision after resolving), `fuzzy title lookup outside a transaction fails instead of using the default threshold` (CR-2). No further tests were needed.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 `search_document` with tsvector (simple + english), GIN index, pg_trgm index on title | `the migration creates a GIN index on tsv and a trigram index on title`, `tsv holds both the exact and the English-stemmed words of title and body` | focused command below | Pass |
| AC2 `SearchIndexer.upsert/delete` API | `upsert replaces the indexed text`, `delete removes only the document of that type`, `deleting an item that was never indexed is harmless`, `titles are truncated, null bodies stored as empty, NUL dropped from titles`, `NUL characters are dropped and long bodies are truncated`, `ItemDeletedEvent published outside a transaction removes the document` | focused command below | Pass |
| AC3 ranking by ts_rank + recency | `a title match ranks before a body match`, `among equal matches the more recent ranks first`, `within a group items are best first`, `recency does not let an old title hit fall behind a fresh body hit`, `upsert refreshes recency` | focused command below | Pass |
| AC3 trigram fallback for short/fuzzy queries | `a typo in a title still finds the item`, `fuzzy hits fill up to the limit without duplicating full-text hits`, `a full-text hit ranks before a fuzzy title hit even when the fuzzy one is newer`, `fuzzy matching applies the type filter`, `fuzzy matching is on titles only, not on bodies` | focused command below | Pass |
| AC3 groups by type, types/limit parameters | `type filter returns only those types and results are grouped by type`, `groups are ordered by their best hit, not by a fixed type order`, `limit counts items across all groups`, `types filters, comma-separated or repeated`, `limit caps the number of items`, `invalid parameters are 400 problem details`, `limit boundaries 1 and 50 are accepted`, `requires login` | focused command below | Pass |
| CR-3 over-fetch: dropped hits replaced, fill-up after resolving | `a hit its resolver leaves out is replaced by the next match`, `fuzzy hits fill up places of full-text hits the resolver leaves out`, `a fuzzy hit its resolver leaves out is replaced by the next fuzzy match` | focused command below | Pass |
| CR-2 `similarTitles` requires a transaction | `fuzzy title lookup outside a transaction fails instead of using the default threshold` | focused command below | Pass |
| AC4 tests: prefix, typo, type filter, deleted items not found | `prefix of a title word finds the item`, `a typo in a title still finds the item`, `type filter returns only those types and results are grouped by type`, `deleted items are not found`, `an item its resolver no longer knows is left out` | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.search.*'` (part of the focused command, Postgres via Testcontainers) | Pass |
| AC5 / CR-1 API documented, contract matches; limit integer, q length | `OpenApiContractTest`, `the published contract types limit as an integer with its default and bounds and q with its length` | focused command below (without `UPDATE_OPENAPI`) | Pass |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.search.*' --tests '*OpenApiContractTest'`

Result:

Pass - BUILD SUCCESSFUL, 52 tests, 0 failures, 0 errors, 0 skipped (SearchEdgeCaseTests 32, SearchServiceTests 13, SearchWebTests 6, OpenApiContractTest 1). ktlint clean.

## Full test execution

Command:

`cd server && ./gradlew test jacocoTestReport`

Result:

Pass - BUILD SUCCESSFUL in 2m 12s, 83 test classes, 0 failures, 0 errors.

## .taskcheck

Result:

Pass - `sh scripts/check.sh` (server, web and android lint + unit tests, web build) ended with "all checks passed".

## Bugs found

None.

## Coverage

Status: verified (server). Generated web client files are excluded (see below).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. Every scoped server file was added by this task, so whole-file coverage equals coverage of the added lines.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: p0-search-core; base `b2995661dceb9c837abdbf543713ab82117f7f3f`; HEAD `0bfcfc454e4bff1aa0e93ce10509c46fe874888d`; commits e6edf58, 2511cfd, 41a9bd1, 27018d5, 0bfcfc4 (supplied by the parent, confirmed with `git log` and `git diff --name-only` of that range).
- Source/test snapshot identifier (including uncommitted inputs): HEAD 0bfcfc4 + uncommitted `SearchEdgeCaseTests.kt` sha256 `87271a5670acea3dd550adde00f0896428dc561b7ddfa0167879e2177061ba2b` (same hash before and after the run). Production sha256: SearchController.kt `c42ad3e7...`, SearchIndexer.kt `21003ae1...`, SearchService.kt `74133cfe...`, SearchDocumentRepository.kt `ee2005fb...`.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/core/search/api/SearchController.kt`, `.../domain/SearchIndexer.kt`, `.../domain/SearchService.kt`, `.../infra/SearchDocumentRepository.kt`. Excluded: `server/src/main/resources/db/migration/core/V202610201500__core_search_document.sql` (SQL, not instrumented by JaCoCo; exercised by the schema tests above); `web/src/app/api/generated/**` (7 generated files, regenerated from the contract and checked by `api:check` in `.taskcheck`). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle `jacoco` plugin, Gradle 9.8.1); `./gradlew test jacocoTestReport` in `server/`; full server test suite.
- Run start/completion time and exit status: 2026-10-20 15:39:39 to 15:41:54 +02:00; BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` (mtime 15:41:54.647 +02:00, sha256 `559188aef365f8e3621f593d411ce5bb7d653c58b46ec296f5161d064af51c2d`).
- Evidence outputs are fresh and inputs stayed unchanged: yes. Gradle reran `test` because the test source changed, the report mtime falls inside the run, and the test-file hash matched before and after.
- Final run after latest test additions and production fixes: yes (it includes the fixes in 27018d5 and this run's test change).

Values below use covered/total (percentage) from JaCoCo XML counters; Kotlin "statements" = JaCoCo INSTRUCTION.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/.../core/search/api/SearchController.kt` | 229/254 (90.2%) | 26/26 (100%) | 17/18 (94.4%) | 45/46 (97.8%) |
| `server/.../core/search/domain/SearchIndexer.kt` | 56/56 (100%) | 2/2 (100%) | 5/5 (100%) | 10/10 (100%) |
| `server/.../core/search/domain/SearchService.kt` | 328/328 (100%) | 12/12 (100%) | 17/17 (100%) | 52/52 (100%) |
| `server/.../core/search/infra/SearchDocumentRepository.kt` | 196/198 (99.0%) | N/A (0/0) | 8/9 (88.9%) | 27/28 (96.4%) |
| Aggregate (summed counters, 4 files) | 809/836 (96.8%) | 40/40 (100%) | 47/49 (95.9%) | 134/136 (98.5%) |

Aggregate excludes the SQL migration and the generated web files listed above. No scoped file is missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `SearchController.kt:98-112` (`SearchItemResponse` synthetic default-argument constructor) | None. The controller always passes all arguments; the defaults exist only for the schema | Justified gap, non-material |
| `SearchDocumentRepository.kt:96` (private companion object constructor) | None, Kotlin synthetic code | Justified gap, non-material |

All behavior that CR-1..CR-3 changed (`SearchService.search` over-fetch and fill-up, `similarTitles` MANDATORY propagation, controller parameter schema) is fully covered.

## Unable to test

- The limit of the fix itself: when more than `limit * 2` candidates in a row are left out by their resolver, the page can still be short. This is how the fix is designed (CANDIDATE_FACTOR 2), not a requirement, so no test was added.
- Nothing else.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. One test from the interrupted re-run was strengthened so that it actually discriminates the CR-3 fix. All validation passed.
