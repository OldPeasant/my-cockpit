---
title: Change events and SSE stream
status: todo
owner:
deps: [p0-auth-login]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/events,server/src/test/kotlin/ch/sonensei/cockpit/core/events]
prio: 1
---
## Goal
Modules publish change events; clients receive them over SSE (SPEC.md §2).

## Acceptance criteria
- [ ] `ChangeEvent(type: ItemType|String, id, op: CREATED|UPDATED|DELETED, at)` published via Spring events after commit (`@TransactionalEventListener`)
- [ ] `GET /api/v1/events` (authenticated) streams events as SSE with heartbeat comments every 25 s; disconnected emitters are cleaned up
- [ ] Test: an event published in a committed transaction reaches a connected client; a rolled-back one does not; `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.events.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
