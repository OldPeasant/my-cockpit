---
title: Microsoft Graph calendar adapter
status: in_progress
owner: dell26/agent-2
deps: [cal-sync-engine,oauth-connections]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft,server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft]
prio: 2
lease_until: 2026-10-21T10:43:52Z
---
## Goal
Two-way Microsoft sync (SPEC.md §6.2).

## Acceptance criteria
- [ ] Calendars, `calendarView/delta` incremental sync, writes, occurrence edits, 'following' split
- [ ] Tests against WireMock recordings of Graph
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.infra.microsoft.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
- ASSUMPTION (2026-10-21 12:24+0200 by dell26/agent-2): Microsoft sync covers a window of 365 days back to 730 days ahead (calendarView/delta needs one); the window moves with a full relisting every 7 days. Events outside it are not synced.
- ASSUMPTION (2026-10-21 12:24+0200 by dell26/agent-2): calendarView/delta reports removals by id only (a removed occurrence cannot be told from a removed event), so a delta with any removal triggers a full relisting of the calendar in the same run (complete=true).
- ASSUMPTION (2026-10-21 12:24+0200 by dell26/agent-2): Series EXDATEs are derived: occurrences the master's pattern produces in the window (cockpit expander) that Graph's /instances no longer has; matching tolerates 12h (Graph patterns recur at most daily). EXDATEs and cancelled overrides written by the cockpit delete the Graph instance.
- ASSUMPTION (2026-10-21 12:24+0200 by dell26/agent-2): Updates send only fields that differ from Graph's current copy (GET first): avoids resetting series exceptions on unchanged recurrence and re-inviting attendees. Graph has one reminder: only the first cockpit reminder is written. showAs free=TRANSPARENT, tentative=TENTATIVE; writes busy otherwise.
