---
title: Phase 4 demo script
status: done
owner: dell26/agent-1
deps: [web-cal-edit,web-cal-sources,web-cal-overlay,web-today-events,web-gtd-convert-event,and-cal-edit,cal-invitations,backup-contrib-calendar,p3-smoke]
touches: [web/e2e/phase4,server/src/test/kotlin/ch/sonensei/cockpit/calendar/smoke]
prio: 1
lease_until: 
---
## Goal
Automate SPEC.md §13.2 Phase 4 (steps 1–5, providers via Radicale/WireMock).

## Acceptance criteria
- [x] Playwright `web/e2e/phase4` covers steps 1, 4, 5; `external` server tests cover steps 2–3 (`./gradlew integrationTest`); earlier phase specs pass
  - verified (2026-10-21 23:06+0200 by dell26/agent-1): cd web && npm run e2e -- --project=phase-demos (podman-compose e2e stack, deploy/docker-compose.e2e.yml) -> 24 passed incl. phase4/demo.spec.ts steps 1, 4, 5 (3 tests) and phase0-2 demos; cd server && ./gradlew integrationTest --rerun-tasks -> 76 tests 0 failures incl. calendar.smoke Phase4CalDavDemoTests (Radicale: step 2 + CalDAV split) 2 passed and Phase4OAuthProvidersDemoTests (WireMock recordings: step 3 Google + Microsoft) 2 passed (an earlier run hit a transient podman 'Broken pipe' starting Radicale in CalDavRadicaleSyncTests; passed 10/10 on re-run); phase 3: e2e stack on 127.0.0.1:18390 + cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.smoke.*' -Pe2eBaseUrl=http://127.0.0.1:18390/ -> Phase3DemoRobolectricTest 1 passed

## Notes
- ASSUMPTION (2026-10-21 22:52+0200 by dell26/agent-1): Step 4 'correct split on all providers': the Playwright demo splits a local series through the UI; the provider splits are server tests (CalDAV against Radicale in calendar/smoke/Phase4CalDavDemoTests, Google/Microsoft in the existing WireMock GoogleCalendarSyncTests/MicrosoftCalendarSyncTests that run in check) - a browser cannot reach Google/Microsoft and the e2e stack has no Radicale.
- ASSUMPTION (2026-10-21 22:53+0200 by dell26/agent-1): Step 1 'one moved occurrence' = moving the 30 March occurrence to Tuesday 31 March 14:00 through the event dialog (scope 'Only this event'); 'correct across a DST change' = the series stays at 09:00 wall-clock in week/day/month views on both sides of 2026-03-29 and the API returns 08:00Z before / 07:00Z after. Step 5's task is created via the API (task creation itself is phase 1).
- done by dell26/agent-1: `sh scripts/check.sh` passed in 71s on 61cbbe4c1; criteria verified 1/1
