# Fix Report

Date: 2026-11-08

Task: cal-invitation-respond-android (answer calendar invitations in the Android event editor)

Scope: current task, approved findings CR-1, CR-2 and CR-3 from `reviews/cal-invitation-respond-android/review.md`

## Fixed findings

### CR-1 — A refused answer always says "not invited or organizer"

Files changed:
- `android/app/src/main/res/values/strings_calendar_edit.xml`
- `android/app/src/main/res/values-de/strings_calendar_edit.xml`
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorExtrasScreenTest.kt`

Problem:
`RESPOND_FAILED` covers every 409/400/unreadable reply. The server also sends 409 when the event changed at the provider, is not synced yet, or is in a read-only calendar. Even so, the text named "not invited or organizer" as the only reason, and the message had no reload action.

Fix:
The text is now neutral: "The answer could not be sent. Reload the event and try again, or check that you are invited and not the organizer." The German text uses "du": "Die Antwort konnte nicht gesendet werden. Lade den Termin neu und versuche es nochmals, oder prüfe, ob du eingeladen bist und ihn nicht organisierst." `Message` now shows the Reload action for `RESPOND_FAILED` as well as for `CONFLICT`. The screen test checks the new text, the Reload button, and that tapping it calls `reload`. The answer buttons still work for another try.

Validation:
- `cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.*'`: Pass

### CR-2 — The answer section never appears if the sources failed to load when the editor opened

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModelRespondTest.kt`

Problem:
`invitationCalendars` and `ownAddresses` were loaded only once, in `loadSources()` from `create`/`edit`. Retry and Reload (`reload()`) did not fetch them again.

Fix:
`reload()` now calls `loadSources()` before `load()` (it does nothing when no event is being edited). A Retry after an offline open therefore loads the invitation calendars and own addresses again. New test: "a retry after the sources failed to load fetches them again, so the invitation can be answered". The first load returns Offline, so `respondable` is false. After `reload()`, `respondable` is true and `ownStatus` is known.

Validation:
- `cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.*'`: Pass

## Not fixed

### CR-3 — The mail accounts are fetched twice for the same editor

Status:
Out of scope (optional)

Reason:
The reviewer and the parent marked this as optional: it does not change behavior. Changing `EventExtrasRepository.ownAddresses()` would also mean changing the repository tests, which does not fit this fix pass. I skipped it on the parent's instruction ("skip unless trivial").

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.*'`: Pass. The test-results XML for `calendar.edit.*` shows 0 failures and 0 errors. Both changed test classes ran.

Full test suite:
- Not run (time budget). The parent runs `.taskcheck`.

Lint/type-check/build:
- ktlintCheck and lintDebug: Pass (part of the command above)

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
1 (CR-3, optional, skipped)

## Final status

All validated findings fixed and validation passed. The optional CR-3 was skipped.
