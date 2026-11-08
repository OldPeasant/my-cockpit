# Code Review

Date: 2026-11-08

Task: and-cal-event-fields - Android events: reminders, attendees, invitations, time zone, links

Scope: committed changes 9eb96efcb0dee38e68903e74ddb584c78e0adc9d..f4822f8982ae5f21f75865a81a690eca769201ae, Android only (`android/app/src/main/java/ch/sonensei/cockpit/calendar/edit`, its tests, `res/values{,-de}/strings_calendar_edit.xml`). Uncommitted changes: none (working tree clean).

Requirements:
- tasks/and-cal-event-fields.md (acceptance criteria and the four recorded assumptions in Notes)
- SPEC.md §6.2/§6.3 (as referenced by the code), docs/ARCHITECTURE.md, AGENTS.md project notes, docs/QUALITY.md
- api/openapi.yaml: `POST /api/v1/calendar/events/{id}/invitations` (SendInvitationsRequest/Response), `PATCH /api/v1/calendar/events/{id}`, `GET/POST /api/v1/links`, AttendeeDto, `reminderMinutes`
- Web reference: web/src/app/features/calendar/edit/event-dialog.ts, overlay/event-links.service.ts
- Server validation: server/.../calendar/api/CalendarEventService.kt (reminder limits)

Git range:
- Branch: main
- HEAD: f4822f8982ae5f21f75865a81a690eca769201ae
- Base/start: 9eb96efcb0dee38e68903e74ddb584c78e0adc9d
- Commits reviewed: 4 (8606787b, e7fb9869, bda8f22c, f4822f89)

Commands run (read-only):
- `git log --oneline 9eb96efc..HEAD`, `git diff --stat 9eb96efc..HEAD`, `git diff 9eb96efc..HEAD -- <files>` -> 15 files, +3444/-43
- Full read of EventEditorViewModel.kt, EventExtrasRepository.kt, EventEditorExtrasState.kt, EventForm.kt, EventEditRepository.kt, EventEditorSections.kt, the EventEditorScreen.kt diff, both string files
- `grep` of api/openapi.yaml for the invitation, PATCH, links, AttendeeDto and reminderMinutes contracts -> request/response shapes match the client
- `git grep` for DI providers of CalendarsApi/ContactsApi/MailAccountsApi/TasksApi/NotesApi -> all provided; EventLinksApi follows the existing ContactLinksApi pattern
- `python tasks.py verify and-cal-event-fields` -> 6 criteria, none recorded yet (expected: the parent records evidence after the review)
- Running Gradle was not permitted in the reviewer sandbox. Existing results in android/app/build/test-results (2026-11-08T13:46Z, after f4822f89): EventEditorViewModelExtrasTest 31 tests / 0 failures (including both links-race tests), EventEditorExtrasScreenTest 19 tests / 0 failures. These are not reviewer-run evidence; the parent must run the criterion 6 commands itself.

Summary: 0 P0, 0 P1, 0 P2, 4 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Retry after a partial invitation failure resends the REQUEST already delivered**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModel.kt:560-571`
    - Category: Correctness
    - Problem: `sendInvitation()` sends the REQUEST/CANCEL to the guests first, then the CANCEL to removed guests. When the first call succeeds and the second fails, the prompt goes back to `failed = true` with the same state. Pressing Send again repeats both calls, so the guests get the invitation mail a second time. The test "a failed send keeps the prompt for a retry, also when only the cancellation failed" covers this path but does not assert what a retry sends.
    - Why it matters: guests get duplicate iTIP mails. The KDoc on `failed` admits "only part was sent", but nothing stops the part that already went out from being sent again.
    - Suggested fix: record which part succeeded (for example, clear `recipients` in the prompt or keep a `requestSent` flag after the first call succeeds) so a retry sends only what is left.

- [ ] **CR-2 | The 20-reminder server limit is not enforced in the editor**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorSections.kt:199-218` (custom add), `EventForm.kt:343`
    - Category: Correctness
    - Problem: the client checks each custom value against 0..40320, matching the server's `MAX_REMINDER_MINUTES`. It has no limit on the number of reminders, but `CalendarEventService.kt:716` rejects more than `MAX_REMINDERS = 20` with a 400. On Android that shows only the generic "The event could not be saved."
    - Why it matters: the user cannot tell why the save failed. This is unlikely in normal use.
    - Suggested fix: hide or disable "Add a reminder" once 20 reminders exist (add a constant next to `MAX_REMINDER_MINUTES`).

- [ ] **CR-3 | "Save your changes first" note stays after it no longer applies**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModel.kt:513-515`, `:399-412`
    - Category: Quality
    - Problem: `saveBeforeInviting` is set by `invite()` and cleared only by a later successful `invite()` or by `reset()`. It stays visible after the user undoes the changes, after a failed save, and after `reload()`, because `load()` does not clear it (or `invitationSent`, `guestQuery`, `suggestions`).
    - Why it matters: an error note can describe a state that no longer exists.
    - Suggested fix: clear `saveBeforeInviting` in `update()` and `load()`, and reset the guest and invitation fields in `load()`.

- [ ] **CR-4 | Date in the "Create task / note" title uses the event's zone, the web uses the user's zone**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModel.kt:595`
    - Category: Integration
    - Problem: the title is built from `initial.startDate`, which `editForm` computes in the event's own `timeZone`. The web (`event-links.service.ts:45-48`, `occurrenceDate(occurrence, timeZone)`) uses the user's time zone. Also, for a generated occurrence the web uses the occurrence date, while Android uses the date of the loaded form. These match too, because `editForm` takes the occurrence's times. For a timed event planned in another zone near midnight, the two clients produce titles with different dates. The task assumption says Android uses "the web's title".
    - Why it matters: this is a small parity difference with a documented reference implementation.
    - Suggested fix: format the start instant in the user's zone (`zone` in the view model), or record the difference as an assumption.

## Review conclusion

No material issues found.

All six acceptance criteria are addressed in code:
- reminders (presets and a custom 0..40320 value);
- guests (contact autocomplete, add/remove, participation status, local calendars only), with the after-save prompt and the "Send invitations" button using `SendInvitationsRequest` REQUEST/CANCEL and `recipients` for removed guests, as in the OpenAPI contract;
- an editable time zone (sent as `timeZone` with recomputed `start`/`end`);
- a read-only linked items list, justified because and-links-panel is still `todo`, plus create task/note with link;
- complete EN/DE strings (same keys, plurals in both);
- Compose and unit tests.

The links-reload race is fixed by cancelling the pending load (`loadLinksJob`), and the two regression tests for it exist. The findings above are optional cleanups. Criterion 6 still needs the parent to run `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` and record the results; the reviewer could not run Gradle here.
