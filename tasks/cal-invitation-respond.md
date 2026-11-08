---
title: Accept, decline or tentatively accept invitations (web + Android)
status: in_progress
owner: felix/agent-1
deps: [and-cal-event-fields]
touches: [web/src/app/features/calendar,android/app/src/main/java/ch/sonensei/cockpit/calendar/edit,android/app/src/test/java/ch/sonensei/cockpit/calendar/edit,android/app/src/main/res/values/strings_calendar_edit.xml,android/app/src/main/res/values-de/strings_calendar_edit.xml,web/src/locale]
prio: 2
lease_until: 2026-11-08T22:24:31Z
---
## Goal
SPEC.md §6.2 requires responding to invitations; the API exists (`POST /api/v1/calendar/events/{id}/respond`)
but neither client calls it.

## Acceptance criteria
- [ ] Web event dialog: for events where the user is an attendee, show Accept / Maybe / Decline with the current status; sending updates the status
- [ ] Android event view/editor: the same three actions
- [ ] Invitation mails in the mail read view (if the server marks them) offer the same actions on web and Android
- [ ] New strings in English and German on both clients (`web/src/locale`, `res/values{,-de}/`)
- [ ] Unit tests cover the new behavior on each changed part; `sh scripts/check.sh` passes

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
