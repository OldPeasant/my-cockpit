---
title: Microsoft Graph calendar adapter
status: todo
owner:
deps: [cal-sync-engine,oauth-connections]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft,server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft]
prio: 2
---
## Goal
Two-way Microsoft sync (SPEC.md §6.2).

## Acceptance criteria
- [ ] Calendars, `calendarView/delta` incremental sync, writes, occurrence edits, 'following' split
- [ ] Tests against WireMock recordings of Graph
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.microsoft.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
