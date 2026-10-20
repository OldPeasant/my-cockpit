---
title: API conventions: errors, ETags, pagination, OpenAPI contract file
status: in_progress
owner: dell26/agent-2
deps: [p0-server-deps]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/web,server/src/test/kotlin/ch/sonensei/cockpit/core/web,api]
prio: 1
lease_until: 2026-10-20T09:27:29Z
---
## Goal
Common REST conventions (SPEC.md §4.2) and the committed OpenAPI contract that both clients use.

## Acceptance criteria
- [x] Errors are RFC 9457 Problem Details (validation → 400 with field errors, not found → 404, conflict → 409, precondition failed → 412); no stack traces or internal messages leak (test)
  - verified (2026-10-20 11:09+0200 by dell26/agent-2): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.web.*' (Postgres 16 Testcontainer) -> ApiConventionsWebTests$Errors: 400 w/ errors[] (body/param/code/type-mismatch/missing param+header), 404 (entity + unknown route), 409 (conflict, optimistic lock), 412, generic 500 w/o message/stack trace; security 401/403 preserved; all pass
- [x] ETag/If-Match helper: GET returns `ETag` from `version`, PATCH/DELETE without `If-Match` → 428, with a stale one → 412 (tests on a test controller)
  - verified (2026-10-20 11:10+0200 by dell26/agent-2): same run -> ApiConventionsWebTests$ETagsAndIfMatch on test controller: GET ETag "1", PATCH/DELETE without If-Match 428, stale 412, current 200/204; ETagsTests (weak/list/*) -> pass
- [x] Cursor pagination DTO (`items`, `nextCursor`) + `?cursor=&limit=` parsing with limit bounds (tests)
  - verified (2026-10-20 11:10+0200 by dell26/agent-2): same run -> CursorRequestTests (default 50, bounds 1..200 -> 400 'limit', malformed cursor -> 400, round trip, page/nextCursor) + ApiConventionsWebTests$Pagination (pages 7 items with limit=3, null nextCursor on last page) -> pass
- [x] `OpenApiContractTest` fetches `/v3/api-docs.yaml`; with `UPDATE_OPENAPI=1` it writes `api/openapi.yaml`, otherwise fails if the file differs; `api/openapi.yaml` committed; `api/README.md` explains regenerating it and that conflicts in it are resolved by regenerating, never by hand
  - verified (2026-10-20 11:10+0200 by dell26/agent-2): ./gradlew updateOpenApi (= UPDATE_OPENAPI=1 OpenApiContractTest) -> wrote api/openapi.yaml, regenerating leaves git clean; OpenApiContractTest without flag passes and failed when a line was added to api/openapi.yaml (fixer run); api/openapi.yaml + api/README.md (regenerate, conflicts resolved by regenerating) committed
- [x] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.web.*'` passes (Postgres via Testcontainers where persistence is involved)
  - verified (2026-10-20 11:10+0200 by dell26/agent-2): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.web.*' -> 49 tests, 0 failures (Postgres via Testcontainers); ./gradlew check --rerun-tasks -> pass

## Notes
- ASSUMPTION (2026-10-20 10:52+0200 by dell26/agent-2): Cursor pagination: default limit 50, allowed 1..200; out-of-range limit is rejected with 400 (field error 'limit') rather than clamped. Cursor = base64url of a module-chosen keyset string.
- ASSUMPTION (2026-10-20 10:52+0200 by dell26/agent-2): Stale If-Match -> 412, missing -> 428, JPA optimistic-lock failure at commit -> 409; If-Match uses strong comparison (W/ tags never match), '*' matches any version.
- ASSUMPTION (2026-10-20 10:52+0200 by dell26/agent-2): Added a small 'updateOpenApi' Gradle task (equivalent to UPDATE_OPENAPI=1 ... OpenApiContractTest) and springdoc props writer-with-order-by-keys + remove-broken-reference-definitions=false in application.properties so the shared ProblemDetail/FieldError schemas are in api/openapi.yaml; both files are outside the task's touches but minimal.
- ASSUMPTION (2026-10-20 11:10+0200 by dell26/agent-2): CR-6 deferred: missing multipart part gives Spring's default 400 without errors[]; whoever adds the first upload endpoint (files-api) should override handleMissingServletRequestPart in ProblemDetailsAdvice.
