---
title: Global search infrastructure
status: in_progress
owner: dell26/agent-5
deps: [p0-items-links]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/search,server/src/test/kotlin/ch/sonensei/cockpit/core/search,server/src/main/resources/db/migration/core-search]
prio: 1
lease_until: 2026-10-20T13:30:05Z
---
## Goal
One search across all modules (SPEC.md §4.4).

## Acceptance criteria
- [ ] `search_document` table with `tsvector` (simple + english configs) + GIN index and `pg_trgm` index on title
- [ ] `SearchIndexer.upsert/delete(itemType, id, title, body)` API for modules
- [ ] `GET /api/v1/search?q=&types=&limit=` ranks by `ts_rank` + recency, falls back to trigram similarity for short/fuzzy queries, groups by type
- [ ] Tests: prefix match, typo tolerance on titles, type filter, deleted items not found; `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.search.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-20 15:14+0200 by dell26/agent-5): Migration lives in db/migration/core/ (V202610201500__core_search_document.sql), not the core-search folder named in touches: MigrationConventionTests requires folder == module prefix [a-z0-9]+, so 'core-search' is impossible
- ASSUMPTION (2026-10-20 15:14+0200 by dell26/agent-5): Trigram fallback = fill-up: full-text (every query word as prefix, simple OR english config) runs first; when it returns fewer than limit items, titles with word_similarity >= 0.4 fill the rest, ranked after. Short queries are served by the prefix tsquery. Transposed letters early in a word (pulmber) can fall below 0.4
- ASSUMPTION (2026-10-20 15:14+0200 by dell26/agent-5): Ranking: ts_rank (title weight A, body weight B) x recency boost 1 + 1/(1 + ageDays/30); recency = time of the last SearchIndexer.upsert. limit default 20, max 50, counts items across groups; groups ordered by best hit
