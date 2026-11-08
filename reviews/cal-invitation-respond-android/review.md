# Code Review

Date: 2026-11-08

Task: cal-invitation-respond-android - Respond to invitations in the Android event editor

Scope: final task code at HEAD, `git diff 0cec3b4bab909270b22a0e32e7965555e46f97b5..f33de8d22e295cb81dd038ba46d6a6f38f934957 -- android/` (implementation fe383eefd, tests cadbdb450, fixes 562f6bc93; 3acbe299e only adds the earlier review report). Committed changes only. This is a re-review after the fixes for CR-1 and CR-2 of the earlier review.

Requirements:
- tasks/cal-invitation-respond-android.md (acceptance criteria and the recorded assumption: same rules as web; 412 -> conflict, 404 -> gone, other failures -> "could not be sent"; unsaved form edits kept after answering)
- SPEC.md §6.2 (answering Google/Microsoft invitations through `POST /api/v1/calendar/events/{id}/respond`)
- reviews/cal-invitation-respond-android/fixes.md (claimed fixes for CR-1 and CR-2; CR-3 skipped)
- AGENTS.md project notes (strings in values and values-de, Android conventions)

Git range:
- Branch: main
- HEAD: f33de8d22e295cb81dd038ba46d6a6f38f934957
- Base/start: 0cec3b4bab909270b22a0e32e7965555e46f97b5
- Commits reviewed: 4 (3 change android/)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Comments still describe every non-412 refusal as "not invited or organizer"**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModel.kt:48` (`RESPOND_FAILED` KDoc) and `:753` (`STALE` KDoc)
    - Category: Quality
    - Problem: the earlier CR-1 fix made the user-facing text neutral and added Reload, because a 409 can also mean "changed at the provider, retry", "not synced yet" or "read-only". The KDoc of `STALE` still says "a 409 to an answer means the user can't answer (not invited, or organizer)". The KDoc of `RESPOND_FAILED` gives only that example too.
    - Why it matters: the comment states the wrong meaning of 409 that the fix just corrected. A later change could rely on it, for example by removing the Reload action again.
    - Suggested fix: reword both comments. For example: "409/400: refused (not invited, organizer, changed at the provider, not synced, read-only); a reload and another try may help."

- [ ] **CR-2 | The mail accounts are still fetched twice (earlier CR-3, accepted as optional)**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventExtrasRepository.kt:128-130`
    - Category: Quality
    - Problem: `ownAddresses()` and `mailAccounts()` both call `listMailAccounts()` and map the same response. One call happens when the editor opens, the other in the invitation prompt. This is unchanged since the earlier review. The fixer skipped it as optional.
    - Why it matters: one redundant network call and a duplicated mapping. Behavior is not affected.
    - Suggested fix: optional. Derive `ownAddresses` from `mailAccounts()`, or record the finding as accepted in quality.json, as planned.

## Verification of the earlier findings

- Earlier CR-1 (misleading refusal text, no reload): fixed. The `calendar_edit_respond_failed` text is now neutral in both `values` and `values-de` (the German uses "du", like the other strings). `Message` (`EventEditorScreen.kt:250`) shows Reload for `RESPOND_FAILED` as well as `CONFLICT`. Reload only drops unsaved edits when the user taps it, so the assumption that edits are kept after answering still holds. The answer buttons stay enabled for another try, because `canRespond` does not block `RESPOND_FAILED`. The screen test `a refused answer is explained` checks the text, the enabled button, and that Reload calls `reload`.
- Earlier CR-2 (answer section missing after a failed first load): fixed. `reload()` (`EventEditorViewModel.kt:260-264`) now returns early for a new event, then calls `loadSources()` and `load()`. `loadSources()` cancels and restarts its own job. The calls for the invitation calendars and own addresses are skipped while creating, and `state.value.creating` is already correct when `edit()`/`reload()` run. `sourcesJob` is used only by `loadSources()`, so restarting it cancels no unrelated work: invitations use `inviteJob`, suggestions use `suggestJob`. The test `a retry after the sources failed to load fetches them again, so the invitation can be answered` covers it.
- Earlier CR-3: still present and still optional; carried over as CR-2 above.

## Notes (not findings)

- Acceptance criterion 1: Accept/Maybe/Decline appear for existing events with guests in writable Google/Microsoft calendars. The current answer is filled and has `selected` semantics, and a note names it. On success, `version`, the form's guests, `initial.attendees` and `savedAttendees` are updated, so the next save sends no spurious guest patch. Unsaved edits to other fields stay.
- Concurrency: `canSave` and `canRespond` block each other. `load()` and `reset()` cancel `respondJob` and clear `responding`. Every result path (Ok, Offline, Failed) clears `responding`. Tests cover a closed editor and a reload during an answer.
- Contract: the If-Match is sent as `"<version>"`, and the enum is mapped by name after the `INVITATION_RESPONSES` guard. Repository tests cover the request, refusals and server errors.
- Acceptance criterion 2: all seven strings exist in both locales.
- Acceptance criterion 3: the unit tests in `EventEditorViewModelRespondTest`, `EventEditorExtrasScreenTest`, `EventEditRepositoryTest` and `EventExtrasRepositoryTest` cover the new behavior. I did not run `sh scripts/check.sh`. The parent worker must run it and record the result as evidence.

## Review conclusion

No material issues found. The two P3 items are optional cleanups.
