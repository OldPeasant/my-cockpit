---
title: Convert task to calendar event
status: in_progress
owner: dell26/agent-1
deps: [gtd-convert-note,cal-local-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert,server/src/test/kotlin/ch/sonensei/cockpit/gtd/convert]
prio: 2
lease_until: 2026-10-21T14:50:40Z
---
## Goal
'To calendar' in inbox processing (SPEC.md §5.3).

## Acceptance criteria
- [ ] `POST /api/v1/tasks/{id}/convert-to-event {calendarId, start, end|allDay}` creates the event, keeps links, deletes the task
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.convert.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-21 16:10+0200 by dell26/agent-1): convert-to-event takes the same time fields as createEvent (allDay, start, end, timeZone, startDate, endDate); calendarId is optional and defaults to the default calendar, like createEvent. Event title = task title, description = task notes (none when blank).
- ASSUMPTION (2026-10-21 16:10+0200 by dell26/agent-1): Regenerated web/src/app/api/generated (outside touches) because npm run check's api:check requires it to match api/openapi.yaml.
