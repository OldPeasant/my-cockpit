# Code Review

Date: 2026-11-08

Task: and-cal-event-fields - Android events: reminders, attendees, invitations, time zone, links

Scope: committed changes eb07e0e35..1490f8a043e72fee7d8e1bb4ded35f4f200b5d9a (task commits 474eae4b6, 07d8a3f9c, bdff4e363, 1b2466dd1, 85fad8305, merged in 1490f8a04). This covers Android only: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit`, its tests, and `res/values{,-de}/strings_calendar_edit.xml`. Uncommitted changes: none (working tree clean). Reviewed revision: 1490f8a043e72fee7d8e1bb4ded35f4f200b5d9a.

Requirements:
- tasks/and-cal-event-fields.md (six acceptance criteria and the four recorded assumptions in Notes)
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions, docs/PARITY.md (task context)
- api/openapi.yaml: `POST /api/v1/calendar/events/{id}/invitations` (sendEventInvitations), `GET/POST /api/v1/links` (listLinks/createLink)
- Web reference: web/src/app/features/calendar/edit/event-dialog.ts, overlay/event-links.service.ts
- Server validation: server/src/main/kotlin/ch/sonensei/cockpit/calendar/api/CalendarEventService.kt:705-719 (attendee and reminder limits)

Git range:
- Branch: main
- HEAD: 1490f8a043e72fee7d8e1bb4ded35f4f200b5d9a
- Base/start: eb07e0e35
- Commits reviewed: 6 (5 task commits and 1 merge)

Commands run (read-only):
- `git log --oneline eb07e0e3..HEAD`, `git diff --stat eb07e0e3 HEAD` -> 16 files, +3640/-43
- `git diff eb07e0e3 HEAD -- CalendarWithEditor.kt EventEditRepository.kt EventEditorScreen.kt`
- Full read of EventEditorViewModel.kt, EventExtrasRepository.kt, EventEditorExtrasState.kt, EventForm.kt, EventEditorSections.kt and EventEditorScreen.kt at HEAD
- `git grep` of api/openapi.yaml for the invitations and links endpoints. The client request shapes match: method REQUEST/CANCEL, accountId, optional recipients; listLinks `type`/`id` query.
- `git grep` of the test names. EventEditorViewModelExtrasTest.kt has 36 tests, including:
    - the two links-race regression tests (`an older links answer arriving last does not hide a newly created task`, `a links reload while one is pending shows the newer answer`);
    - the late-answer tests for suggestions, accounts and a closed editor.
- CalendarEditStringsTest.kt has 4 tests, covering EN/DE presence, placeholders and real translations.
- I did not run Gradle in the reviewer session. The parent must run and record the criterion 6 commands itself.

Summary: 0 P0, 0 P1, 0 P2, 4 P3

Re-validation of the previous review (f4822f89): all four P3 findings are still in the final code, at the lines given below. The links-reload race fix (1b2466dd1) is correct:
- `loadLinks()` cancels `loadLinksJob` before it starts a new load (EventEditorViewModel.kt:614), so an older or failed answer cannot override a newer one.
- `reset()` also cancels the pending load (:306), so an answer cannot leak into the next editor session.
- `createLinked` uses a separate `linksJob`, so a running create is not cancelled by a reload.
- The reload that follows a create cancels any older load that is still pending.

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
    - Problem: `sendInvitation()` first sends the REQUEST/CANCEL to the guests, then the CANCEL to removed guests. If the first call succeeds and the second fails, the prompt returns to `failed = true` with unchanged `recipients`. Pressing Send again repeats both calls. The test `a failed send keeps the prompt for a retry, also when only the cancellation failed` does not check what a retry sends.
    - Why it matters: guests get the same iTIP mail twice.
    - Suggested fix: after the first call succeeds, record it in the prompt (for example, clear `recipients` or set a `requestSent` flag). A retry then sends only the part that is still missing.

- [ ] **CR-2 | The server's 20-reminder limit is not enforced in the editor**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorSections.kt:182-209`, `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventForm.kt:28`
    - Category: Correctness
    - Problem: the client checks each custom value against 0..`MAX_REMINDER_MINUTES`, but it does not limit how many reminders an event has. `CalendarEventService.kt:716` rejects more than `MAX_REMINDERS` (20) with a 400, and Android then shows only the generic "could not be saved" message.
    - Why it matters: the user cannot tell why the save failed (an unlikely case).
    - Suggested fix: add a `MAX_REMINDERS = 20` constant next to `MAX_REMINDER_MINUTES`, and hide or disable "Add a reminder" once the event has that many.

- [ ] **CR-3 | The "Save your changes first" note stays after it no longer applies**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModel.kt:513-515` (set), `:219-222` (`update`), `:399-412` (`load`)
    - Category: Quality
    - Problem: `invite()` sets `saveBeforeInviting`. Only a later successful `invite()` or `reset()` clears it. It stays visible after the user undoes the changes, after a failed save, and after `reload()`. `load()` also keeps `invitationSent`, `guestQuery` and `suggestions` from before the reload.
    - Why it matters: the screen can show a note about a state that no longer exists.
    - Suggested fix: clear `saveBeforeInviting` in `update()` and `load()`, and reset the guest and invitation fields in `load()`.

- [ ] **CR-4 | Date in the "Create task / note" title uses the event's time zone; the web uses the user's**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModel.kt:595`
    - Category: Integration
    - Problem: the title uses `initial.startDate`, which `editForm` computes in the event's own `timeZone` (`:700`). The web (`overlay/event-links.service.ts:45-48,96-98`) formats the occurrence start in the user's time zone. For a timed event in another zone near midnight, the two clients give different dates. The task assumption says Android uses "the web's title".
    - Why it matters: a small parity difference from the documented reference.
    - Suggested fix: format the start instant in the view model's `zone` (the user's setting) for timed events, or record the difference as an assumption.

## Review conclusion

No material issues found.

All six acceptance criteria are covered in code:
- Reminders: presets and a custom value of 0..40320 minutes, which can be added and removed.
- Guests: contact autocomplete (`autocompleteContacts`), add and remove, and participation status for each guest. Guests can be edited only in local calendars, as the task assumption says. After a save, the editor offers to send a REQUEST/CANCEL to the guests, plus a CANCEL with `recipients` to removed guests. A "Send invitations" button covers the saved event. Both match `SendInvitationsRequest` in the OpenAPI contract.
- An editable time zone, sent as `timeZone` with recalculated `start`/`end`.
- A read-only list of linked items (and-links-panel is not done). Create task (INBOX) and Create note each create the item, then link it to the event, as the web does.
- EN and DE strings, checked by `CalendarEditStringsTest`.
- Unit and Compose tests, including the race and late-answer cases.

Architecture: the new API access goes through `EventExtrasRepository` with the shared `calendarCall` mapping. No secrets or message bodies are logged. All code changes are under `calendar/edit` and its tests and string files, inside the task's `touches`.

The four findings above are optional cleanups. For criterion 6, the parent must still run `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` and record the results.
