# Code Review

Date: 2026-10-20

Task: p0-search-core - Global search infrastructure

Scope: commits after base 2015c33 up to HEAD e6e4128 (f39d96c implementation, e6e4128 edge-case tests and test report). Committed changes only. The working tree was clean.

Requirements:
- tasks/p0-search-core.md acceptance criteria and recorded assumptions #1-#5
- SPEC.md §4.4 (global search), §4.2 conventions (Problem Details)
- AGENTS.md "Project notes" (module boundaries, migrations, logging, generated contract)
- docs/ARCHITECTURE.md conventions

Git range:
- Branch: main
- HEAD: e6e4128e64f95333a364efcc79cc6575187389a3
- Base/start: 2015c33ae3b3305d5c207711f0bbb817e3469a36
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 1 P2, 2 P3

Verified as correct:
- Migration `V202610201500__core_search_document.sql`: generated stored `tsv` over title (A) and body (B) in the `simple` and `english` configs, a GIN index on `tsv` and a `gin_trgm_ops` GIN index on `title`. `pg_trgm` is enabled by the earlier `V202610200000__core_enable_pg_trgm.sql`. The migration stays in `db/migration/core/` per assumption #1.
- tsquery injection: `SearchService` only passes `[\p{L}\p{N}]+` tokens into the query, quotes each one as `'word':*` and binds it as a JDBC parameter. Quotes, backslashes and operators cannot reach `to_tsquery`. Tests with tsquery syntax confirm this.
- `<%` with the query on the left matches `word_similarity(:text, title)` and can use the `gin_trgm_ops` index (the planner commutes it to `%>`). `set_config(..., true)` is allowed in the read-only transaction of `SearchService`, and the setting does not leak beyond it.
- Ranking: `ts_rank` multiplied by a recency boost. Fuzzy fill-up de-duplicates against full-text hits and stays within `limit`. `groupBy` keeps the order of first appearance, so groups are ordered by their best hit.
- Auth: `/api/v1/search` falls under the authenticated `/api/**` rule. A test checks that an anonymous request gets 401. Problem Details are returned for blank or too long `q`, an unknown type, a missing `q`, and a non-numeric or out-of-range `limit` (tests cover all of these).
- Logging: the only log statement (resolver failure, WARN) logs the item type and id, never titles or bodies.
- The tests cover everything named in criterion 4 (prefix match, typo tolerance, type filter, deleted and purged items), plus ranking, fill-up, resolver failure, truncation and schema checks.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | `limit` is published as `type: string` in the OpenAPI contract, and its default is lost**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/search/api/SearchController.kt:54` (result in `api/openapi.yaml:392-398`, `web/src/app/api/generated/fn/search/search.ts` `limit?: string`)
    - Category: Integration
    - Problem: The `@Parameter(schema = Schema(defaultValue = ..., minimum = ..., maximum = ...))` override has no `type`/`implementation`. As a result, springdoc emits `schema: {type: string, minimum: 1, maximum: 50}` for an `Int?` parameter and drops `default: 20`. The regenerated Angular client therefore types `limit` as `string`. Also, `minimum`/`maximum` on a string schema has no effect.
    - Why it matters: `api/openapi.yaml` is the source of truth for the web and Android clients (AGENTS.md "API changes"). A wrong type and a missing default in it lead to wrong client code (a string-typed `limit`) and an inaccurate API doc (criterion 5: "API documented").
    - Suggested fix: Give the schema its type, e.g. `Schema(type = "integer", format = "int32", defaultValue = "20", minimum = "1", maximum = "50")`, or `implementation = Int::class`. Then regenerate `api/openapi.yaml` and the web client (`npm run api:gen`). Optionally also document `maxLength: 200` on `q`.

## P3 Low

- [ ] **CR-2 | `similarTitles` only works inside a caller's transaction, and nothing enforces that**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/search/infra/SearchDocumentRepository.kt:73-85`
    - Category: Correctness
    - Problem: The method runs `set_config('pg_trgm.word_similarity_threshold', ..., true)` and the `<%` query as two separate statements. The local setting only reaches the second statement when both run in the same transaction on the same connection. Today the only caller is `@Transactional(readOnly = true)` `SearchService`, so it works. Any other caller without a transaction (a future reindex job, another service) would silently run the query with the default threshold of 0.6 instead of 0.4, and typo tolerance would quietly get worse.
    - Why it matters: Search quality would drop silently, with no error to point to the cause.
    - Suggested fix: Annotate the method (or the repository) with `@Transactional(propagation = Propagation.MANDATORY)`, or document the precondition in the KDoc so a missing transaction fails loudly.

- [ ] **CR-3 | Hits dropped by the resolver are not replaced, so results can fall short of `limit`**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/search/domain/SearchService.kt:70-80`
    - Category: Correctness
    - Problem: `limit` is applied in SQL (full-text plus fuzzy fill-up) before `resolve` filters out items whose resolver returns `null` (e.g. items in the Trash, assumption #4). If some of the top `limit` hits are stale, the response has fewer than `limit` items even when more matches exist. The fuzzy fill-up decision (`hits.size < limit`) is also made before this filtering.
    - Why it matters: This is minor while modules call `SearchIndexer.delete` on soft delete as documented. But a module that forgets to do so causes short or empty result pages, with no sign of what went wrong.
    - Suggested fix: Accept this and record it in the assumption. Alternatively, over-fetch (e.g. `limit * 2`, capped) and trim after resolving.

## Review conclusion

Material issues found; fix before completing the task. CR-1 (the OpenAPI type of `limit`) should be fixed and the contract and web client regenerated. CR-2 and CR-3 are low-priority hardening.
