---
title: Trash: listing, restore, purge
status: in_progress
owner: R1963MACOS/agent-2
deps: [p0-items-links]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/trash,server/src/test/kotlin/ch/sonensei/cockpit/core/trash]
prio: 2
lease_until: 2026-10-20T13:28:01Z
---
## Goal
Generic trash for soft-deleted items (SPEC.md §4.2).

## Acceptance criteria
- [ ] `TrashContributor` interface per module (list deleted, restore, purge); `GET /api/v1/trash`, `POST /api/v1/trash/{type}/{id}/restore`, `DELETE /api/v1/trash/{type}/{id}`
- [ ] Scheduled job purges items deleted > 30 days ago (test with the test clock)
- [ ] Tests with a fake contributor; `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.trash.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
