# Code Review

Date: 2026-11-09

Task: cal-invitation-respond-android - Respond to invitations in the Android event editor

Scope: the whole task diff `git diff 924770e97 bf4361ac5 -- android`. This covers the original implementation, the fixes, and the merge with main that wired `respond` through `EventEditorCallbacks.of(editor)` and switched `gtd/today/TodayWithEditor.kt` to it.

Requirements:
- tasks/cal-invitation-respond-android.md (acceptance criteria, assumptions: same rules as web; the Today editor uses `EventEditorCallbacks.of`)
- SPEC.md §6.2 (Invitations: answering via API for Google and Microsoft) and §4.2 (If-Match/ETag)
- api/openapi.yaml `POST /api/v1/calendar/events/{id}/respond` (operationId respondToInvitation, optional If-Match, body RespondToInvitationRequest, 200 EventResponse)
- AGENTS.md project notes, docs/QUALITY.md

Git range:
- Branch: main
- HEAD: bf4361ac5 (reviewed revision). The clone has since moved to 700f0237a, a merge of main that does not touch any file in this task's diff (checked with `git diff --stat bf4361ac5 700f0237a` on the task paths).
- Base/start: 924770e973ebb0f112aea4b401645a50693c231f
- Commits reviewed: 13 task commits (a642512d0 .. bf4361ac5). Commits that touch only reviews/ or tasks/ were skipped.
- Uncommitted changes: `android/app/src/test/java/ch/sonensei/cockpit/gtd/today/TodayWithEditorTest.kt` was modified while this review ran. This is the parallel test-writer's work, so it was not reviewed.

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | No host-level test that the editor screens pass taps on Accept, Maybe or Decline to the view model**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorScreen.kt:107`
    - Category: Tests
    - Problem: When main was merged, `respond = editor::respond` was added to `EventEditorCallbacks.of(editor)`, and Today now uses `of()`. No committed test covers this connection. `EventEditorExtrasScreenTest` tests `ResponseSection` with a fake callback. `EventEditorViewModelRespondTest` calls `respond()` directly. `TodayWithEditorTest` and the calendar and dashboard host tests never tap an answer button. If the `respond = editor::respond` line were removed, the buttons would silently do nothing (the default is `{}`) and every unit test would still pass.
    - Why it matters: This connection is exactly what the merge changed, and the callback defaults to a no-op, so a regression would not be visible. The test-writer currently has an uncommitted edit to `TodayWithEditorTest.kt` that may cover this; check it once it is committed.
    - Suggested fix: Add one test that opens a respondable event in a host (Today, Calendar or Dashboard), taps `EventEditorTags.respond(ACCEPTED)`, and asserts that the repository's respond call was made. Alternatively, add a small unit test asserting that `EventEditorCallbacks.of(editor).respond` reaches `editor.respond`.

- [ ] **CR-2 | The `editorCallbacks` wrapper in TodayWithEditor is now redundant**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/today/TodayWithEditor.kt:59`
    - Category: Quality
    - Problem: `private fun editorCallbacks(editor: EventEditorViewModel) = EventEditorCallbacks.of(editor)` only forwards to `of()`. `CalendarWithEditor.kt:47` and `DashboardScreen.kt:201` call `EventEditorCallbacks.of(editor)` inline.
    - Why it matters: It is a small inconsistency between the three hosts of the same editor, and it adds one more step for a reader.
    - Suggested fix: Call `EventEditorCallbacks.of(editor)` inline at line 40 and delete the wrapper.

## Verified without findings

- API contract: `EventEditRepository.respond` calls `api.respondToInvitation(id, RespondToInvitationRequest(answer), "\"version\"")`. This matches the generated signature (path id, body, optional If-Match) and quotes the ETag the same way as patch and delete. The enum is mapped by name and protected by the `INVITATION_RESPONSES` guard in the view model.
- When the answers show (assumption #1): only for existing events that have guests and are in writable GOOGLE or MICROSOFT calendars (`EventExtrasRepository.invitationCalendars`). The own status comes from the guest that matches a mail account address, compared in lower case. DELEGATED, NEEDS_ACTION and no match all show as "not answered", with no button selected.
- Error mapping: 412 gives CONFLICT, which blocks further saves and answers until a reload. 404 gives GONE. Any other refusal (including 409) gives RESPOND_FAILED with a Reload action, and the user can try again. Offline gives OFFLINE and allows a retry.
- State and concurrency: `responding` blocks save, update, delete and a second answer, and `canRespond` blocks answering during a load or save. `load()` and `reset()` cancel `respondJob`, so a late answer never reaches a reloaded or different event. Every result path clears `responding`. On success, the new version and guests are applied to `initial`, the form and `savedAttendees`, and unsaved form edits are kept. `reload()` fetches the sources again only for existing events (it returns early for a new event).
- All three hosts (Calendar, Dashboard, Today) now use `EventEditorCallbacks.of`, so every one of them gets `respond`. No host builds its callback list by hand any more.
- Strings: the 7 `calendar_edit_respond_*` keys exist in both values/ and values-de/ (German uses "du"), and placeholders match.
- Security: there are no secrets or logging of personal data. Authorization stays on the server (it refuses non-invitees and organizers). The UI only decides whether to show the section.
- The task stays within its `touches`, except for the one-line Today change, which is recorded as an assumption on the task.
- Tests: the repository, view model and screen behaviour are covered, including edge cases (unknown or failed invitation calendars, retry after a failed source load, stale versions, a closed editor, a reload while an answer is being sent).

## Review conclusion

No material issues found.
