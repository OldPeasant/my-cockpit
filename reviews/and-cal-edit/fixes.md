# Fix Report

Date: 2026-10-21

Task: and-cal-edit - Android event create/edit

Scope: current task. Findings CR-1, CR-2 and CR-3 from `reviews/and-cal-edit/review.md` (reviewed HEAD c127c7c9, base 6db1d0fd), as assigned by the parent worker. Second run: finding CR-5 from the re-review of `reviews/and-cal-edit/review.md` (reviewed HEAD 4ef0c96d).

## Fixed findings

### CR-1 — A generated occurrence is not read again on open, so stale times are edited under a fresh If-Match

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditRepository.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorScreen.kt`
- `android/app/src/main/res/values/strings_calendar_edit.xml`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModelEdgeCasesTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorScreenTest.kt`

Problem:
For a generated occurrence the editor reloaded the series master (fresh If-Match) but took the times from the occurrence the view had shown. A series moved elsewhere, or an occurrence that had become an override or been removed, was edited with stale values. A save answered with 404 showed the generic FAILED message.

Fix:
Same approach as the web (`event-edit.service.ts` `reread`, `event-dialog.ts` `load`). `EventEditRepository.reread(occurrence)` calls `CalendarsApi.listEventOccurrences(from, to, calendarIds = listOf(calendarId))`. The range comes from `rangeAround`: ±1 day for an instant, day-1 .. day+2 for an all-day date, in UTC. It maps the items with the agenda's `toOccurrence()` and returns the item with the same `seriesId` and `occurrence`, or `null` when there is none or it is cancelled. `EventEditorViewModel.load()` re-reads a generated occurrence first and stores the fresh occurrence as the target for the load and the PATCH. If the fresh occurrence is now an override, that event and its master are loaded as usual. A missing occurrence shows the new `EditorMessage.GONE` ("This event was changed or removed elsewhere. Close the editor to see the current calendar.") with no form and no reload. A failed re-read shows LOAD_OFFLINE/LOAD_FAILED. A 404 answer to a save now maps to GONE, which blocks saving like CONFLICT does.

Validation:
- New tests: VM (moved occurrence is patched as re-read; it became an override, so the override is loaded and patched with its own version; gone shows GONE with no form; re-read failure; save 404 shows GONE), repository (re-read request path, calendarIds, range, match by series and occurrence, gone/cancelled/5xx, all-day range), screen (GONE message).
- Gradle not runnable in this sub-agent session (Bash denied). The parent worker runs `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`. Result: Not run here.

### CR-2 — Closing or going back while a save runs cancels it silently

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorScreen.kt`
- `android/app/src/main/res/values/strings_calendar_edit.xml`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModelEdgeCasesTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorScreenTest.kt`

Problem:
`close()` cancelled a running save and reset the state, including the new event's id. The server may already have committed the change, so a retry could create a duplicate. The OFFLINE message said "Nothing was saved", but OFFLINE also covers 5xx answers and failures after the request was sent.

Fix:
`close()` does nothing while `saving` is true. A successful save resets the editor through a private `reset()`. The BackHandler stays enabled but does nothing while saving, so back doesn't leave the Calendar screen and cancel the request through the ViewModel. The close icon is disabled while saving. `calendar_edit_offline` now says "Can't reach the server. Try again.", and the `EditorMessage.OFFLINE` KDoc says a retry is harmless.

Changed test:
The test-writer's EdgeCases test "closing while a save is running drops its answer" asserted the behaviour CR-2 identifies as the bug. It was replaced by "closing while a save is running is ignored: the answer arrives and a retry keeps the id", plus "closing after a failed save closes the editor". The screen test "no connection while saving says nothing was saved" now checks the new wording and that "Nothing was saved" is gone.

Validation:
- New/updated tests: EdgeCases (close during saving is ignored, retry reuses the id; close after a failure closes), screen (close disabled while saving, enabled again afterwards; new OFFLINE text).
- Gradle not run here (see CR-1). Result: Not run here.

### CR-3 — The custom repeat choice disappears once another preset is picked

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventForm.kt` (KDoc only)
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorScreenTest.kt`

Problem:
`repeatChoices` offered `Repeat.CUSTOM` only while `form.repeat == CUSTOM`.

