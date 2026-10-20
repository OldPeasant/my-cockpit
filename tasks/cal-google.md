---
title: Google Calendar adapter
status: todo
owner:
deps: [cal-sync-engine,oauth-connections]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google,server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/google]
prio: 2
---
## Goal
Two-way Google sync (SPEC.md §6.2).

## Acceptance criteria
- [ ] Calendar list, incremental sync with `syncToken` (410 → full resync), writes via API, recurring instance edits and 'this and following' split
- [ ] Tests against WireMock recordings of the Google API (incl. 410 and 412)
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.google.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
