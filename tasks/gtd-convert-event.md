---
title: Convert task to calendar event
status: in_progress
owner: dell26/agent-1
deps: [gtd-convert-note,cal-local-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert,server/src/test/kotlin/ch/sonensei/cockpit/gtd/convert]
prio: 2
lease_until: 2026-10-21T15:05:50Z
---
## Goal
'To calendar' in inbox processing (SPEC.md §5.3).

## Acceptance criteria
- [x] `POST /api/v1/tasks/{id}/convert-to-event {calendarId, start, end|allDay}` creates the event, keeps links, deletes the task
  - verified (2026-10-21 16:36+0200 by dell26/agent-1): ./gradlew test --tests 'ch.sonensei.cockpit.gtd.convert.*' (Testcontainers Postgres) -> TaskToEventWebTests 9/9 + TaskToEventEdgeCaseTests 22/22 + RollbackTests 1/1 pass: POST /api/v1/tasks/{id}/convert-to-event creates event (201, Location), copies links, trashes task; JaCoCo service 29/29 lines
- [x] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.convert.*'` passes (Postgres via Testcontainers where persistence is involved)
  - verified (2026-10-21 16:36+0200 by dell26/agent-1): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.convert.*' jacocoTestReport -> BUILD SUCCESSFUL, 58 tests, 0 failures (Postgres via Testcontainers)
- [x] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag
  - verified (2026-10-21 16:36+0200 by dell26/agent-1): UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest' --rerun -> no diff in api/ or web/; ./gradlew test --tests '*OpenApiContractTest' without flag -> BUILD SUCCESSFUL

## Notes
- ASSUMPTION (2026-10-21 16:10+0200 by dell26/agent-1): convert-to-event takes the same time fields as createEvent (allDay, start, end, timeZone, startDate, endDate); calendarId is optional and defaults to the default calendar, like createEvent. Event title = task title, description = task notes (none when blank).
- ASSUMPTION (2026-10-21 16:10+0200 by dell26/agent-1): Regenerated web/src/app/api/generated (outside touches) because npm run check's api:check requires it to match api/openapi.yaml.
