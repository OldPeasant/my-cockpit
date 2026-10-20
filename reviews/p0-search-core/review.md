# Code Review

Date: 2026-10-20

Task: p0-search-core - Global search infrastructure (re-review of the final code after fixes CR-1..CR-3)

Scope: `git diff b2995661dceb9c837abdbf543713ab82117f7f3f..beea32c13daa5e0d02339493342a7eb30caabb05`, reviewed revision beea32c13daa5e0d02339493342a7eb30caabb05. Committed changes only. The full range was first reviewed at 0bfcfc4. The later delta `git diff 0bfcfc4..beea32c -- server` was then reviewed on its own: it changes only one test in `SearchEdgeCaseTests.kt`, plus `reviews/p0-search-core/tests.md`. `git diff --quiet 0bfcfc4 beea32c -- server/src/main api web` confirms that no production code, contract or generated client changed. Tests were run on exports of the committed revisions, not on the working tree.

Requirements:
- tasks/p0-search-core.md: acceptance criteria 1-5 and recorded assumptions #1-#5
- SPEC.md §4.4 (global search) and §4.2 (Problem Details)
- AGENTS.md "Project notes" (module boundaries, migrations, logging, generated contract and client)
- docs/ARCHITECTURE.md conventions
- Previous review reviews/p0-search-core/review.md (CR-1..CR-3) and reviews/p0-search-core/fixes.md

Git range:
- Branch: main
- HEAD: beea32c13daa5e0d02339493342a7eb30caabb05
- Base/start: b2995661dceb9c837abdbf543713ab82117f7f3f (merge-base with origin/main)
- Commits reviewed: 6 (e6edf58, 2511cfd, 41a9bd1, 27018d5, 0bfcfc4, beea32c)

Commands run:
- `git archive 0bfcfc4 server api | tar -x -C .tmp/review-0bfcfc4` (isolated copy of the committed code)
- `.tmp/review-0bfcfc4/server/gradlew -p .tmp/review-0bfcfc4/server --no-daemon -q ktlintCheck test --tests 'ch.sonensei.cockpit.core.search.*' --tests '*OpenApiContractTest'`, run with Docker/Testcontainers Postgres. Result: success. SearchEdgeCaseTests 32, SearchServiceTests 13, SearchWebTests 6 and OpenApiContractTest 1, with 0 failures, 0 errors and 0 skipped. ktlint was clean. `OpenApiContractTest` passing without `UPDATE_OPENAPI` shows that `api/openapi.yaml` matches the running server.
- `git archive beea32c server api | tar -x -C .tmp/review-beea32c`, then the same Gradle command with `-p .tmp/review-beea32c/server`. Result: success. SearchEdgeCaseTests 32, SearchServiceTests 13, SearchWebTests 6 and OpenApiContractTest 1, with 0 failures, 0 errors and 0 skipped. ktlint was clean.

Delta 0bfcfc4..beea32c: the test `a fuzzy hit its resolver leaves out is replaced by the next fuzzy match` now ages the two typo items by 10 and 20 days. It also asserts, as a precondition, that the trashed item's `word_similarity` is at least that of each of the others. Ties are broken by `updated_at desc`, and the trashed item is the newest, so it is the best fuzzy candidate. This makes the test a real regression test for CR-3: a lookup of only `limit` candidates would have returned a short page. The test is sound, and I found nothing to report in it.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Verification of the earlier findings:
- CR-1 (fixed): `limit` now has `type = "integer", format = "int32"`, a default of 20 and the bounds 1..50. `q` has `minLength = 1` and `maxLength = 200`. The schema and the bounds come from the `SearchService` constants. `api/openapi.yaml` shows `type: integer, format: int32, default: 20, minimum: 1, maximum: 50`, and the generated `web/src/app/api/generated/fn/search/search.ts` has `limit?: number`. A test checks the published contract through `/v3/api-docs`.
- CR-2 (fixed): `SearchDocumentRepository.similarTitles` is `@Transactional(propagation = Propagation.MANDATORY)`, and its KDoc explains why. A test checks that a call without a transaction throws `IllegalTransactionStateException`. The test class has no transaction of its own, so the test is meaningful. The only caller, `SearchService`, is `@Transactional(readOnly = true)`.
- CR-3 (fixed): Both queries fetch `limit * 2` candidates. Full-text hits are resolved in rank order until `limit` results are kept. The fuzzy fill-up runs only when fewer than `limit` results resolved. It leaves out every full-text candidate, so there are no duplicates and a trashed full-text item cannot come back as a fuzzy hit. Ranking order (full-text, then fuzzy) is unchanged. Regression tests cover replacement within full-text, fill-up after resolver drops, and replacement within fuzzy hits. One limit remains by design: if more than half of the `2 * limit` full-text candidates are dropped by their resolvers, matches beyond that window are not looked at. This only happens when a module fails to call `SearchIndexer.delete` (assumption #4). It is not a finding.

Re-checked acceptance criteria:
1. Migration `core/V202610201500__core_search_document.sql`: a generated stored `tsv` over title (A) and body (B) in the `simple` and `english` configs, a GIN index on `tsv`, and a `gin_trgm_ops` GIN index on `title`. A test checks the schema. The folder deviates from `touches`, which is recorded in assumption #1.
2. `SearchIndexer.upsert/delete(itemType, id, title, body)` exists and is transactional. It strips NUL and truncates the text. `ItemDeletedEvent` also deletes the document.
3. `GET /api/v1/search?q=&types=&limit=`: ranks by `ts_rank` × recency boost, fills up with trigram `word_similarity` (≥ 0.4) for fuzzy queries, and groups by type, with groups ordered by their best hit. The tsquery is built only from `[\p{L}\p{N}]+` tokens, quoted, and bound as a parameter, so there is no injection. Problem Details are returned for invalid input. Requests require authentication (a 401 test exists).
4. Tests for prefix match, typo tolerance, type filter, and deleted and purged items all exist. The named test command passes (see above).
5. The contract is regenerated and `OpenApiContractTest` passes without the flag. The generated web client is consistent with it.

Security and conventions: no secrets in the diff. The only log statement (WARN when a resolver fails) logs the type and id only, never titles or bodies. The module uses only `core.items` domain types and its own infra, and the packages follow `api/domain/infra`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
