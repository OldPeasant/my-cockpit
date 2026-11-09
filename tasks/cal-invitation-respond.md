---
title: Accept, decline or tentatively accept invitations (web + Android)
status: in_progress
owner: felix/agent-1
deps: [and-cal-event-fields, cal-invitation-respond-web, cal-invitation-respond-android]
touches: [web/src/app/features/calendar,android/app/src/main/java/ch/sonensei/cockpit/calendar/edit,android/app/src/test/java/ch/sonensei/cockpit/calendar/edit,android/app/src/main/res/values/strings_calendar_edit.xml,android/app/src/main/res/values-de/strings_calendar_edit.xml,web/src/locale]
prio: 2
lease_until: 2026-11-09T03:27:31Z
subtasks: cal-invitation-respond-web, cal-invitation-respond-android
releases: 0
extended: 
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
- ASSUMPTION (2026-11-08 23:05+0100 by felix/agent-1): EventResponse has no 'self attendee' flag: clients show Accept/Maybe/Decline for events of GOOGLE/MICROSOFT calendars that are not read-only and have attendees; the current status is the attendee whose email matches one of the user's mail account addresses (none -> no status highlighted). Server answers 409 when the user organizes/is not invited; the client shows that error. No server change.
- ASSUMPTION (2026-11-08 23:05+0100 by felix/agent-1): Criterion 3 (invitation mails): the server does not mark invitation mails (no iTIP/text/calendar flag in the mail API), so per 'if the server marks them' no mail-view actions are built; a server task would be needed first.
- split by felix/agent-1: into cal-invitation-respond-web, cal-invitation-respond-android. Analysis done, no code yet: API has no self-attendee flag and no invitation-mail marker; decisions recorded as assumptions. Split into web and Android subtasks; the original then only integrates/verifies (criterion 3 is N/A per assumption). Once they are done, this task is only integration: merge what is left on wip/cal-invitation-respond (if anything), verify the acceptance criteria end to end, done.
