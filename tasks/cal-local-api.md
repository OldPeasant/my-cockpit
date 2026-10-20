---
title: Calendar API for local calendars
status: in_progress
owner: dell26/agent-3
deps: [cal-recurrence,p0-events-sse]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/api,server/src/test/kotlin/ch/sonensei/cockpit/calendar/api]
prio: 1
lease_until: 2026-10-20T16:52:47Z
---
## Goal
CRUD for local calendars and events (SPEC.md §6.4).

## Acceptance criteria
- [ ] `GET/POST/PATCH /api/v1/calendar/calendars`; `GET /api/v1/calendar/events?from=&to=&calendarIds=` returns expanded occurrences
- [ ] `POST/PATCH/DELETE /api/v1/calendar/events/{id}?scope=THIS|FOLLOWING|ALL&occurrence=` with correct series splitting/overrides for local events
- [ ] ChangeEvents, search, trash for events
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.api.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-20 18:37+0200 by dell26/agent-3): Service, DTOs and EVENT TrashContributor live in calendar/api (the task's touches); no separate calendar/domain package was created
- ASSUMPTION (2026-10-20 18:37+0200 by dell26/agent-3): POST /calendar/calendars creates LOCAL calendars only, in a single LOCAL source 'Cockpit' created on demand; the first calendar becomes the default; no DELETE for calendars (SPEC 6.4 lists only GET/POST/PATCH)
- ASSUMPTION (2026-10-20 18:37+0200 by dell26/agent-3): Event writes are only allowed in LOCAL, non-read-only calendars (409 otherwise); write-through to providers is left to cal-sync-engine
- ASSUMPTION (2026-10-20 18:37+0200 by dell26/agent-3): occurrence= is the original start: ISO instant (offset allowed) for timed series, YYYY-MM-DD for all-day series; scope defaults: ALL for a series master, THIS when addressing an override, ignored for single events; If-Match is the ETag of the addressed event
