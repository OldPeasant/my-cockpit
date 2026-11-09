---
title: Accept, decline or tentatively accept invitations (web + Android)
status: in_progress
owner: felix/agent-1
deps: [and-cal-event-fields, cal-invitation-respond-web, cal-invitation-respond-android]
touches: [web/src/app/features/calendar,android/app/src/main/java/ch/sonensei/cockpit/calendar/edit,android/app/src/test/java/ch/sonensei/cockpit/calendar/edit,android/app/src/main/res/values/strings_calendar_edit.xml,android/app/src/main/res/values-de/strings_calendar_edit.xml,web/src/locale]
prio: 2
lease_until: 2026-11-09T03:35:54Z
subtasks: cal-invitation-respond-web, cal-invitation-respond-android
releases: 0
extended: 
---
## Goal
SPEC.md §6.2 requires responding to invitations; the API exists (`POST /api/v1/calendar/events/{id}/respond`)
but neither client calls it.

## Acceptance criteria
- [x] Web event dialog: for events where the user is an attendee, show Accept / Maybe / Decline with the current status; sending updates the status
  - verified (2026-11-09 04:10+0100 by felix/agent-1): Integrated via done subtask cal-invitation-respond-web (event-dialog respond UI + event-edit.service respondToInvitation). cd web && npx ng test --watch=false --include src/app/features/calendar/edit -> 8 files, 198 tests passed (incl. event-dialog/event-edit.service respond specs)
- [x] Android event view/editor: the same three actions
  - verified (2026-11-09 04:10+0100 by felix/agent-1): Integrated via done subtask cal-invitation-respond-android (EventEditor respond actions, EventEditRepository). cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.*' -> pass, EventEditorViewModelRespondTest 15/15, 0 failures
- [x] Invitation mails in the mail read view (if the server marks them) offer the same actions on web and Android
  - verified (2026-11-09 04:10+0100 by felix/agent-1): grep -i 'text/calendar|invitation' api/openapi.yaml -> no mail-side invitation marker (only calendar respond/invitations endpoints); per criterion 'if the server marks them' and recorded assumption, no mail-view actions apply (N/A)
- [x] New strings in English and German on both clients (`web/src/locale`, `res/values{,-de}/`)
  - verified (2026-11-09 04:10+0100 by felix/agent-1): web: calendar.respond.* in web/src/locale/messages.xlf + 6 in messages.de.xlf; cd web && npx ng test --watch=false --include src/locale -> 10 passed. android: calendar_edit_respond_* in res/values{,-de}/strings_calendar_edit.xml (Zusagen/Vielleicht/Absagen), CalendarEditStringsTest passed in calendar.edit run
- [ ] Unit tests cover the new behavior on each changed part; `sh scripts/check.sh` passes

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 23:05+0100 by felix/agent-1): EventResponse has no 'self attendee' flag: clients show Accept/Maybe/Decline for events of GOOGLE/MICROSOFT calendars that are not read-only and have attendees; the current status is the attendee whose email matches one of the user's mail account addresses (none -> no status highlighted). Server answers 409 when the user organizes/is not invited; the client shows that error. No server change.
- ASSUMPTION (2026-11-08 23:05+0100 by felix/agent-1): Criterion 3 (invitation mails): the server does not mark invitation mails (no iTIP/text/calendar flag in the mail API), so per 'if the server marks them' no mail-view actions are built; a server task would be needed first.
- split by felix/agent-1: into cal-invitation-respond-web, cal-invitation-respond-android. Analysis done, no code yet: API has no self-attendee flag and no invitation-mail marker; decisions recorded as assumptions. Split into web and Android subtasks; the original then only integrates/verifies (criterion 3 is N/A per assumption). Once they are done, this task is only integration: merge what is left on wip/cal-invitation-respond (if anything), verify the acceptance criteria end to end, done.
