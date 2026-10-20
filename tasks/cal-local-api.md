---
title: Calendar API for local calendars
status: todo
owner:
deps: [cal-recurrence,p0-events-sse]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/api,server/src/test/kotlin/ch/sonensei/cockpit/calendar/api]
prio: 1
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
