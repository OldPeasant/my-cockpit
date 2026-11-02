---
title: Android calendar: delete events (incl. repeating series scope)
status: in_progress
owner: dell26/agent-1
deps: [and-cal-edit]
touches: [android/app/src/main/java/ch/sonensei/cockpit/calendar/edit,android/app/src/test/java/ch/sonensei/cockpit/calendar,android/app/src/main/res/values/strings_calendar_edit.xml,android/app/src/main/res/values-de/strings_calendar_edit.xml]
prio: 2
lease_until: 2026-11-02T10:13:20Z
---
## Goal
On Android an event can be created and edited (and-cal-edit) but not deleted; the owner had to go to the web. The
server supports it: `DELETE /api/v1/calendar/events/{id}` with `scope` (THIS / FOLLOWING / ALL), `occurrence` for a
series occurrence, and `If-Match`; events of synced calendars are deleted at the provider. The web editor
(`web/src/app/features/calendar/edit`, scope prompt in `scope-dialog.ts`) is the reference. Server unchanged.

## Acceptance criteria
- [x] The event editor (`calendar/edit/EventEditorScreen`) shows a Delete action for an existing event in a
      calendar that can be changed; not for new events, read-only calendars (ICS feeds, birthdays) or while saving
  - verified (2026-11-02 10:56+0100 by dell26/agent-1): EventEditorDeleteScreenTest (Robolectric Compose, ./gradlew testDebugUnitTest) -> Delete shown+enabled for existing event; absent for new, read-only calendar, loading, saving (7/7 pass); VM test new/read-only/running delete -> canDelete false
- [x] A single event: a confirmation ("Delete this event?") then `DELETE` with `If-Match`; on success the editor
      closes and the agenda/day/week views no longer show the event (cache updated, no manual refresh)
  - verified (2026-11-02 10:56+0100 by dell26/agent-1): EventEditorViewModelDeleteTest single: confirm then repo.delete(single, 4, null), editor closes; EventEditRepositoryTest (MockWebServer): DELETE /api/v1/calendar/events/{id}, If-Match "4", no scope; CalendarWithEditorDeleteTest (real CalendarWithEditor + view models): after delete+confirm event gone from agenda, week, day and agenda cache without manual refresh -> all pass (cd android && ./gradlew testDebugUnitTest)
- [x] A repeating event: the existing scope choice (only this / this and following / all events in the series),
      sent as `scope` with the occurrence's original start as `occurrence`, as the web does; cancelling the
      choice deletes nothing
  - verified (2026-11-02 10:56+0100 by dell26/agent-1): EventEditorViewModelDeleteTest: series occurrence offers THIS/FOLLOWING/ALL, each scope -> repo.delete(weekly, 7, scope); cancel -> no delete; EventEditRepositoryTest: generated -> master id, scope=FOLLOWING, occurrence=2026-10-23T07:00:00Z; override -> own id, scope THIS, no occurrence -> pass (./gradlew testDebugUnitTest)
- [x] Errors use the editor's existing patterns: 412 -> the "changed elsewhere" message with Reload, 404 -> the
      "changed or removed elsewhere" message, offline -> "Can't reach the server", nothing is removed locally then
  - verified (2026-11-02 10:56+0100 by dell26/agent-1): EventEditorViewModelDeleteTest: 412 -> CONFLICT + reload re-enables, 404 -> GONE, Offline -> OFFLINE with form kept; CalendarWithEditorDeleteTest offline: agenda + cache still hold the event; repository maps 412/404 to Failed, 503 to Offline -> pass (./gradlew testDebugUnitTest)
- [x] All new UI strings exist in English and German (`res/values`, `res/values-de`)
  - verified (2026-11-02 10:56+0100 by dell26/agent-1): 4 new keys in res/values and res/values-de strings_calendar_edit.xml; EventEditorDeleteScreenTest @Config(qualifiers=de) shows 'Diesen Termin löschen?', 'Löschen', 'Wiederkehrenden Termin löschen'; ./gradlew lintDebug (MissingTranslation) -> BUILD SUCCESSFUL
- [ ] ViewModel tests (single, each scope, cancel, 412, 404, offline) and a Compose test for the button and
      confirmation; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes

## Notes
Requested by the owner (2026-11-02): "How can I delete a calendar entry in the Android App?" Out of scope: deleting
from the agenda list by swipe, undo after delete (the web has none for events either, unless it does: then match it).
- ASSUMPTION (2026-11-02 10:49+0100 by dell26/agent-1): Delete is a red text button in the editor's top bar (next to Save), hidden for new events, read-only calendars, while loading and while saving; shown but disabled after a 412/404 message (reload first), as Save is.
- ASSUMPTION (2026-11-02 10:50+0100 by dell26/agent-1): A delete refused for another reason (e.g. 400/409 read-only) shows a new message 'The event could not be deleted.' instead of the save wording; 409 maps to the conflict message like a save. The web has no undo for event deletes, so none here.
