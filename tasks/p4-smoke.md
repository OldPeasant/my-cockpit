---
title: Phase 4 demo script
status: todo
owner:
deps: [web-cal-edit,web-cal-sources,web-cal-overlay,web-today-events,web-gtd-convert-event,and-cal-edit,cal-invitations,backup-contrib-calendar,p3-smoke]
touches: [web/e2e/phase4,server/src/test/kotlin/ch/sonensei/cockpit/calendar/smoke]
prio: 1
---
## Goal
Automate SPEC.md §13.2 Phase 4 (steps 1–5, providers via Radicale/WireMock).

## Acceptance criteria
- [ ] Playwright `web/e2e/phase4` covers steps 1, 4, 5; `external` server tests cover steps 2–3 (`./gradlew integrationTest`); earlier phase specs pass

## Notes
