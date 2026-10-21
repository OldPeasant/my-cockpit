---
title: Google Calendar adapter
status: in_progress
owner: dell26/agent-4
deps: [cal-sync-engine,oauth-connections]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google,server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/google]
prio: 2
lease_until: 2026-10-21T11:01:53Z
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
- ASSUMPTION (2026-10-21 12:43+0200 by dell26/agent-4): Writes send reminders as popup overrides with useDefault=false (CR-2): Google email reminders and calendar-default reminders become explicit popup overrides on any cockpit edit; the sync engine does not tell the adapter which fields changed, so the lossy mapping is accepted for now
