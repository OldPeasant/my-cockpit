---
title: Change events and SSE stream
status: in_progress
owner: dell26/agent-4
deps: [p0-auth-login]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/events,server/src/test/kotlin/ch/sonensei/cockpit/core/events]
prio: 1
lease_until: 2026-10-20T12:15:00Z
---
## Goal
Modules publish change events; clients receive them over SSE (SPEC.md §2).

## Acceptance criteria
- [ ] `ChangeEvent(type: ItemType|String, id, op: CREATED|UPDATED|DELETED, at)` published via Spring events after commit (`@TransactionalEventListener`)
- [ ] `GET /api/v1/events` (authenticated) streams events as SSE with heartbeat comments every 25 s; disconnected emitters are cleaned up
- [ ] Test: an event published in a committed transaction reaches a connected client; a rolled-back one does not; `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.events.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-20 13:35+0200 by dell26/agent-4): ChangeEvent.type is a String; a secondary constructor/publish overload takes any Enum (its name), since ItemType does not exist yet (p0-items-links) and events also carry non-item kinds like REMINDER
- ASSUMPTION (2026-10-20 13:35+0200 by dell26/agent-4): Events published outside a transaction are delivered right away (@TransactionalEventListener fallbackExecution=true), so non-transactional publishers (schedulers, sync) work; transactional ones only after commit
- ASSUMPTION (2026-10-20 13:35+0200 by dell26/agent-4): SecurityConfiguration (auth infra, outside touches) permits DispatcherType.ASYNC: the async dispatch that ends an SSE stream skips the session filter and was rejected with an error log; the original request is still authorized
- ASSUMPTION (2026-10-20 13:35+0200 by dell26/agent-4): SSE: event name 'change', data = ChangeEvent JSON, initial ':connected' comment, no event ids/replay (clients refetch on reconnect), no emitter timeout (dead clients found by heartbeat write failure); sends on one background thread
- ASSUMPTION (2026-10-20 13:58+0200 by dell26/agent-4): SSE streams end after cockpit.events.stream-timeout (default 15 min) and EventSource reconnects, so each reconnect re-checks the login; replaces the earlier 'no emitter timeout' (a stream outlives logout by at most 15 min)
