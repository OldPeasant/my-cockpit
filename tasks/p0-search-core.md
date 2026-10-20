---
title: Global search infrastructure
status: in_progress
owner: dell26/agent-5
deps: [p0-items-links]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/search,server/src/test/kotlin/ch/sonensei/cockpit/core/search,server/src/main/resources/db/migration/core-search]
prio: 1
lease_until: 2026-10-20T13:27:54Z
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
