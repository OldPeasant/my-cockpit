---
title: Trash: listing, restore, purge
status: in_progress
owner: R1963MACOS/agent-2
deps: [p0-items-links]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/trash,server/src/test/kotlin/ch/sonensei/cockpit/core/trash]
prio: 2
lease_until: 2026-10-20T13:46:45Z
---
## Goal
Generic trash for soft-deleted items (SPEC.md §4.2).

## Acceptance criteria
- [x] `TrashContributor` interface per module (list deleted, restore, purge); `GET /api/v1/trash`, `POST /api/v1/trash/{type}/{id}/restore`, `DELETE /api/v1/trash/{type}/{id}`
  - verified (2026-10-20 15:30+0200 by R1963MACOS/agent-2): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.trash.*' (Testcontainers postgres:16-alpine, Docker Desktop) -> TrashWebTests: GET /api/v1/trash lists/pages, POST /{type}/{id}/restore 204/404, DELETE /{type}/{id} 204/404, 400 bad type/id; TrashContributor interface (listDeleted/restore/purge) exercised via fake contributors; 51 tests incl. contract, 0 failures
- [x] Scheduled job purges items deleted > 30 days ago (test with the test clock)
  - verified (2026-10-20 15:30+0200 by R1963MACOS/agent-2): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.trash.*' -> TrashServiceTests/TrashUnitTests with MutableClock test clock: item deleted exactly 30 days ago kept, purged 1 us/1 ns later, links removed, failing item retried next run; job registered as cron '0 30 3 * * *' (ScheduledTaskHolder) -> pass
- [x] Tests with a fake contributor; `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.trash.*'` passes (Postgres via Testcontainers where persistence is involved)
  - verified (2026-10-20 15:30+0200 by R1963MACOS/agent-2): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.trash.*' --tests '*OpenApiContractTest' --rerun-tasks against Testcontainers Postgres -> 51 tests, 0 failures (fake contributors, link rows + transaction isolation tests on Postgres)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-20 15:13+0200 by R1963MACOS/agent-2): GET /api/v1/trash merges all contributors in memory (sorted deletedAt desc, then ItemType order, then id desc) with a keyset cursor; fine for a single user's 30-day trash, contributors return all their deleted items
- ASSUMPTION (2026-10-20 15:13+0200 by R1963MACOS/agent-2): Purge job runs daily at 03:30 UTC (property cockpit.trash.purge-cron); purges items with deletedAt strictly older than 30 days; each item in its own transaction, failures retried next run. @EnableScheduling lives in core.trash (TrashSchedulingConfiguration)
- ASSUMPTION (2026-10-20 15:13+0200 by R1963MACOS/agent-2): TrashService (not the contributors) publishes ItemDeletedEvent on purge (removes links) and ChangeEvents: restore -> UPDATED, purge -> DELETED; restore/purge of an item not in the trash or of a type without contributor -> 404
- ASSUMPTION (2026-10-20 15:13+0200 by R1963MACOS/agent-2): Response adds purgeAt (deletedAt + 30 days) so the web trash can show 'purged in N days'; path {type} is the ItemType name in upper case as in /api/v1/links
