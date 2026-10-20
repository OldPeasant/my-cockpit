---
title: Universal item references and links
status: in_progress
owner: dell26/agent-3
deps: [p0-auth-login]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/items,server/src/test/kotlin/ch/sonensei/cockpit/core/items,server/src/main/resources/db/migration/core-items]
prio: 1
lease_until: 2026-10-20T12:05:15Z
---
## Goal
The cross-module link system (SPEC.md §4.3).

## Acceptance criteria
- [ ] `ItemType` enum contains all types of SPEC.md §4.3 (TASK … HABIT) up front; `ItemResolver` interface (title, subtitle, icon, deepLink, exists) and a registry that finds resolvers by type
- [ ] `link` table + `POST /api/v1/links`, `DELETE /api/v1/links/{id}`, `GET /api/v1/links?type=&id=` returning links in both directions, each end resolved via its resolver (missing resolver/item → marked `unavailable`, not an error)
- [ ] `LinkService.sync(source, targets)` replaces the links a source owns (used for inline note links later); deleting an item removes its links via an event
- [ ] Tests with a fake resolver; `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.items.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-20 13:35+0200 by dell26/agent-3): Migration lives in db/migration/core/ (V202610201130__core_items_link.sql), not the core-items folder named in touches: MigrationConventionTests requires a [a-z0-9]+ module folder matching the file prefix.
- ASSUMPTION (2026-10-20 13:35+0200 by dell26/agent-3): link has an extra origin column (MANUAL|SYNC) plus BaseEntity's updated_at/version: LinkService.sync replaces only SYNC links of the source, so manual links from a note survive re-syncing its inline links.
- ASSUMPTION (2026-10-20 13:35+0200 by dell26/agent-3): A pair of items is linked at most once in either direction (unique index on least/greatest of both ends): POST of an existing pair -> 409, sync skips it. Self-links -> 400.
- ASSUMPTION (2026-10-20 13:35+0200 by dell26/agent-3): POST rejects an end (400) only when its type has a resolver that says it does not exist; types without a resolver yet are accepted and listed as unavailable. A resolver that throws is logged at WARN and its end marked unavailable.
- ASSUMPTION (2026-10-20 13:35+0200 by dell26/agent-3): ItemDeletedEvent is published on permanent deletion (Trash purge / hard delete), not on soft delete, so links survive restore; soft-deleted items resolve to unavailable. Listener is a plain @EventListener in the publisher's transaction.
- ASSUMPTION (2026-10-20 13:35+0200 by dell26/agent-3): DELETE /api/v1/links/{id} needs no If-Match (links are immutable); GET /api/v1/links returns {items:[...]} unpaginated (few links per item), with direction OUTGOING/INCOMING relative to the queried item.
