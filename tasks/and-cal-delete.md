---
title: Android calendar: delete events (incl. repeating series scope)
status: todo
owner:
deps: [and-cal-edit]
touches: [android/app/src/main/java/ch/sonensei/cockpit/calendar/edit,android/app/src/test/java/ch/sonensei/cockpit/calendar,android/app/src/main/res/values/strings_calendar_edit.xml,android/app/src/main/res/values-de/strings_calendar_edit.xml]
prio: 2
---
## Goal
On Android an event can be created and edited (and-cal-edit) but not deleted; the owner had to go to the web. The
server supports it: `DELETE /api/v1/calendar/events/{id}` with `scope` (THIS / FOLLOWING / ALL), `occurrence` for a
series occurrence, and `If-Match`; events of synced calendars are deleted at the provider. The web editor
(`web/src/app/features/calendar/edit`, scope prompt in `scope-dialog.ts`) is the reference. Server unchanged.

## Acceptance criteria
- [ ] The event editor (`calendar/edit/EventEditorScreen`) shows a Delete action for an existing event in a
      calendar that can be changed; not for new events, read-only calendars (ICS feeds, birthdays) or while saving
- [ ] A single event: a confirmation ("Delete this event?") then `DELETE` with `If-Match`; on success the editor
      closes and the agenda/day/week views no longer show the event (cache updated, no manual refresh)
- [ ] A repeating event: the existing scope choice (only this / this and following / all events in the series),
      sent as `scope` with the occurrence's original start as `occurrence`, as the web does; cancelling the
      choice deletes nothing
- [ ] Errors use the editor's existing patterns: 412 -> the "changed elsewhere" message with Reload, 404 -> the
      "changed or removed elsewhere" message, offline -> "Can't reach the server", nothing is removed locally then
- [ ] All new UI strings exist in English and German (`res/values`, `res/values-de`)
- [ ] ViewModel tests (single, each scope, cancel, 412, 404, offline) and a Compose test for the button and
      confirmation; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes

## Notes
Requested by the owner (2026-11-02): "How can I delete a calendar entry in the Android App?" Out of scope: deleting
from the agenda list by swipe, undo after delete (the web has none for events either, unless it does: then match it).
