# Code Review

Date: 2026-11-08

Task: cal-invitation-respond-android - Respond to invitations in the Android event editor

Scope: final task code at HEAD, `git diff 0d79924c8cfdc1218629b425f6aa284dc77fcc21..b663ca0dc567e0174ba4aae42606fd12245d616b` (task commits 81324b87b implementation, b4c468cea tests, 403feb486 fixes, 3dd6114fd more tests, b663ca0dc KDoc fix; a501d1597 and e22f5685d only add review reports). Committed changes only, no uncommitted changes. This is the third review. The previous one was at 6fc9e1864 (before a rebase; the same content as e22f5685d). Since then, 3dd6114fd added two tests and b663ca0dc rewrote two KDoc comments for the earlier CR-1. I read the whole task diff again on the final code. I re-checked the delta in detail.

Requirements:
- tasks/cal-invitation-respond-android.md: the acceptance criteria and the recorded assumption. The assumption says the rules are the same as on the web: 412 -> conflict, 404 -> gone, any other failure -> "could not be sent", and unsaved form edits stay after answering.
- SPEC.md §6.2: answering Google/Microsoft invitations through `POST /api/v1/calendar/events/{id}/respond`
- AGENTS.md project notes: strings in values and values-de, Android conventions, the task's touches
- Parent agent instructions: the earlier CR-2 (mail accounts fetched twice) is accepted as optional

Git range:
- Branch: main
- HEAD: b663ca0dc567e0174ba4aae42606fd12245d616b
- Base/start: 0d79924c8cfdc1218629b425f6aa284dc77fcc21 (`git merge-base HEAD origin/main`)
- Commits reviewed: 7 (5 change android/)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The mail accounts are fetched twice (accepted as optional by the parent worker)**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventExtrasRepository.kt:126-130`
    - Category: Quality
    - Problem: `mailAccounts()` and `ownAddresses()` both call `listMailAccounts()` and map the same response. This is unchanged since the earlier reviews.
    - Why it matters: it costs one extra network call and duplicates a mapping. Behavior is not affected.
    - Suggested fix: optional. Record it as accepted in quality.json, as the parent worker plans to. Or derive `ownAddresses` from `mailAccounts()`.

## Verification of the earlier findings and the new commits

- Earlier CR-1 (stale KDoc about 409): fixed in b663ca0dc. The KDoc of `RESPOND_FAILED` (`EventEditorViewModel.kt:48-51`) and of `STALE` (`:756-759`) now lists every reason for a refusal and says that a reload and another try may help. This matches the neutral text and the Reload action. The commit changes only comments.
- Earlier CR-2: still present and accepted. It is carried over as CR-1 above.
- New test `a delegated or unknown own answer counts as not answered and selects no button` (`EventEditorExtrasScreenTest.kt`): it matches `ResponseSection` (`EventEditorSections.kt:293-302`). There, DELEGATED, NEEDS_ACTION and a null `ownStatus` show "You have not answered yet." DELEGATED is not in `INVITATION_RESPONSES`, so no button gets `selected`. With no own addresses, `ownStatus` is null and the buttons stay enabled.
- New test `a reload of a new event neither fetches the sources again nor drops the form` (`EventEditorViewModelRespondTest.kt`): it matches `reload()` (`EventEditorViewModel.kt:263-267`), which returns early when `occurrence == null`. So `localCalendars()` is called only once, from `create()`. The invitation calendars and the event are never fetched, and the edited title stays. The test checks behavior.

## Notes (not findings)

- Acceptance criterion 1 is met in code. Accept/Maybe/Decline show for existing events with guests in writable Google/Microsoft calendars (`respondable`). The current answer is selected and named in a note. On success (`respond()`, `EventEditorViewModel.kt:644-679`), the code updates `version`, the guests in the form, `initial.attendees` and `savedAttendees`, and it keeps unsaved edits to other fields. 412 maps to CONFLICT, 404 to GONE, and any other failure to RESPOND_FAILED. Every result path clears `responding`.
- Concurrency is unchanged since the earlier review. `canRespond` and `canSave` block each other. `load()` and `reset()` cancel `respondJob`.
- Acceptance criterion 2: all seven strings exist in both locales. The task file already records this as verified.
- Acceptance criterion 3: the unit tests cover the new behavior in the ViewModel, the screen and both repositories. I did not run `sh scripts/check.sh`. Criteria 1 and 3 are not yet verified in the task file. The parent worker must run the check and record the evidence with `tasks.py verify` before `done`.
- Every changed file is under `android/.../calendar/edit`, its tests, or `strings_calendar_edit.xml`. This matches the task's touches.

## Review conclusion

No material issues found. The remaining P3 item is an optional cleanup that the parent worker has already accepted.
