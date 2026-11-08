# Code Review

Date: 2026-11-08

Task: cal-invitation-respond-android - Respond to invitations in the Android event editor

Scope: the task's implementation commit e4c18e794 on top of base d0368f222 (`git diff d0368f222 e4c18e794`), committed changes only. Tests that the test-writer is adding at the same time are not part of this scope.

Requirements:
- tasks/cal-invitation-respond-android.md (acceptance criteria)
- tasks/cal-invitation-respond.md (parent task's assumptions: show the actions for writable GOOGLE/MICROSOFT calendars with attendees; take the current status from the attendee whose address matches one of the user's mail account addresses; show the server's 409 as an error)
- SPEC.md §6.2 (responding to invitations for Google/Microsoft through the API)
- api/openapi.yaml `respondToInvitation` (POST /api/v1/calendar/events/{id}/respond) and server InvitationService/InvitationController
- AGENTS.md project notes (Android conventions, strings in values and values-de)

Git range:
- Branch: main
- HEAD: e4c18e794
- Base/start: d0368f222584f4231dd4e673147472c8562769c5
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 1 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Every refused answer says "you are not invited or you organize it", including a provider-side change that only needs a retry**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModel.kt:655` (mapping), `android/app/src/main/res/values/strings_calendar_edit.xml:140` (and the values-de equivalent)
    - Category: Correctness
    - Problem: `respond()` maps every `Failed` status other than 412 and 404 to `RESPOND_FAILED`. That covers 409, 400 and unreadable answers (`status == null`). The string for that message always says the user is not invited or organizes the event. According to the contract (openapi.yaml respondToInvitation 409, server `InvitationService.respond` lines 96-98 and 131-136), a 409 also means "the event was changed at the provider; the cockpit now shows the new version, please retry", "the calendar or event is not synced yet", and "not a Google/Microsoft calendar or read-only". For the remote-change case the client gives a wrong reason and offers no reload: `Message` only shows the reload action for `CONFLICT`. The user can only recover by trying again, which then fails with 412 and leads to `CONFLICT`.
    - Why it matters: a user whose invitation can be answered is told they cannot answer it, and is likely to give up. The parent assumption only covers the "organizer / not invited" meaning of 409, not the other ones listed in the contract.
    - Suggested fix: use a neutral failure text that mentions the likely causes and offers a reload. One option: treat `RESPOND_FAILED` like `CONFLICT` for the reload action, since a reload also picks up the version the server fetched. Keep the "not invited / organizer" wording as one possible cause, not the only one.

## P3 Low

- [ ] **CR-2 | The response section never appears if the calendars or mail accounts could not be loaded when the editor opened**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModel.kt:727-735`
    - Category: Correctness
    - Problem: `invitationCalendars` and `ownAddresses` are loaded once, in `loadSources()`, which runs only from `create`/`edit`. Failures are ignored. `reload()` / `load()` (the Retry action after `LOAD_OFFLINE`/`LOAD_FAILED`) does not load them again. If the user opens an invitation while offline and then taps Retry once back online, `invitationCalendars` is still `null`, so `respondable` is false and the Accept/Maybe/Decline section is missing until the editor is closed and opened again. In the same way, a failed `ownAddresses` load shows "You have not answered yet." even when the user has already answered.
    - Why it matters: the actions silently disappear and nothing indicates a problem or offers a fix.
    - Suggested fix: load the invitation sources again in `load()` when they are still missing (for example, when `invitationCalendars == null`), or make Retry also call `loadSources()`.

- [ ] **CR-3 | The mail accounts are fetched twice for the same editor**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventExtrasRepository.kt:128-130`
    - Category: Quality
    - Problem: `ownAddresses()` calls `listMailAccounts()` when the editor opens. The invitation prompt calls `listMailAccounts()` again through `mailAccounts()`. Both map the same response.
    - Why it matters: this is a small duplication of network calls and mapping. It does not affect behavior.
    - Suggested fix: optional. Derive `ownAddresses` from `mailAccounts()` (`mailAccounts().map { ... }`) so there is a single mapping. Or keep it as it is and accept the extra call.

## Notes (not findings)

- Acceptance criterion 1 is implemented. Accept/Maybe/Decline appear for existing events with guests in writable Google/Microsoft calendars. The current answer is selected (filled button, `selected` semantics) and named in a note. A successful answer updates the guests' statuses, the stored `version`, and `initial`/`savedAttendees`, so it does not create a spurious patch on the next save.
- The concurrency is handled well. Save/delete are blocked while an answer is being sent (`canSave` checks `responding`). Answering is blocked during load, save, an open invitation prompt, `CONFLICT` and `GONE`. `load()` and `reset()` cancel `respondJob` and clear `responding`. Every result path, including Offline and Failed, clears `responding`.
- The request matches the contract: the If-Match is sent quoted as `"<version>"` like the other calls, and the enum is mapped by name to `RespondToInvitationRequest.Response`. A non-matching name cannot reach the mapping because `respond()` checks `INVITATION_RESPONSES` first.
- Acceptance criterion 2 is met: all seven new strings exist in both values and values-de, and the German text uses "du" like the existing strings.
- Acceptance criterion 3: the commit only adds the two new stubs to `EventEditorViewModelExtrasTest`. Unit tests for `respond()`, `respondable`/`canRespond`/`ownStatus`, the new repository methods and `ResponseSection` still need to come from the parallel test-writer. As instructed, this is not reported as a finding, but the criterion cannot be considered verified until those tests exist and `sh scripts/check.sh` passes.

## Review conclusion

Material issues found; fix before completing the task. CR-1 should be fixed. CR-2 and CR-3 are optional.
