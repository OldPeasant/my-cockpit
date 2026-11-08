---
title: Respond to invitations in the Android event editor
status: in_progress
owner: felix/agent-1
deps: [and-cal-event-fields]
prio: 2
parent: cal-invitation-respond
lease_until: 2026-11-08T22:38:22Z
---
## Goal
Part of `cal-invitation-respond` (Accept, decline or tentatively accept invitations (web + Android)), which was split.
Goal: the Android event view/editor calls `POST /api/v1/calendar/events/{id}/respond` (SPEC §6.2), same rules as the web subtask (see the assumptions on cal-invitation-respond). Touches android/.../calendar/edit, its tests and strings_calendar_edit.xml (values, values-de) only.

## Acceptance criteria
- [ ] Android event view/editor: Accept / Maybe / Decline with the current status; sending updates the status
- [ ] New strings in English and German (`res/values{,-de}/strings_calendar_edit.xml`)
- [ ] Unit tests cover the new behavior; `sh scripts/check.sh` passes

## Notes
- Work done on `cal-invitation-respond` before the split is on `origin/wip/cal-invitation-respond` (if it exists): merge it first if your part builds on it.
- ASSUMPTION (2026-11-08 23:11+0100 by felix/agent-1): Same rules as web (parent assumption #1): Accept/Maybe/Decline shown for existing events with guests in writable GOOGLE/MICROSOFT calendars; own status = guest matching a mail account address; 412 -> conflict (reload), 404 -> gone, other failures (409 organizer/not invited) -> 'could not be sent' message; unsaved form edits are kept after answering.
