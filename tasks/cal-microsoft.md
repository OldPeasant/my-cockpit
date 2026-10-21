---
title: Microsoft Graph calendar adapter
status: in_progress
owner: dell26/agent-2
deps: [cal-sync-engine,oauth-connections]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft,server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/microsoft]
prio: 2
lease_until: 2026-10-21T12:11:42Z
extended: dell26/agent-2
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
- ASSUMPTION (2026-10-21 12:24+0200 by dell26/agent-2): Zones: Graph returns UTC (Prefer outlook.timezone); originalStartTimeZone maps via a CLDR Windows->IANA table; writes send the Windows name of golden zones, else the IANA id (Graph accepts both). RRULEs Graph cannot express (HOURLY, BYHOUR, several BYMONTHDAY...) are refused with 502 on write.
- extended by dell26/agent-2: Adapter, WireMock recording tests and Postgres sync tests are done and committed. Fixes for review findings CR-1 (FOLLOWING split restores exceptions), CR-2 (events addressed via /me/calendars/{cal}/events) and CR-5 (page bound) are in b54e6a37. Focused microsoft.* tests and scripts/check.sh pass (check took >10 min under load). Left: decide CR-3/CR-4/CR-6 (fix or record assumption), run the fixer sub-agent so fixes.md exists, re-run test-writer and code-reviewer on the final code, verify AC 1-3, write quality.json, then done.