Fix:
`EventForm.rule` holds the rule the event was loaded with and is never changed by the UI, which only changes `repeat`. `repeatChoices` now offers CUSTOM when `repeatOf(form.rule) == Repeat.CUSTOM`, that is, when the loaded rule was custom, whatever is picked now. A preset rule still gives no custom choice. The KDoc of `EventForm.rule` says so.

Validation:
- New tests: VM (custom choice still offered after picking DAILY; choosing CUSTOM again restores the rule and sends nothing; a preset rule offers no custom choice), screen (CUSTOM can be re-selected after DAILY and shows the original rule).
- Gradle not run here (see CR-1). Result: Not run here.

### CR-5 — A refused create (409) shows "changed elsewhere" with a Reload button that does nothing

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModelEdgeCasesTest.kt`

Problem:
`send()` mapped 409/412 to CONFLICT and 404 to GONE for creates too. For a new event, Reload does nothing (`load()` returns at once when there is no occurrence), and CONFLICT/GONE make `canSave` false, so `update()` ignored edits. The user could not pick another calendar or retry. Closing the editor lost the form.

Fix:
`send()` records whether the request is a create (`occurrence == null`) before it starts. When a create fails, the result is always `EditorMessage.FAILED` ("The event could not be saved."), whatever the status. The form stays editable and Save stays enabled. Edits are unchanged: 409/412 still give CONFLICT and 404 still gives GONE.

Is a fresh id needed? Yes, for a 409 only. `CalendarEventService.create` calls `existingCreate(id)` before it checks that the calendar is writable. So a 409 means either the id belongs to an event in the Trash (a retry with the same id is refused every time) or no event with that id exists and the calendar is read-only. In both cases no live event has the old id, so a new UUIDv7 can't create a duplicate. The VM therefore generates a new `newId` only after a create answered with 409. OFFLINE and every other failure keep the id, so an idempotent retry still works. 412 can't happen on a create (POST sends no If-Match). It is mapped to FAILED with the same id. The `newId` KDoc says this.

Validation:
- New VM tests in `EventEditorViewModelEdgeCasesTest`: "a refused create (409) is a failure, the form stays editable and the retry uses a new id" (FAILED, `canSave`, another calendar can be picked and is sent, the second body has a different `id`); "other refused creates (412, 404, 422) are failures and the retry keeps the id" (FAILED and `canSave` each time, one id across all 4 requests). The existing tests "a 409 is a conflict too", "a conflict blocks saving until the event is reloaded" (412) and "a save answered with 404 says the event is gone" still pass unchanged, so edits keep their behaviour. The existing OFFLINE retry test ("closing while a save is running is ignored ... a retry keeps the id") still passes.
- No screen test was added: the screen only renders the state, and "a failed save says so and offers no reload, saving stays possible" in `EventEditorScreenTest` already covers FAILED.
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.*'` — Pass (85 tests, 0 failures: EdgeCases 20, ViewModel 17, Screen 17, Form 17, Repository 11, CalendarWithEditor 3).
- `cd android && ./gradlew ktlintCheck` — Pass (BUILD SUCCESSFUL).

## Not fixed

### CR-4 — `editForm` paths for all-day occurrences and overrides have no tests

Status:
Out of scope

Reason:
The parent worker did not assign it to this fixer run. The test-writer's uncommitted tests already cover part of it (all-day generated occurrence, override with master, master load failure, unknown zone in `EventEditorViewModelEdgeCasesTest`). The new override re-read test in `EventEditorViewModelTest` adds coverage. The parent decides on the rest.

## Validation summary

Focused tests:
- First run (CR-1..3): not run, because Bash/Gradle was denied in that sub-agent session. The parent worker ran them afterwards.
- Second run (CR-5): `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.*'` — Pass (85 tests, 0 failures, 0 skipped).

Full test suite:
- Not run in this fixer session. The parent should run `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`.

Lint/type-check/build:
- `cd android && ./gradlew ktlintCheck` — Pass. `lintDebug` was not run.

`.taskcheck`:
- Not run (left to the parent worker).

Remaining unresolved findings:
1 (CR-4, not assigned to the first run. The re-review says tests now cover it.)

## Final status

All findings assigned to this fixer (CR-1, CR-2, CR-3, CR-5) are fixed. The focused CR-5 validation (ktlint and the calendar edit unit tests) passed. The parent worker still runs the full Android suite, `lintDebug` and `.taskcheck`.
