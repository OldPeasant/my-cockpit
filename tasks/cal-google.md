---
title: Google Calendar adapter
status: in_progress
owner: dell26/agent-4
deps: [cal-sync-engine,oauth-connections]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google,server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/google]
prio: 2
lease_until: 2026-10-21T11:31:19Z
releases: 0
extended: dell26/agent-4
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
- ASSUMPTION (2026-10-21 12:45+0200 by dell26/agent-4): Writes send the full attendee list (review round 2 CR-1): the Attendee model has no optional/comment/additionalGuests, so a cockpit edit makes optional Google attendees required and drops those details; accepted like the reminder mapping because the engine does not pass which fields changed
- extended by dell26/agent-4: Implementation, tests and two quality rounds are done and committed (round 2: 0 P0-P2, 2 P3; attendee/reminder mappings recorded as assumptions). Left: run the google test suite and verify AC 1-3, write and commit reviews/cal-google/quality.json (reviewed_revision = HEAD), run .taskcheck, then done. The server part of the check passed at 68d92f23. The web vitest part timed out (250s per test) because the host load average was about 300, which is unrelated: the task changes no web code.
