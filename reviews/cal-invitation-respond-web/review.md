# Code Review

Date: 2026-11-08

Task: cal-invitation-respond-web - Respond to invitations in the web event dialog

Scope: re-review of the final revision c41803aed. Full task diff `git diff 3d5890712..c41803aed` (web/src/app/features/calendar/edit/event-dialog.{ts,html,scss,spec.ts}, event-edit.service{,.spec}.ts, web/src/locale/messages{,.de}.xlf), with a focus on the CR-1 fix and on the test-writer's second-pass specs. The task commits were rebased onto the new base 3d5890712 since the previous re-review of 2408b6be8. `git diff 2408b6be8 c41803aed -- web` changes only `event-dialog.spec.ts` (+59 lines, 3 new specs), so the production code is identical to the version reviewed before. The working tree was clean during the review.

Requirements:
- tasks/cal-invitation-respond-web.md (acceptance criteria 1-3; assumption of 2026-11-08 23:25 on dismissal returning 'saved' and on 412/409 handling)
- SPEC.md 6.2 (answering invitations for Google/Microsoft events), 4.2 (ETag/If-Match)
- AGENTS.md project notes (i18n, conventions)
- Previous review (CR-1) and reviews/cal-invitation-respond-web/fixes.md
- Installed Angular Material/CDK 22.2.3 sources (MatDialogRef close handling, OverlayRef subject lifecycle)

Git range:
- Branch: main
- HEAD: c41803aed3d2151ef98172d496396677410f3b53
- Base/start: 3d58907128bf10dd63587549f93a891030906ecc (`git merge-base HEAD origin/main`)
- Commits reviewed: 5 (76063a8e8, 096af3482, e72630466, beb709491, c41803aed)

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
- New specs in c41803aed. Each one asserts behavior the production code actually has:
  - "keeps reporting the first answer when a second answer is sent or fails": while the second answer is in flight, `busy()` blocks `dismiss`. Afterwards `finally` sets `disableClose = answered()`, which is true, so the next Escape closes with 'saved' exactly once.
  - "closes only once when dismissed after two answers": the `answered()` guard in `closeAsSavedOnDismiss()` subscribes only once, so one backdrop click closes once. The second answer is sent with the version returned by the first one (5), which confirms that the dialog's ETag/version is updated (criterion 1).
  - "closes with saved ... failed answer and Cancel": after a successful answer followed by a 409, the Cancel button still closes with 'saved'.
  - These specs go through the component's public interactions (choice buttons, the dialog ref's event streams, the Cancel button). They do not weaken any existing assertion.
- CR-1 fix (unchanged production code): after the first successful answer, the dialog subscribes once to `ref.backdropClick()` and `ref.keydownEvents()`. `respond()` and `run()` set `ref.disableClose = this.answered()` in `finally`. In Material 22.2.3, `MatDialogRef` closes on a backdrop click or Escape only while `!disableClose` (material/fesm2022/dialog.mjs:288-289), and the CDK dialog itself gets `disableClose: true` (dialog.mjs:424). So after an answer, only the component's own handler closes the dialog, always with 'saved'. The Escape filter matches Material's (no modifier key, `preventDefault`).
- Busy handling: `dismiss` does nothing while a save, delete or answer is running. After a failed save or a failed second answer, dismissing still reports 'saved'. Delete after an answer closes with 'deleted'. A reload after a 412 keeps `answered`.
- Leaks: the subscribed streams are OverlayRef subjects. `OverlayRef.dispose()` completes them (cdk/_overlay-module-chunk.mjs:838-839), so the subscriptions end when the dialog closes.
- Nested overlays: the CDK sends Escape only to the topmost overlay that has keydown listeners, so pressing Escape in the scope prompt or an open mat-select does not reach the event dialog's handler. A child dialog's backdrop does not fire the parent's backdropClick.
- Criterion 1: the choices appear for writable GOOGLE/MICROSOFT events with guests. The current answer is highlighted (`aria-pressed`), matching the user's mail account addresses case-insensitively. The request sends `If-Match` set to the occurrence version. On success the dialog updates the version, the guest statuses and the diff base, and keeps unsaved edits. A 412 shows the conflict banner. A 409 or any other error shows the server's message inline.
- Criterion 2: six `calendar.respond.*` units are in messages.xlf and messages.de.xlf, with German targets.
- Criterion 3: the new behavior is covered by unit tests in the service spec and the dialog spec. `tasks.py verify` #3 (the `sh scripts/check.sh` run) still has to be recorded by the parent worker on this revision.
