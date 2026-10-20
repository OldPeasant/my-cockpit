---
title: Reminders and notifications backend
status: done
owner: dell26/agent-3
deps: [p0-events-sse,p0-items-links]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/reminders,server/src/test/kotlin/ch/sonensei/cockpit/core/reminders,server/src/main/resources/db/migration/core-reminders]
prio: 2
lease_until: 
extended: dell26/agent-3
---
## Goal
Generic reminders for any item (SPEC.md §4.6).

## Acceptance criteria
- [x] `reminder` table + `GET/POST/DELETE /api/v1/reminders` (itemType, itemId, remindAt); `GET /api/v1/reminders/upcoming?until=` for Android sync
  - verified (2026-10-20 16:50+0200 by dell26/agent-3): Flyway migration core/reminders + ReminderController; ./gradlew test --tests 'ch.sonensei.cockpit.core.reminders.*' (ReminderWebTests: GET/POST/DELETE /api/v1/reminders, GET /{id}, GET /upcoming?until=, Problem Details 400/404) against Testcontainers Postgres -> pass (52 reminders tests, 0 failures)
- [x] Scheduler fires due reminders once (sets `firedAt`) and publishes a `reminder` SSE event with the resolved item title
  - verified (2026-10-20 16:50+0200 by dell26/agent-3): ./gradlew test --tests 'ch.sonensei.cockpit.core.reminders.*' (ReminderSchedulerRunTests, ReminderEventsStreamTests, ReminderGtdResolverTests: due reminder fired once, firedAt set, 'reminder' SSE event with resolved task/project title delivered after commit) -> pass
- [x] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.reminders.*'` passes (Postgres via Testcontainers where persistence is involved) with the test clock (fires once, not twice after restart)
  - verified (2026-10-20 16:50+0200 by dell26/agent-3): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.reminders.*' --tests '*OpenApiContractTest' on rebased HEAD (Testcontainers Postgres, test clock; ReminderServiceTests restart case: second service instance does not re-fire) -> BUILD SUCCESSFUL, 53 tests, 0 failures
- [x] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag
  - verified (2026-10-20 16:50+0200 by dell26/agent-3): UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest' regenerated api/openapi.yaml (+ npm run api:gen web client, commit c4c7dcc); then ./gradlew test --tests '*OpenApiContractTest' without the flag on rebased HEAD -> pass

## Notes
- ASSUMPTION (2026-10-20 15:37+0200 by dell26/agent-3): SSE: a fired reminder is sent as SSE event 'reminder' with a ReminderResponse (incl. item title/deepLink) as data, via a new generic ClientNotification event that SseEventHub (core/events, outside touches; small addition) forwards after commit; ChangeEvent stays content-free. Every reminder change also sends a 'change' event of type REMINDER
- ASSUMPTION (2026-10-20 15:37+0200 by dell26/agent-3): channel: enum with the single value NOTIFICATION (SSE/browser + Android local alarm), default when omitted; spec names the column but no values
- ASSUMPTION (2026-10-20 15:38+0200 by dell26/agent-3): Firing: scheduler polls every cockpit.reminders.poll-interval (default PT15S), SELECT ... FOR UPDATE SKIP LOCKED, sets firedAt once; overdue reminders (server was down) fire on the next run; a reminder whose item resolver says the item is gone (deleted/trash) is marked fired without notification; disabled in tests via cockpit.reminders.scheduler.enabled=false (test config)
- ASSUMPTION (2026-10-20 15:38+0200 by dell26/agent-3): API: GET /reminders requires itemType+itemId (reminders of one item); also GET /reminders/{id}; reminders are immutable (no PATCH, delete without If-Match); upcoming?until= returns unfired reminders <= until incl. overdue, max 1000; reminders of a permanently deleted item are removed via ItemDeletedEvent
- ASSUMPTION (2026-10-20 16:00+0200 by dell26/agent-3): Migration lives in db/migration/core/V202610201538__core_reminders.sql, not core-reminders/ as in touches: MigrationConventionTests requires folder == module name in the file name (module core; reminders are package core.reminders)
- extended by dell26/agent-3: Implementation + CR-1/CR-2 fixes committed, openapi regenerated, reminders tests pass. Left: rerun test-writer + code-reviewer on final code (fixes made earlier evidence stale; ReminderGtdResolverTests just committed), verify criteria 1-4, run .taskcheck, write quality.json, done.
- ASSUMPTION (2026-10-20 16:50+0200 by dell26/agent-3): Reminder item lookups (ReminderItems) run in a separate REQUIRES_NEW read-only transaction, so each lookup borrows a second pooled DB connection while the request/firing transaction holds one; accepted for a single-user server (review rerun CR-1, P3).
- ASSUMPTION (2026-10-20 16:50+0200 by dell26/agent-3): GET /reminders/upcoming returns reminders of trashed/deleted items and of items whose resolver failed alike as available=false; the server skips notifying only for gone items. Android (and-reminders) should not schedule alarms for available=false items or the API should distinguish them (review rerun CR-2, P3, deferred to and-reminders).
- done by dell26/agent-3: `sh scripts/check.sh` passed in 36s on 8307fc932; criteria verified 4/4
