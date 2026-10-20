---
title: Convert task to calendar event
status: todo
owner:
deps: [gtd-convert-note,cal-local-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert,server/src/test/kotlin/ch/sonensei/cockpit/gtd/convert]
prio: 2
---
## Goal
'To calendar' in inbox processing (SPEC.md §5.3).

## Acceptance criteria
- [ ] `POST /api/v1/tasks/{id}/convert-to-event {calendarId, start, end|allDay}` creates the event, keeps links, deletes the task
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.convert.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
