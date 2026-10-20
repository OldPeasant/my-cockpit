# Test Report

Date: 2026-10-20

Task: p0-search-core - Global search infrastructure

Scope: `git diff 2015c33ae3b3305d5c207711f0bbb817e3469a36..f39d96cbd3ec0e990a4ed4810d7d03d412a2cfb1` (one task commit f39d96c on `main`, supplied by the parent worker), plus the new untracked test file `server/src/test/kotlin/ch/sonensei/cockpit/core/search/SearchEdgeCaseTests.kt` written by this test-writer. No other uncommitted changes.

Requirements:
- `tasks/p0-search-core.md` acceptance criteria and its recorded assumptions (#1-#5)
- `SPEC.md` §4.4 Global search
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/search/SearchEdgeCaseTests.kt` (new; same Spring context configuration as `SearchWebTests`, so the context is shared)

Existing `SearchServiceTests.kt` and `SearchWebTests.kt` were not modified.

## Tests added or updated

Count: 27 (new, in `SearchEdgeCaseTests`)

## Requirement coverage

All commands below were run from `server/`. "search pkg" = `./gradlew test --tests 'ch.sonensei.cockpit.core.search.*'` (46 tests: 27 new + 13 `SearchServiceTests` + 6 `SearchWebTests`), Postgres 16 via Testcontainers.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| `search_document` table with tsvector (simple + english), GIN index, pg_trgm index on title | `the migration creates a GIN index on tsv and a trigram index on title`, `tsv holds both the exact and the English-stemmed words of title and body` | search pkg | Pass |
| `SearchIndexer.upsert/delete(itemType, id, title, body)` | `upsert replaces the indexed text`, `upsert refreshes recency`, `delete removes only the document of that type`, `deleting an item that was never indexed is harmless`, `titles are truncated, null bodies stored as empty, NUL dropped from titles`, `NUL characters are dropped and long bodies are truncated` | search pkg | Pass |
| Ranking by `ts_rank` + recency | `a title match ranks before a body match`, `among equal matches the more recent ranks first`, `within a group items are best first`, `recency does not let an old title hit fall behind a fresh body hit`, `upsert refreshes recency` | search pkg | Pass |
| Trigram fallback for short/fuzzy queries | `a typo in a title still finds the item`, `a full-text hit ranks before a fuzzy title hit even when the fuzzy one is newer`, `fuzzy hits fill up to the limit without duplicating full-text hits`, `fuzzy matching applies the type filter`, `fuzzy matching is on titles only, not on bodies` | search pkg | Pass |
| Grouped by type (groups ordered by best hit, assumption #3) | `type filter returns only those types and results are grouped by type`, `groups are ordered by their best hit, not by a fixed type order`, `returns hits grouped by type with resolved display data` | search pkg | Pass |
| `GET /api/v1/search?q=&types=&limit=` parameters | `types filters, comma-separated or repeated`, `an empty types parameter means all types`, `an empty type set searches all types`, `limit caps the number of items`, `limit counts items across all groups`, `limit boundaries 1 and 50 are accepted`, `q is trimmed before validation and search`, `invalid parameters are 400 problem details`, `a non-numeric limit is a 400 problem detail`, `requires login` | search pkg | Pass |
| Test: prefix match | `prefix of a title word finds the item`, `queries are case-insensitive`, `non-ASCII letters are words`, `every query word must match` | search pkg | Pass |
| Test: typo tolerance on titles | `a typo in a title still finds the item` (+ fuzzy tests above) | search pkg | Pass |
| Test: type filter | `type filter returns only those types...`, `types filters, comma-separated or repeated`, `fuzzy matching applies the type filter` | search pkg | Pass |
| Test: deleted items not found | `deleted items are not found`, `ItemDeletedEvent published outside a transaction removes the document`, `an item its resolver no longer knows is left out`, `a fuzzy hit whose resolver no longer knows the item is left out` | search pkg | Pass |
| Resolver handling (assumption #4): failing resolver does not break search | `a failing resolver does not break the search, the hit keeps its indexed title`, `a failing resolver gives the indexed title without display data over HTTP`, `items of types without a resolver are found with the indexed title` | search pkg | Pass |
| Query safety: tsquery syntax, stop words, no words | `queries with tsquery syntax or without words are safe`, `an English stop word alone or with other words does not fail`, `a query without letters or digits finds nothing over HTTP` | search pkg | Pass |
| API documented: `api/openapi.yaml` regenerated, `OpenApiContractTest` passes without the flag | `OpenApiContractTest` (existing) | `./gradlew cleanTest test jacocoTestReport` (full suite, no `UPDATE_OPENAPI`) | Pass (1/1) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.search.*'` and `cd server && ./gradlew ktlintCheck`

Result:

Pass (46 tests, 0 failures, 0 skipped; ktlintCheck BUILD SUCCESSFUL)

## Full test execution

Command:

`cd server && ./gradlew cleanTest test jacocoTestReport`

Result:

Pass (76 suites, 916 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESSFUL in 2m 2s)

## .taskcheck

Result:

Pass (`sh scripts/check.sh`, 2026-10-20T15:22:03+02:00 to 15:22:42+02:00, exit 0, "all checks passed")

## Bugs found

None. No test exposed behavior that contradicts the acceptance criteria, SPEC.md §4.4 or the recorded assumptions.

Observation (not a defect, no fix requested): because the resolver filter runs after `limit` is applied, hits whose resolver returns `null` (an item in the Trash that its module did not de-index) reduce the result count below `limit` even when further matches exist. This is consistent with assumption #4 (modules call `SearchIndexer.delete` on soft delete); the parent may decide whether it needs follow-up.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to whole-file coverage here, because every in-scope production file was added by the task (all executable lines are changed lines); not measured separately with a diff tool.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p0-search-core`; base `2015c33ae3b3305d5c207711f0bbb817e3469a36` and task commit `f39d96cbd3ec0e990a4ed4810d7d03d412a2cfb1` (= measured HEAD), both supplied by the parent worker; file list from `git diff --name-only 2015c33a..HEAD -- server/src/main web/src android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `f39d96cbd3ec0e990a4ed4810d7d03d412a2cfb1` + untracked `SearchEdgeCaseTests.kt` sha256 `7d2a626d6a44c0640a7c48d7454e9181d91cf087b6a77bb7e708466b49b3229c` (identical before and after the run; `SearchServiceTests.kt` sha256 `ce590acc...aecaa`, `SearchWebTests.kt` sha256 `47d21bd4...7f9d1`, unchanged from HEAD).
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/core/search/api/SearchController.kt`, `.../domain/SearchIndexer.kt`, `.../domain/SearchService.kt`, `.../infra/SearchDocumentRepository.kt`. Excluded: `server/src/main/resources/db/migration/core/V202610201500__core_search_document.sql` (SQL, not instrumentable by JaCoCo; verified behaviorally by the schema tests), `api/openapi.yaml` and `web/src/app/api/generated/**` (generated files). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo 0.8.15 (Gradle `jacoco` plugin, Gradle 9.8.1, JVM 21.0.12.1); `./gradlew cleanTest test jacocoTestReport` in `server/`; full server test suite.
- Run start/completion time and exit status: 2026-10-20T15:19:27+02:00 to 15:21:33+02:00; BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 `1f3c337cc047e90c65cebb708c1b2c800593ab8fca6eee5857aaffbdb21a533c`; `server/build/jacoco/test.exec` (written 15:21).
- Evidence outputs are fresh and inputs stayed unchanged: yes (`cleanTest` forced the test task to rerun; report and exec timestamps are from this run; snapshot hashes rechecked after the run).
- Final run after latest test additions and production fixes: yes (no production fixes were made; run includes all 27 new tests).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. "Statements" = JaCoCo INSTRUCTION counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/search/api/SearchController.kt` | 229/254 (90.2%) | 26/26 (100%) | 17/18 (94.4%) | 45/46 (97.8%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/search/domain/SearchIndexer.kt` | 56/56 (100%) | 2/2 (100%) | 5/5 (100%) | 10/10 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/search/domain/SearchService.kt` | 312/312 (100%) | 12/12 (100%) | 16/16 (100%) | 44/44 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/search/infra/SearchDocumentRepository.kt` | 196/198 (99.0%) | N/A (0 branches) | 8/9 (88.9%) | 27/28 (96.4%) |
| Aggregate (summed counters, 4 Kotlin files; migration SQL and generated files excluded as above) | 793/820 (96.7%) | 40/40 (100%) | 46/48 (95.8%) | 126/128 (98.4%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `SearchController.kt:86,93,95,97,100` (missed instructions, the 1 missed method) | Kotlin-generated synthetic default-arguments constructor of `SearchItemResponse` (`subtitle`/`icon`/`deepLink` default `null`); production always passes all arguments | Justified gap: compiler glue, no behavior; the null-field responses are covered via `a failing resolver gives the indexed title without display data over HTTP` |
| `SearchDocumentRepository.kt:87` (1 missed method/line) | Constructor of the `private companion object` holding the `HIT` row mapper | Justified gap: compiler glue, the row mapper itself is exercised by every search test |

## Unable to test

- Performance of the GIN / trigram indexes on large data (whether the planner actually uses them for `@@` and `<%`): not a task criterion; only index presence and definition are tested. No follow-up required for this task.
- Real module resolvers and modules calling `SearchIndexer` from their transactions: no module integrates yet; tested with `FakeItemResolvers` and direct indexer calls. Follow-up belongs to the module tasks.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
