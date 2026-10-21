---
title: Phase 4 demo script
status: in_progress
owner: dell26/agent-1
deps: [web-cal-edit,web-cal-sources,web-cal-overlay,web-today-events,web-gtd-convert-event,and-cal-edit,cal-invitations,backup-contrib-calendar,p3-smoke]
touches: [web/e2e/phase4,server/src/test/kotlin/ch/sonensei/cockpit/calendar/smoke]
prio: 1
lease_until: 2026-10-21T21:09:02Z
---
## Goal
Automate SPEC.md §13.2 Phase 4 (steps 1–5, providers via Radicale/WireMock).

## Acceptance criteria
- [ ] Playwright `web/e2e/phase4` covers steps 1, 4, 5; `external` server tests cover steps 2–3 (`./gradlew integrationTest`); earlier phase specs pass

## Notes
- ASSUMPTION (2026-10-21 22:52+0200 by dell26/agent-1): Step 4 'correct split on all providers': the Playwright demo splits a local series through the UI; the provider splits are server tests (CalDAV against Radicale in calendar/smoke/Phase4CalDavDemoTests, Google/Microsoft in the existing WireMock GoogleCalendarSyncTests/MicrosoftCalendarSyncTests that run in check) - a browser cannot reach Google/Microsoft and the e2e stack has no Radicale.
