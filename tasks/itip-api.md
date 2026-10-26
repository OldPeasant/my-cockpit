---
title: API + web UI to send iTIP invitations for local events
status: in_progress
owner: dell26/agent-1
deps: [itip-invitations]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip,api/openapi.yaml,web/src/app/features/calendar]
prio: 2
lease_until: 2026-10-26T15:24:06Z
---
## Goal
Let the user send invitations (REQUEST/CANCEL) for local events from the web UI (SPEC.md §6.2), using `ItipService.send`.

## Acceptance criteria
- [ ] POST endpoint to send REQUEST/CANCEL for an event over a chosen mail account; Problem Details errors; openapi regenerated
- [ ] Web event detail offers "Send invitations" for local events with attendees

## Notes
- ASSUMPTION (2026-10-26 15:58+0100 by dell26/agent-1): Endpoint is POST /api/v1/calendar/events/{id}/invitations {method, accountId, recipients?} -> 200 {method, recipients}; no If-Match because sending does not change the event. SMTP refusal -> 422 SEND_REJECTED, mail server failure -> 502/503 with errorCode (same as DraftController).
- ASSUMPTION (2026-10-26 15:58+0100 by dell26/agent-1): Web: 'Send invitations' sits in the event dialog actions for saved events of writable LOCAL calendars with saved guests; it sends to all guests (server default, no per-guest picking), over a chosen mail account (first preselected), REQUEST or CANCEL. Unsaved edits block it ('save first'). An override occurrence sends just the override; a generated occurrence sends the whole series.
- ASSUMPTION (2026-10-26 15:58+0100 by dell26/agent-1) [confirmed 2026-10-26 16:03+0100 by Peter Gürtler]: frontend-design skill not used: a small dialog that follows the existing scope-dialog/event-dialog Material patterns, no new visual direction.
