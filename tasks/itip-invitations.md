---
title: Send invitations for local events (iTIP)
status: todo
owner:
deps: [cal-local-api,mail-compose]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip,server/src/test/kotlin/ch/sonensei/cockpit/calendar/itip]
prio: 3
---
## Goal
Invite attendees by mail (SPEC.md §6.2).

## Acceptance criteria
- [ ] REQUEST/CANCEL as text/calendar mails; incoming REPLY updates attendee status
- [ ] `external` GreenMail tests

## Notes
