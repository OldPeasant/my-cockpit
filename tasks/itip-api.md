---
title: API + web UI to send iTIP invitations for local events
status: in_progress
owner: dell26/agent-1
deps: [itip-invitations]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip,api/openapi.yaml,web/src/app/features/calendar]
prio: 2
lease_until: 2026-10-26T15:14:02Z
---
## Goal
Let the user send invitations (REQUEST/CANCEL) for local events from the web UI (SPEC.md §6.2), using `ItipService.send`.

## Acceptance criteria
- [ ] POST endpoint to send REQUEST/CANCEL for an event over a chosen mail account; Problem Details errors; openapi regenerated
- [ ] Web event detail offers "Send invitations" for local events with attendees

## Notes
