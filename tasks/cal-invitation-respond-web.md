---
title: Respond to invitations in the web event dialog
status: done
owner: dell26/agent-1
deps: [and-cal-event-fields]
prio: 2
parent: cal-invitation-respond
lease_until: 
---
## Goal
Part of `cal-invitation-respond` (Accept, decline or tentatively accept invitations (web + Android)), which was split.
Goal: the web event dialog calls `POST /api/v1/calendar/events/{id}/respond` (SPEC §6.2). See the assumptions on cal-invitation-respond: show the actions for events of non-read-only GOOGLE/MICROSOFT calendars with attendees; current status = attendee matching one of the user's mail account addresses; show the server's 409/412 errors. Touches web/src/app/features/calendar and web/src/locale only.

## Acceptance criteria
- [x] Web event dialog: Accept / Maybe / Decline with the current status; sending updates the status (and the dialog's ETag/version)
  - verified (2026-11-08 23:33+0100 by dell26/agent-1): npm --prefix web exec -- ng test --include src/app/features/calendar/edit --watch=false -> 195 passed (event-dialog.spec 'answering invitations': Accept/Maybe/Decline shown for writable GOOGLE/MICROSOFT events with guests, own status highlighted via mail account address, respond sends If-Match of occurrence version, new statuses + version used for next save, 412 conflict, 409 message)
- [x] New strings in English and German (`web/src/locale`)
  - verified (2026-11-08 23:33+0100 by dell26/agent-1): npm --prefix web run i18n:extract -> 6 new calendar.respond.* units in messages.xlf; German targets added in messages.de.xlf; npm --prefix web run check (incl. de build + messages-de.spec) -> pass
- [x] Unit tests cover the new behavior; `sh scripts/check.sh` passes
  - verified (2026-11-08 23:39+0100 by dell26/agent-1): event-dialog.spec + event-edit.service.spec cover answering (198 tests in calendar/edit pass); sh scripts/check.sh at c41803aed -> all checks passed

## Notes
- Work done on `cal-invitation-respond` before the split is on `origin/wip/cal-invitation-respond` (if it exists): merge it first if your part builds on it.
- ASSUMPTION (2026-11-08 23:25+0100 by dell26/agent-1): Answering shows a toast and keeps the dialog open (unsaved edits stay; ETag/version and guest statuses updated from the answer); closing afterwards returns 'saved' so the view refetches. 412 -> conflict banner with Reload; 409 and other errors -> the server's message inline. Choices are disabled during a conflict.
- done by dell26/agent-1: `sh scripts/check.sh` passed in 70s on 73ec03502; criteria verified 3/3
