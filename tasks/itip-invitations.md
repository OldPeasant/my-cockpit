---
title: Send invitations for local events (iTIP)
status: in_progress
owner: r1963macos/agent-1
deps: [cal-local-api,mail-compose]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip,server/src/test/kotlin/ch/sonensei/cockpit/calendar/itip]
prio: 3
lease_until: 2026-10-26T12:38:36Z
---
## Goal
Invite attendees by mail (SPEC.md §6.2).

## Acceptance criteria
- [ ] REQUEST/CANCEL as text/calendar mails; incoming REPLY updates attendee status
- [ ] `external` GreenMail tests

## Notes
- ASSUMPTION (2026-10-26 13:20+0100 by r1963macos/agent-1): No REST endpoint/UI for sending invitations in this task: touches only covers calendar/itip; ItipService.send is the server API, exposing it (controller + openapi + clients) is follow-up task itip-api
