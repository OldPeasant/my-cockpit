---
title: Reminders and notifications backend
status: in_progress
owner: dell26/agent-3
deps: [p0-events-sse,p0-items-links]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/reminders,server/src/test/kotlin/ch/sonensei/cockpit/core/reminders,server/src/main/resources/db/migration/core-reminders]
prio: 2
lease_until: 2026-10-20T14:16:28Z
---
## Goal
Generic reminders for any item (SPEC.md §4.6).

## Acceptance criteria
- [ ] `reminder` table + `GET/POST/DELETE /api/v1/reminders` (itemType, itemId, remindAt); `GET /api/v1/reminders/upcoming?until=` for Android sync
- [ ] Scheduler fires due reminders once (sets `firedAt`) and publishes a `reminder` SSE event with the resolved item title
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.reminders.*'` passes (Postgres via Testcontainers where persistence is involved) with the test clock (fires once, not twice after restart)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-20 15:37+0200 by dell26/agent-3): SSE: a fired reminder is sent as SSE event 'reminder' with a ReminderResponse (incl. item title/deepLink) as data, via a new generic ClientNotification event that SseEventHub (core/events, outside touches; small addition) forwards after commit; ChangeEvent stays content-free. Every reminder change also sends a 'change' event of type REMINDER
- ASSUMPTION (2026-10-20 15:37+0200 by dell26/agent-3): channel: enum with the single value NOTIFICATION (SSE/browser + Android local alarm), default when omitted; spec names the column but no values
- ASSUMPTION (2026-10-20 15:38+0200 by dell26/agent-3): Firing: scheduler polls every cockpit.reminders.poll-interval (default PT15S), SELECT ... FOR UPDATE SKIP LOCKED, sets firedAt once; overdue reminders (server was down) fire on the next run; a reminder whose item resolver says the item is gone (deleted/trash) is marked fired without notification; disabled in tests via cockpit.reminders.scheduler.enabled=false (test config)
- ASSUMPTION (2026-10-20 15:38+0200 by dell26/agent-3): API: GET /reminders requires itemType+itemId (reminders of one item); also GET /reminders/{id}; reminders are immutable (no PATCH, delete without If-Match); upcoming?until= returns unfired reminders <= until incl. overdue, max 1000; reminders of a permanently deleted item are removed via ItemDeletedEvent
- ASSUMPTION (2026-10-20 16:00+0200 by dell26/agent-3): Migration lives in db/migration/core/V202610201538__core_reminders.sql, not core-reminders/ as in touches: MigrationConventionTests requires folder == module name in the file name (module core; reminders are package core.reminders)
