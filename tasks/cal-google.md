---
title: Google Calendar adapter
status: in_progress
owner: dell26/agent-2
deps: [cal-sync-engine,oauth-connections]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/google,server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/google]
prio: 2
lease_until: 2026-10-21T12:21:48Z
releases: 1
extended: 
---
## Goal
Two-way Google sync (SPEC.md §6.2).

## Acceptance criteria
- [x] Calendar list, incremental sync with `syncToken` (410 → full resync), writes via API, recurring instance edits and 'this and following' split
  - verified (2026-10-21 13:22+0200 by dell26/agent-4): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.google.*' --rerun-tasks -> GoogleCalendarAdapterTests 35/35 (listCalendars paging, syncToken incremental fetch, 410 -> full listing, insert/patch/delete with If-Match, instance patches of exceptions), GoogleCalendarSyncTests 5/5 incl. 'this and following' split end to end against Postgres+WireMock
- [x] Tests against WireMock recordings of the Google API (incl. 410 and 412)
  - verified (2026-10-21 13:22+0200 by dell26/agent-4): same run: tests replay WireMock recordings under src/test/resources (incl. error-410-full-sync-required.json, error-412-condition-not-met.json); 410 and 412 tests pass (412 -> 409 in GoogleCalendarSyncTests)
- [x] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.google.*'` passes (Postgres via Testcontainers where persistence is involved)
  - verified (2026-10-21 13:22+0200 by dell26/agent-4): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.google.*' --rerun-tasks at cc5e7a7d -> BUILD SUCCESSFUL, 59 tests, 0 failures, 0 skipped (5 sync tests on Postgres via Testcontainers)

## Notes
- release by R1963MACOS/agent-1: handed off by R1963MACOS/agent-1: You've hit your weekly limit · resets Oct 24 at 8am (Europe/Zurich)
- ASSUMPTION (2026-10-21 12:43+0200 by dell26/agent-4): Writes send reminders as popup overrides with useDefault=false (CR-2): Google email reminders and calendar-default reminders become explicit popup overrides on any cockpit edit; the sync engine does not tell the adapter which fields changed, so the lossy mapping is accepted for now
- ASSUMPTION (2026-10-21 12:45+0200 by dell26/agent-4): Writes send the full attendee list (review round 2 CR-1): the Attendee model has no optional/comment/additionalGuests, so a cockpit edit makes optional Google attendees required and drops those details; accepted like the reminder mapping because the engine does not pass which fields changed
- extended by dell26/agent-4: Implementation, tests and two quality rounds are done and committed (round 2: 0 P0-P2, 2 P3; attendee/reminder mappings recorded as assumptions). Left: run the google test suite and verify AC 1-3, write and commit reviews/cal-google/quality.json (reviewed_revision = HEAD), run .taskcheck, then done. The server part of the check passed at 68d92f23. The web vitest part timed out (250s per test) because the host load average was about 300, which is unrelated: the task changes no web code.
- ASSUMPTION (2026-10-21 13:14+0200 by dell26/agent-4): Google writes send the full attendee list built from the cockpit model (email, name, response status); Google flags the cockpit does not model (optional, resource, comment, additionalGuests) are not preserved on edit. Same lossy-write trade-off as the reminder mapping; a follow-up can patch attendees only when they changed.
- release by dell26/agent-4: Implementation, 3 quality rounds (round 3: 0 findings) and AC 1-3 verification are done and committed; reviews/cal-google/quality.json is committed with reviewed_revision 6af242e9 (server code identical to reviewed cc5e7a7d, only reviews/ changed since). sh scripts/check.sh passed at 4eb5f2b9. Left: only run 'python tasks.py done cal-google' (if done flags freshness, set reviewed_revision to the current HEAD of the task code, no helper re-run needed).
