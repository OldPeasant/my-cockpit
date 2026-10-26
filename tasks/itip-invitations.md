---
title: Send invitations for local events (iTIP)
status: in_progress
owner: r1963macos/agent-1
deps: [cal-local-api,mail-compose]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip,server/src/test/kotlin/ch/sonensei/cockpit/calendar/itip]
prio: 3
lease_until: 2026-10-26T13:09:39Z
---
## Goal
Invite attendees by mail (SPEC.md §6.2).

## Acceptance criteria
- [ ] REQUEST/CANCEL as text/calendar mails; incoming REPLY updates attendee status
- [ ] `external` GreenMail tests

## Notes
- ASSUMPTION (2026-10-26 13:20+0100 by r1963macos/agent-1): No REST endpoint/UI for sending invitations in this task: touches only covers calendar/itip; ItipService.send is the server API, exposing it (controller + openapi + clients) is follow-up task itip-api
- ASSUMPTION (2026-10-26 13:20+0100 by r1963macos/agent-1): Incoming REPLY is only applied when it arrives in an Inbox, the event is in a LOCAL calendar, lists the attendee, and the mail's From matches the attendee address (anti-spoofing); delegated/alias replies from another address are ignored
- ASSUMPTION (2026-10-26 13:20+0100 by r1963macos/agent-1): A REPLY for one occurrence without an existing override is ignored (no override created); a series REPLY updates the master and overrides listing the attendee. Storing a status bumps the event version, which is also the iTIP SEQUENCE
