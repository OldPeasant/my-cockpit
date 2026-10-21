---
title: Phase 4 demo script
status: in_progress
owner: dell26/agent-1
deps: [web-cal-edit,web-cal-sources,web-cal-overlay,web-today-events,web-gtd-convert-event,and-cal-edit,cal-invitations,backup-contrib-calendar,p3-smoke]
touches: [web/e2e/phase4,server/src/test/kotlin/ch/sonensei/cockpit/calendar/smoke]
prio: 1
lease_until: 2026-10-21T21:14:05Z
---
## Goal
Automate SPEC.md §13.2 Phase 4 (steps 1–5, providers via Radicale/WireMock).

## Acceptance criteria
- [ ] Playwright `web/e2e/phase4` covers steps 1, 4, 5; `external` server tests cover steps 2–3 (`./gradlew integrationTest`); earlier phase specs pass

## Notes
- ASSUMPTION (2026-10-21 22:52+0200 by dell26/agent-1): Step 4 'correct split on all providers': the Playwright demo splits a local series through the UI; the provider splits are server tests (CalDAV against Radicale in calendar/smoke/Phase4CalDavDemoTests, Google/Microsoft in the existing WireMock GoogleCalendarSyncTests/MicrosoftCalendarSyncTests that run in check) - a browser cannot reach Google/Microsoft and the e2e stack has no Radicale.
- ASSUMPTION (2026-10-21 22:53+0200 by dell26/agent-1): Step 1 'one moved occurrence' = moving the 30 March occurrence to Tuesday 31 March 14:00 through the event dialog (scope 'Only this event'); 'correct across a DST change' = the series stays at 09:00 wall-clock in week/day/month views on both sides of 2026-03-29 and the API returns 08:00Z before / 07:00Z after. Step 5's task is created via the API (task creation itself is phase 1).
