---
title: API conventions: errors, ETags, pagination, OpenAPI contract file
status: in_progress
owner: dell26/agent-2
deps: [p0-server-deps]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/web,server/src/test/kotlin/ch/sonensei/cockpit/core/web,api]
prio: 1
lease_until: 2026-10-20T09:11:40Z
---
## Goal
Common REST conventions (SPEC.md §4.2) and the committed OpenAPI contract that both clients use.

## Acceptance criteria
- [ ] Errors are RFC 9457 Problem Details (validation → 400 with field errors, not found → 404, conflict → 409, precondition failed → 412); no stack traces or internal messages leak (test)
- [ ] ETag/If-Match helper: GET returns `ETag` from `version`, PATCH/DELETE without `If-Match` → 428, with a stale one → 412 (tests on a test controller)
- [ ] Cursor pagination DTO (`items`, `nextCursor`) + `?cursor=&limit=` parsing with limit bounds (tests)
- [ ] `OpenApiContractTest` fetches `/v3/api-docs.yaml`; with `UPDATE_OPENAPI=1` it writes `api/openapi.yaml`, otherwise fails if the file differs; `api/openapi.yaml` committed; `api/README.md` explains regenerating it and that conflicts in it are resolved by regenerating, never by hand
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.web.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
