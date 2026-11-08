# Fix Report

Date: 2026-11-08

Task: cal-invitation-respond-web (answer calendar invitations in the web event dialog)

Scope: current task, finding CR-1 from `reviews/cal-invitation-respond-web/review.md`

## Fixed findings

### CR-1 — Closing with Escape or a backdrop click after answering does not tell the view the event changed

Files changed:
- `web/src/app/features/calendar/edit/event-dialog.ts`
- `web/src/app/features/calendar/edit/event-dialog.spec.ts`

Problem:
After an answer was sent, only the Cancel/Close button returned `'saved'`. `respond()` reset
`ref.disableClose = false` in `finally`, so MatDialog closed itself on Escape or a backdrop click with
`undefined`. `calendar-view` refreshes only on a truthy result, so the view kept the old occurrence
and its old version (the next drag would get a 412). Confirmed in the code before changing it. The
same cause also affected "Save" with no form changes after an answer, which called
`ref.close(undefined)`.

Fix:
- On the first successful answer, the new private method `closeAsSavedOnDismiss()` sets `answered`
  and subscribes once to `ref.backdropClick()` and `ref.keydownEvents()`. A backdrop click, or Escape
  without modifier keys, closes the dialog with `'saved'`. Nothing happens while a save, delete or
  answer is still running (`busy()`), which keeps the existing rule that the dialog cannot be closed
  until a running change is done.
- `respond()` and `run()` now set `ref.disableClose = this.answered()` in `finally` instead of
  `false`. Before any answer, or after a failed one, MatDialog still handles Escape and backdrop
  clicks as before. After an answer the dialog closes itself, so it always returns `'saved'`.
- "Save" without changes after an answer closes with `'saved'` instead of `undefined`.
- Spec: the `MatDialogRef` mock now has `backdropClick()` and `keydownEvents()` backed by rxjs
  Subjects. New tests: Escape after an answer closes with `'saved'` and ignores other keys and
  Escape with a modifier key; a backdrop click after an answer closes with `'saved'`; saving without
  changes after an answer closes with `'saved'`; before an answer or after a failed one, Escape and
  backdrop clicks are left to MatDialog (`disableClose` stays false and the dialog does not close
  them itself); while a save runs after an answer, Escape and backdrop clicks do nothing, and after
  that save fails Escape still closes with `'saved'`.
- Changed test: in "disables the choices while the answer is sent", the last assertion was
  `ref.disableClose` false after a successful answer. That expectation describes exactly the
  behavior CR-1 reports as wrong. It now expects `true`, with a comment explaining why. The rest of
  the test (choices disabled while sending, one request only) is unchanged.
- Regression check: with the original `event-dialog.ts` restored temporarily, the spec failed in the
  4 new after-answer tests and in the changed test (5 failed, 72 passed). With the fix, all pass.

Validation:
- `npm --prefix web exec -- ng test --include src/app/features/calendar/edit --watch=false` — Pass (8 files, 195 tests)
- `npx ng test --include src/app/features/calendar/edit/event-dialog.spec.ts --watch=false` against the original production file — Fail as expected (5 failed, 72 passed), fix restored afterwards
- `npx prettier --check` on both changed files — Pass
- `npx eslint` on both changed files — Pass
- `npx tsc --noEmit -p tsconfig.spec.json` (in `web/`) — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `npm --prefix web exec -- ng test --include src/app/features/calendar/edit --watch=false` — 195 passed

Full test suite:
- `npx ng test --watch=false` (in `web/`) — 193 files, 4580 tests passed

Lint/type-check/build:
- `npx prettier --check` and `npx eslint` on the changed files — Pass
- `npx tsc --noEmit -p tsconfig.spec.json` — Pass
- `ng build` and the full `npm run check` — Not run (left to the parent worker's `.taskcheck`)

`.taskcheck`:
- Not run (it also runs Gradle; the parent asked the fixer not to run Gradle)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
