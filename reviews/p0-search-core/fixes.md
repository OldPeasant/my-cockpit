# Fix Report

Date: 2026-10-20

Task: p0-search-core - Global search infrastructure

Scope: findings CR-1, CR-2, CR-3 from `reviews/p0-search-core/review.md` (base 2015c33..HEAD 8e0e4f1), all approved by the parent worker.

## Fixed findings

### CR-1 (P2) — `limit` published as `type: string`, default lost

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/search/api/SearchController.kt`
- `api/openapi.yaml` (regenerated)
- `web/src/app/api/generated/fn/search/search.ts` (regenerated)

Problem:
The `@Parameter(schema = Schema(...))` override of `limit` had no type, so springdoc emitted `type: string` with `minimum`/`maximum` and no `default`. The generated web client typed `limit` as `string`.

Fix:
The `limit` schema now has `type = "integer"`, `format = "int32"`, `defaultValue = "20"`, `minimum = "1"`, `maximum = "50"`. The `q` schema now has `type = "string"`, `minLength = 1`, `maxLength = 200` (`SearchService.MAX_QUERY_LENGTH`). The contract is regenerated: `limit` is `type: integer, format: int32, default: 20, minimum: 1, maximum: 50`, and `q` has `minLength: 1, maxLength: 200`. The regenerated web client has `limit?: number`. Runtime validation did not change.

Validation:
- `cd server && UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'` — Pass (contract regenerated)
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.search.*' --tests '*OpenApiContractTest'` (without the flag) — Pass
- `npm --prefix web run api:gen` — Pass. Only `fn/search/search.ts` changed.
- `npm --prefix web run check` (api:check, lint, e2e:typecheck, 321 unit tests, ng build) — Pass

### CR-3 (P3) — hits dropped by the resolver are not replaced, so results can fall short of `limit`

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/search/domain/SearchService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/search/SearchEdgeCaseTests.kt`

Problem:
`limit` was applied in SQL to the full-text and fuzzy queries before the resolver filtered out items (e.g. items in the Trash). The fuzzy fill-up decision was also made before that filtering. A page could therefore be shorter than `limit` even when more matches existed.

Fix:
Both queries now fetch `limit * CANDIDATE_FACTOR` (2) candidates. Full-text candidates are resolved lazily, in rank order, until `limit` results are kept. Only when fewer than `limit` resolved full-text results remain does the fuzzy fill-up run. It excludes every full-text candidate, resolves the rest, and takes the remaining places. Ranking order is unchanged: full-text first, then fuzzy, and groups are ordered by their best hit.

Regression tests added:
- `a hit its resolver leaves out is replaced by the next match`: 3 full-text matches, the best one trashed, `limit = 2` returns the other two. The old code returned 1.
- `fuzzy hits fill up places of full-text hits the resolver leaves out`: 2 full-text matches, one trashed, plus one typo match, `limit = 2` returns the exact match and the typo match. The old code skipped the fill-up and returned 1.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.search.*' --tests '*OpenApiContractTest'` — Pass (SearchEdgeCaseTests 30, SearchServiceTests 13, SearchWebTests 6, OpenApiContractTest 1, 0 failures)

### CR-2 (P3) — `similarTitles` relies on a caller's transaction without enforcing it

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/search/infra/SearchDocumentRepository.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/search/SearchEdgeCaseTests.kt`

Problem:
`set_config('pg_trgm.word_similarity_threshold', ..., true)` only reaches the following `<%` query when both run in the same transaction. A caller without a transaction would silently get the default threshold of 0.6.

Fix:
`similarTitles` is annotated `@Transactional(propagation = Propagation.MANDATORY)`, and its KDoc explains why. A caller without a transaction now gets an `IllegalTransactionStateException` instead of silently worse matching. The only production caller, `SearchService` (`@Transactional(readOnly = true)`), is unaffected.

Regression test added:
- `fuzzy title lookup outside a transaction fails instead of using the default threshold`

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.search.*' --tests '*OpenApiContractTest'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` — Pass
- `cd server && UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'` — Pass
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.search.*' --tests '*OpenApiContractTest'` — BUILD SUCCESSFUL, 50 tests, 0 failures

Full test suite:
- `sh scripts/check.sh` (server, web and android lint and unit tests) — Pass ("all checks passed")

Lint/type-check/build:
- `npm --prefix web run api:gen` — Pass
- `npm --prefix web run check` — Pass (lint clean, 321 tests passed, build complete)

`.taskcheck`:
- Pass (`sh scripts/check.sh`, exit 0)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
