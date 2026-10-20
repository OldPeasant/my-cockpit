---
title: Calendar sync engine
status: in_progress
owner: dell26/agent-2
deps: [cal-local-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/sync,server/src/test/kotlin/ch/sonensei/cockpit/calendar/sync]
prio: 1
lease_until: 2026-10-20T23:00:42Z
---
## Goal
Provider-independent two-way sync framework (SPEC.md §6.2).

## Acceptance criteria
- [ ] `CalendarProviderAdapter` interface (list calendars, incremental fetch with a sync cursor, create/update/delete with etag) + registry by source type
- [ ] Scheduler polls each source at its interval, stores cursor/last sync/error; write-through: local edits of external events go to the adapter first; 412 → refetch and return 'changed remotely' (409 to the client)
- [ ] `GET /api/v1/calendar/sources` incl. sync status; `POST …/{id}/sync`
- [ ] Tests with a fake adapter: incremental merge, deletions, conflict path
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.sync.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-21 00:42+0200 by dell26/agent-2): Sync status (last attempt/success/error) and per-calendar cursors live in new tables calendar_source_sync and calendar_sync_cursor (migration in db/migration/calendar, outside the task's touches), so a sync never bumps the ETag of sources/calendars.
