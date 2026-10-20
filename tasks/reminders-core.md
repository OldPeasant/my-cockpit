---
title: Reminders and notifications backend
status: todo
owner:
deps: [p0-events-sse,p0-items-links]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/reminders,server/src/test/kotlin/ch/sonensei/cockpit/core/reminders,server/src/main/resources/db/migration/core-reminders]
prio: 2
---
## Goal
Generic reminders for any item (SPEC.md §4.6).

## Acceptance criteria
- [ ] `reminder` table + `GET/POST/DELETE /api/v1/reminders` (itemType, itemId, remindAt); `GET /api/v1/reminders/upcoming?until=` for Android sync
- [ ] Scheduler fires due reminders once (sets `firedAt`) and publishes a `reminder` SSE event with the resolved item title
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.reminders.*'` passes (Postgres via Testcontainers where persistence is involved) with the test clock (fires once, not twice after restart)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
