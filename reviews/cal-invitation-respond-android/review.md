# Code Review

Date: 2026-11-09

Task: cal-invitation-respond-android - Respond to invitations in the Android event editor

Scope: the whole task diff `git diff 924770e97 521f51e38 -- android`. This covers the original implementation, the fixes, the merge that wired `respond` through `EventEditorCallbacks.of(editor)` (also used by the Today editor), and the two host-level wiring tests the test-writer added at 521f51e38. The production code is the same as at bf4361ac5, which was reviewed before. The only android change since then is in two test files (`git diff bf4361ac5 521f51e38 -- android`), and this review looks at those closely.

Requirements:
- tasks/cal-invitation-respond-android.md (acceptance criteria, assumptions: same rules as web; the Today editor uses `EventEditorCallbacks.of`)
- SPEC.md §6.2 (Invitations: answering via API for Google and Microsoft) and §4.2 (If-Match/ETag)
- api/openapi.yaml `POST /api/v1/calendar/events/{id}/respond` (operationId respondToInvitation, optional If-Match, body RespondToInvitationRequest, 200 EventResponse)
- AGENTS.md project notes, docs/QUALITY.md

Git range:
- Branch: main
- HEAD: 521f51e38 (reviewed revision)
- Base/start: 924770e973ebb0f112aea4b401645a50693c231f
- Commits reviewed: 14 task commits (a642512d0 .. 521f51e38). Commits that touch only reviews/ or tasks/ were skipped. The merge 700f0237a does not touch any file in this task's diff.
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-2 | The `editorCallbacks` wrapper in TodayWithEditor is now redundant** (still open, not changed since the previous review)
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/today/TodayWithEditor.kt:59`
    - Category: Quality
    - Problem: `private fun editorCallbacks(editor: EventEditorViewModel) = EventEditorCallbacks.of(editor)` only forwards to `of()`. `CalendarWithEditor.kt` and `DashboardScreen.kt` call `EventEditorCallbacks.of(editor)` inline.
    - Why it matters: It is a small inconsistency between the three hosts of the same editor, and it adds one more step for a reader.
    - Suggested fix: Call `EventEditorCallbacks.of(editor)` inline at line 40 and delete the wrapper.

## Resolved since the previous review

- **CR-1 | No host-level test that the editor screens pass taps on Accept, Maybe or Decline to the view model**: resolved at 521f51e38.
    - `CalendarWithEditorTest` "an invitation opened from the calendar can be answered in the editor" and `TodayWithEditorTest` "an invitation opened from Today can be answered in the editor" each open a respondable event through the real host. The event is in a calendar listed by `invitationCalendars()`, has guests, and one guest matches `ownAddresses()`. The test then taps `EventEditorTags.respond(TENTATIVE)` or `respond(DECLINED)` and checks three things: `editRepo.respond(id, 2, answer)` ran exactly once with the loaded version, the view model's `ownStatus` changed, and the tapped button is selected.
    - Both tests drive the real `EventEditorCallbacks.of(editor)` path through `CalendarWithEditor` and `TodayWithEditor` (Today goes through its `editorCallbacks` wrapper). If `respond = editor::respond` were removed, the callback would fall back to its `{}` default, and `coVerify(exactly = 1)` would fail in both tests. So these tests guard the connection that CR-1 named.
    - The only change to the test helpers is that `show()` now takes an optional `editor` parameter. Its default is the old construction, so the other tests behave exactly as before. No test was weakened or removed. Test data is fictitious (example.org).
    - Ran: `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.CalendarWithEditorTest' --tests 'ch.sonensei.cockpit.gtd.today.TodayWithEditorTest'`. Result: CalendarWithEditorTest 7 tests, TodayWithEditorTest 3 tests, 0 failures, 0 errors, 0 skipped. Both new test cases are in the result XML.
    - Dashboard has no answer-tap test of its own. That is fine: it uses the same `EventEditorCallbacks.of(editor)` that both new tests cover, so it adds no wiring of its own to test.

## Verified without findings

These were checked in the previous review. The production code has not changed since, so they still hold.

- API contract: `EventEditRepository.respond` calls `api.respondToInvitation(id, RespondToInvitationRequest(answer), "\"version\"")`. This matches the generated signature (path id, body, optional If-Match) and quotes the ETag the same way as patch and delete. The enum is mapped by name and protected by the `INVITATION_RESPONSES` guard in the view model.
- When the answers show (assumption #1): only for existing events that have guests and are in writable GOOGLE or MICROSOFT calendars. The own status comes from the guest that matches a mail account address, compared in lower case. DELEGATED, NEEDS_ACTION and no match all show as "not answered", with no button selected.
- Error mapping: 412 gives CONFLICT, which blocks further saves and answers until a reload. 404 gives GONE. Any other refusal gives RESPOND_FAILED with a Reload action, and the user can try again. Offline gives OFFLINE and allows a retry.
- State and concurrency: `responding` blocks save, update, delete and a second answer. `load()` and `reset()` cancel `respondJob`, so a late answer never reaches a reloaded or different event. Every result path clears `responding`. On success, the new version and guests are applied, and unsaved form edits are kept.
- All three hosts (Calendar, Dashboard, Today) use `EventEditorCallbacks.of`, so every one of them gets `respond`.
- Strings: the `calendar_edit_respond_*` keys exist in both values/ and values-de/, and placeholders match.
- Security: there are no secrets or logging of personal data. Authorization stays on the server. The UI only decides whether to show the section.
- Scope: the task stays within its `touches`, except for the one-line Today change, which is recorded as an assumption on the task.

## Review conclusion

No material issues found.
