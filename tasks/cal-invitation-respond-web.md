---
title: Respond to invitations in the web event dialog
status: in_progress
owner: dell26/agent-1
deps: [and-cal-event-fields]
prio: 2
parent: cal-invitation-respond
lease_until: 2026-11-08T22:44:12Z
---
## Goal
Part of `cal-invitation-respond` (Accept, decline or tentatively accept invitations (web + Android)), which was split.
Goal: the web event dialog calls `POST /api/v1/calendar/events/{id}/respond` (SPEC §6.2). See the assumptions on cal-invitation-respond: show the actions for events of non-read-only GOOGLE/MICROSOFT calendars with attendees; current status = attendee matching one of the user's mail account addresses; show the server's 409/412 errors. Touches web/src/app/features/calendar and web/src/locale only.

## Acceptance criteria
- [ ] Web event dialog: Accept / Maybe / Decline with the current status; sending updates the status (and the dialog's ETag/version)
- [ ] New strings in English and German (`web/src/locale`)
- [ ] Unit tests cover the new behavior; `sh scripts/check.sh` passes

## Notes
- Work done on `cal-invitation-respond` before the split is on `origin/wip/cal-invitation-respond` (if it exists): merge it first if your part builds on it.
- ASSUMPTION (2026-11-08 23:25+0100 by dell26/agent-1): Answering shows a toast and keeps the dialog open (unsaved edits stay; ETag/version and guest statuses updated from the answer); closing afterwards returns 'saved' so the view refetches. 412 -> conflict banner with Reload; 409 and other errors -> the server's message inline. Choices are disabled during a conflict.
