---
title: Bookmarks API
status: in_progress
owner: dell26/agent-3
deps: [p0-items-links,p0-search-core,p0-trash-core]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/bookmarks,server/src/test/kotlin/ch/sonensei/cockpit/bookmarks,server/src/main/resources/db/migration/bookmarks]
prio: 3
lease_until: 2026-10-21T18:47:50Z
---
## Goal
Bookmarks and read-later (SPEC.md §8.4).

## Acceptance criteria
- [ ] CRUD, tags, read_later/read_at; on save fetch title/description/favicon (OpenGraph) asynchronously with SSRF protection, timeouts and size limits
- [ ] Resolver BOOKMARK, search, trash
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.*'` passes (Postgres via Testcontainers where persistence is involved) with WireMock pages
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
