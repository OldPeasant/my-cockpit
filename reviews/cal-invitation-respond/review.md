# Code Review

Date: 2026-11-09

Task: cal-invitation-respond - Accept, decline or tentatively accept invitations (web + Android)

Scope: Integration-only parent task. It has no code commits of its own. The review covers the integrated feature on main at HEAD, made of the done subtasks cal-invitation-respond-web (da673b7d9..73ec03502, done 38dc035d3) and cal-invitation-respond-android (a642512d0..b9a18ccca, done 4ae47052d). The focus is cross-client consistency and integration gaps. The findings already handled in reviews/cal-invitation-respond-web/ and reviews/cal-invitation-respond-android/ are not repeated.

Requirements:
- tasks/cal-invitation-respond.md acceptance criteria and recorded assumptions (self attendee found by mail account address; criterion 3 N/A)
- api/openapi.yaml (`POST /api/v1/calendar/events/{id}/respond`, `RespondToInvitationRequest`, mail schemas)
- AGENTS.md project notes, docs/QUALITY.md

Git range:
- Branch: main
- HEAD: e29b9fc2453edb643132595fd0ae50dd075f2d13
- Base/start: e29b9fc2453edb643132595fd0ae50dd075f2d13 (integration-only: the subtask commits are already on main)
- Commits reviewed: 0 own commits; the subtask commits listed under Scope were reviewed

Commands run (read-only):
- `git log --oneline --grep cal-invitation-respond` -> found the subtask commits listed above
- `grep -n -i -E 'respond|invitation|text/calendar|ics|partstat' api/openapi.yaml` -> only calendar endpoints (`/events/{id}/invitations`, `/events/{id}/respond`), with no mail-side marker
- Read `MailMessageResponse`, `MailAttachmentResponse` and `MailBodyResponse` in api/openapi.yaml -> no invitation or iTIP flag and no link to an event. Only a generic attachment `contentType`, which cannot name an event id for `/respond`. This confirms the criterion 3 assumption.
- Read web/src/app/features/calendar/edit/event-dialog.{ts,html} and event-edit.service.ts (the answer part)
- Read the respond paths in android/.../calendar/edit/EventEditorViewModel.kt, EventEditRepository.kt, EventExtrasRepository.kt, EventEditorSections.kt and EventEditorScreen.kt
- `grep` of calendar.respond.* in web/src/locale/messages{,.de}.xlf and calendar_edit_respond_* in res/values{,-de}/strings_calendar_edit.xml -> EN and DE are present on both clients, with the same labels: Accept/Maybe/Decline and Zusagen/Vielleicht/Absagen
- I did not run scripts/check.sh, as instructed. Test runs are recorded in the task's verify evidence and the subtask reports.

Cross-client consistency checks that passed:
- Who can answer: both clients show the actions only for an existing, non-virtual event with guests in a writable GOOGLE or MICROSOFT calendar. On web this is `answerable` with `isProviderCalendar` and `!readOnly`. On Android it is `respondable` through `invitationCalendars`, which filters on `INVITATION_SOURCES` and `!readOnly`. This matches the recorded assumption.
- Current status: both clients take the attendee whose email, in lower case, matches one of the mail account addresses. If none matches, no answer is highlighted.
- Request: both clients send `response` as ACCEPTED, TENTATIVE or DECLINED, to `occurrence.eventId`, with the occurrence version as a quoted `If-Match`. Both use the returned event's attendees and version afterwards.
- 412 -> conflict state and reload on both clients. 409 -> error message without a lockout on both. Android also offers Reload. Both block answering while a save, another answer or a conflict is pending.
- After an answer the calendar view refetches on both clients. Web closes the dialog as 'saved' even on dismiss. Android views reload when they come back.

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Android gives no feedback after a successful answer and can say "not answered yet" right after one**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModel.kt:652` (success branch) and `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorSections.kt:292`
    - Category: Correctness
    - Problem: On success, web always shows "Your answer was sent." (`calendar.respond.sent`, event-dialog.ts:438). Android only updates the form, and its feedback comes from `ownStatus`. Under the recorded assumption, the provider attendee address may not match any mail account address (for example, a Google calendar account without a matching mail account). Then the status line still reads "You have not answered yet." (`calendar_edit_respond_none`) right after a successful answer. The progress bar disappearing is the only other signal.
    - Why it matters: This breaks web/Android parity, which is the stated goal of the task. On Android a successful answer looks as if nothing happened, which invites repeated taps.
    - Suggested fix: Show a success message on Android, matching web's "Your answer was sent." (EN and DE). Alternatively, keep the last sent answer in the UI state and show it when `ownStatus` can't be resolved.

- [ ] **CR-2 | A provider failure (502) shows as "Can't reach the server" on Android, while web shows the server's problem detail**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditRepository.kt:209` (shared `calendarCall`: status >= 500 -> `Offline`) and `EventEditorViewModel.kt:664`
    - Category: Integration
    - Problem: The respond contract documents 502 as "The calendar provider failed; nothing was changed". Android maps every status >= 500 to `EditorMessage.OFFLINE` ("Can't reach the server. Try again."). Web shows the ProblemDetail `detail` for any `ApiError` (event-dialog.ts:441-445). The two clients therefore name different causes for the same failure.
    - Why it matters: The Android message is misleading, since the server is reachable and the provider is the problem. It is low impact because the advice to retry is still sensible and the mapping is a pre-existing shared convention of the Android calendar editor.
    - Suggested fix: Optionally map 502 on the respond path to `RESPOND_FAILED` or a dedicated provider-failure message. Otherwise accept it as the existing convention.

## Review conclusion

No material issues found.

The integrated feature meets criteria 1, 2 and 4 with consistent semantics across web and Android. The criterion 3 N/A assumption holds: api/openapi.yaml and the mail read API expose no invitation marker and no way to link a mail to an event. The two P3 findings are optional parity improvements and do not block completion.
