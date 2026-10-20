---
title: Universal item references and links
status: in_progress
owner: dell26/agent-5
deps: [p0-auth-login]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/items,server/src/test/kotlin/ch/sonensei/cockpit/core/items,server/src/main/resources/db/migration/core-items]
prio: 1
lease_until: 2026-10-20T13:20:01Z
releases: 1
extended: 
---
## Goal
The cross-module link system (SPEC.md §4.3).

## Acceptance criteria
- [x] `ItemType` enum contains all types of SPEC.md §4.3 (TASK … HABIT) up front; `ItemResolver` interface (title, subtitle, icon, deepLink, exists) and a registry that finds resolvers by type
  - verified (2026-10-20 15:02+0200 by dell26/agent-5): Compared ItemType.kt with SPEC.md §4.3 -> all 11 types (TASK..HABIT) present; ItemResolver has type/resolve/exists, ResolvedItem has title/subtitle/icon/deepLink, ItemResolverRegistry.find(type); ItemResolverRegistryTests 5/5 pass
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
- ASSUMPTION (2026-10-20 14:10+0200 by dell26/agent-3): Replaces the 'pair linked at most once' assumption (fix TW-1, CR-3): MANUAL and SYNC links are independent. A pair has at most one MANUAL link (either direction, POST of a second -> 409) and at most one SYNC link per direction; GET /links shows each linked item once (manual link preferred, else oldest), so two notes stay linked while either still has the inline link.
- ASSUMPTION (2026-10-20 14:10+0200 by dell26/agent-3): CR-2: LinkResponse carries origin (MANUAL|SYNC); DELETE /api/v1/links/{id} of a SYNC (inline) link is rejected with 409 Problem Details, since the next sync of its source would recreate it; inline links are removed by editing the source.
- release by dell26/agent-3: Implementation complete (AC1-5 code + tests; CR-1/2/3 and TW-1/2 fixed). Check passes at eb593a6. Left: (1) rerun test-writer on final code: tests.md predates the last fixes and LinkWebTests got extra tests in eb593a6; (2) rerun code-reviewer on the final revision (review.md covers 48e57fa, pre-rebase; it has 1 open P3 about a stale detail in an ASSUMPTION note); (3) tasks.py verify for all 5 criteria (run core.items.* tests with Testcontainers + OpenApiContractTest without the flag); (4) write quality.json, commit; (5) done. origin/main is 3 commits ahead: merge it first and recheck migration versions in db/migration/core.
- ASSUMPTION (2026-10-20 15:01+0200 by dell26/agent-5): Replaces the migration file name in the 13:35 note (CR-1): the migration is db/migration/core/V202610201400__core_items_link.sql (renumbered from 201130 to avoid colliding with core_settings on main).
- ASSUMPTION (2026-10-20 15:01+0200 by dell26/agent-5): CR-2 (P3) accepted: CreateLinkRequest's 200-char label limit counts surrounding whitespace while LinkService trims before its own check, so a padded 200-char label gets 400; harmless edge case, left as is.
