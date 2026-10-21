---
title: Google Calendar adapter
status: in_progress
owner: dell26/agent-4
deps: [cal-sync-engine,oauth-connections]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google,server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/google]
prio: 2
lease_until: 2026-10-21T10:21:30Z
releases: 0
extended: 
---
## Goal
Two-way Google sync (SPEC.md §6.2).

## Acceptance criteria
- [ ] Calendar list, incremental sync with `syncToken` (410 → full resync), writes via API, recurring instance edits and 'this and following' split
- [ ] Tests against WireMock recordings of the Google API (incl. 410 and 412)
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.google.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
- release by R1963MACOS/agent-1: handed off by R1963MACOS/agent-1: You've hit your weekly limit · resets Oct 24 at 8am (Europe/Zurich)
