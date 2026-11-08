# Code Review

Date: 2026-11-08

Task: cal-invitation-respond-web - Respond to invitations in the web event dialog

Scope: committed task changes `git diff 0cec3b4b..5e67f38ec` (commits e885db046, 5e67f38ec). After a rebase these are now 3fa882e7f and 7e53fe01a on base 54924e3fd. `git patch-id` of the web diff is identical for both ranges (a843dc1c...). One uncommitted change in `web/src/app/features/calendar/edit/event-dialog.spec.ts` was present during the review and was not reviewed (committed changes only).

Requirements:
- tasks/cal-invitation-respond-web.md (acceptance criteria 1-3)
- Assumptions cal-invitation-respond #1, cal-invitation-respond-web #1, cal-invitation-respond-android #1 (consistency with Android)
- SPEC.md 6.2 (Invitations: answering for Google/Microsoft through the API)
- api/openapi.yaml `POST /api/v1/calendar/events/{id}/respond`, `GET /api/v1/mail/accounts`, `OccurrenceResponse.eventId` semantics
- AGENTS.md project notes (i18n, conventions), existing code in web/src/app/features/calendar/edit

Git range:
- Branch: main
- HEAD: 7e53fe01ab0ae851d2430cfe0add46f7ceb8aca7 (rebased equivalent of 5e67f38ec)
- Base/start: 0cec3b4bab909270b22a0e32e7965555e46f97b5 (rebased: 54924e3fd)
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 1 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Closing with Escape or a backdrop click after answering does not tell the view the event changed**
    - File: `web/src/app/features/calendar/edit/event-dialog.html:334` (also `web/src/app/features/calendar/edit/event-dialog.ts:447`, `web/src/app/features/calendar/edit/event-editor.ts:100-105`, `web/src/app/features/calendar/views/calendar-view.ts:243,290`)
    - Category: Correctness
    - Problem: Only the Cancel/Close button returns `'saved'` once `answered()` is true (`[mat-dialog-close]="answered() ? 'saved' : undefined"`). `respond()` sets `ref.disableClose = false` again in `finally`, and `openDialog` keeps the MatDialog defaults. So after an answer, Escape or a backdrop click closes the dialog with `undefined`. `calendar-view` refreshes only on a truthy result (`if (await this.editor.open(...)) void this.occurrences.refresh()`), so the view keeps the old occurrence. That includes the old `version`: the answer increased it on the server.
    - Why it matters: The task's own assumption (cal-invitation-respond-web #1) says that closing after an answer returns `'saved'` so the view refetches. That does not hold for the two most common ways to close a dialog. One consequence: dragging the same event right afterwards calls `EventEditor.move` with the old version as If-Match. The server answers 412 and the user sees a "changed elsewhere" snackbar for a change they just made themselves. The view also keeps the stale data until the next refresh. The new tests cover only the Cancel button (`tells the view the event changed when closed after an answer`).
    - Suggested fix: Make every close path return `'saved'` after an answer. For example, once `answered()` is set, keep `ref.disableClose = true` and close through `ref.backdropClick()` / `ref.keydownEvents()` (Escape) with `ref.close('saved')`. Or let `EventEditor.openDialog` map the result from a dialog-owned flag. Add a spec that presses Escape (or triggers a backdrop click) after an answer and expects `'saved'`.

## P3 Low

None.

## Review conclusion

Material issues found; fix before completing the task.

Notes (verified, no finding):
- Criterion 1: the dialog shows Accept / Maybe / Decline for edit mode, non-virtual, non-read-only GOOGLE/MICROSOFT calendars with saved guests. The current answer is highlighted with `aria-pressed`, matching guests to mail account addresses case-insensitively. Sending posts to `/events/{eventId}/respond` with `If-Match` set to the occurrence version and body `{response}`. For a generated occurrence `eventId` is the series master (per the OpenAPI description), which matches the series-wide answer in SPEC/OpenAPI. On success the dialog updates `data.occurrence.version`, the guest statuses, the `initial` diff base and the saved guests, and keeps unsaved form edits. A later save sends the new version (tested). 412 shows the conflict banner with Reload, and other errors show the server message inline, as the assumptions say. The choices are disabled while busy or in conflict, and `busy()` also blocks save, delete and invite while an answer is being sent.
- Criterion 2: six new units (`calendar.respond.*`) are in `messages.xlf` and `messages.de.xlf`, with German targets marked translated. The other EN changes only update line numbers.
- Criterion 3: the new behavior has unit tests in both the service spec and the dialog spec, covering normal, 409, 412, generic failure, in-flight disabling, mail accounts failing, and the hidden cases. Evidence for `tasks.py verify` has not been recorded yet. The parent worker must record it before `done`.
