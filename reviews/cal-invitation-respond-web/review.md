# Code Review

Date: 2026-11-08

Task: cal-invitation-respond-web - Respond to invitations in the web event dialog

Scope: re-review of the final code. Full task diff `git diff 54924e3fd..2408b6be8` (web/src/app/features/calendar/edit/event-dialog.{ts,html,scss,spec.ts}, event-edit.service{,.spec}.ts, web/src/locale/messages{,.de}.xlf), with a focus on the CR-1 fix in 2408b6be8. Committed changes only. An uncommitted change to `web/src/app/features/calendar/edit/event-dialog.spec.ts` was in the working tree during this review: two additional specs about a second answer. They were not reviewed as part of the scope. They only add tests, and the behavior they describe matches the committed code.

Requirements:
- tasks/cal-invitation-respond-web.md (acceptance criteria 1-3, assumption of 2026-11-08 23:25 on dismissal returning 'saved', 412/409 handling)
- SPEC.md 6.2 (answering invitations for Google/Microsoft events), 4.2 (ETag/If-Match)
- AGENTS.md project notes (i18n, conventions)
- Previous review reviews/cal-invitation-respond-web/review.md (CR-1) and reviews/cal-invitation-respond-web/fixes.md
- Installed Angular Material/CDK 22.2.3 sources (MatDialogRef close handling, OverlayRef subject lifecycle)

Git range:
- Branch: main
- HEAD: 2408b6be845f467f99add8be3ee6aadf15b8226d
- Base/start: 54924e3fd196833064725e3138480d2e542345d6 (`git merge-base HEAD origin/main`)
- Commits reviewed: 4 (3fa882e7f, 7e53fe01a, acace40ee, 2408b6be8)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.

Notes (verified, no finding):
- CR-1 fix: after the first successful answer, `closeAsSavedOnDismiss()` (event-dialog.ts:457-470) sets `answered` and subscribes once to `ref.backdropClick()` and `ref.keydownEvents()`. The `answered()` guard prevents a second subscription on later answers. `respond()` and `run()` now set `ref.disableClose = this.answered()` in `finally`. In Material 22.2.3, `MatDialogRef` closes on a backdrop click or Escape only while `!disableClose` (material/fesm2022/dialog.mjs:288-289), and MatDialog passes `disableClose: true` to the CDK dialog (dialog.mjs:424). So once the dialog is answered, only the component's own handler closes it, always with 'saved'. The Escape filter (`key === 'Escape'`, no modifier key, `preventDefault`) does what MatDialog does.
- Busy handling: `dismiss` does nothing while `busy()` (a save, delete or answer is running). This keeps the existing rule that a change in flight cannot be dismissed. After a failed save or a failed second answer, `disableClose` stays true and the next dismissal still reports 'saved'. Delete after an answer still closes with 'deleted', and a reload after a 412 keeps `answered`.
- Leaks: `backdropClick()` and `keydownEvents()` return the OverlayRef subjects, which are completed in `OverlayRef.dispose()` (cdk/_overlay-module-chunk.mjs:838-839). The subscriptions therefore end when the dialog closes, and no manual teardown is needed.
- Nested overlays: Escape is dispatched to the topmost overlay that has keydown listeners (cdk OverlayKeyboardDispatcher), so pressing Escape in the scope prompt or an open mat-select panel does not reach the event dialog's handler. Clicks on a child dialog's backdrop do not trigger the parent's backdropClick.
- Other close paths: the Cancel/Close button returns `answered() ? 'saved' : undefined`. Save without changes after an answer now closes with 'saved' (event-dialog.ts:372).
- Criterion 1: the choices are shown for edit mode, non-virtual, writable GOOGLE/MICROSOFT calendars with saved guests. The current answer is highlighted (`aria-pressed`) by matching mail account addresses case-insensitively. `respond` POSTs to `/events/{eventId}/respond` with `If-Match` set to the occurrence version. On success it updates the occurrence version, the guest statuses, the diff base and the saved guests, and keeps unsaved edits. A 412 shows the conflict banner, and 409 and other errors show the server's message inline. The choices, save, delete and invite are disabled while busy.
- Criterion 2: six `calendar.respond.*` units are in messages.xlf and messages.de.xlf, with German targets.
- Criterion 3: the new specs cover dismissal by Escape (including non-Escape keys and Escape with a modifier key), by a backdrop click, by Save without changes, before an answer and after a failed answer, and during a running save after an answer. The changed assertion in "disables the choices while the answer is sent" (disableClose true after a successful answer) follows from the fix and does not weaken the test. Criterion 3 is not yet recorded with `tasks.py verify` (#3 unticked). The parent worker must record it after running `sh scripts/check.sh` on the final code.
