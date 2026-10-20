---
title: Web SSE client
status: in_progress
owner: dell26/agent-4
deps: [p0-web-api-client,p0-events-sse]
touches: [web/src/app/core/events]
prio: 1
lease_until: 2026-10-20T12:40:13Z
---
## Goal
A shared service that keeps one SSE connection and dispatches change events to feature stores.

## Acceptance criteria
- [ ] `ChangeEventsService` connects after login to `/api/v1/events`, reconnects with backoff, exposes `events$(type)` per item type
- [ ] On reconnect it emits a `resync` signal so stores can refetch (missed events)
- [ ] Vitest component/service tests cover dispatch by type, reconnect with backoff using fake timers, resync on reconnect; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-20 14:16+0200 by dell26/agent-4): ChangeEventsService connects lazily: it is a root service whose effect follows AuthService.loggedIn, so the stream opens once the first store injects it while logged in (no app.config change, outside this task's touches)
- ASSUMPTION (2026-10-20 14:16+0200 by dell26/agent-4): Reconnect backoff is 1s doubling to max 30s (no jitter, single-user app), reset after a successful open; own backoff replaces EventSource's built-in retry, which never retries after a 401/non-200
- ASSUMPTION (2026-10-20 14:16+0200 by dell26/agent-4): resync$ emits on every reopen within one login (incl. after the server's 15-min stream end), not on the first connect after login; malformed event data is ignored silently
