# Fix Report

Date: 2026-11-01

Task: web-mail-read-delete - Mail read view: Delete button

Scope: current task. Findings from `reviews/web-mail-read-delete/review.md`. The parent worker told me to fix CR-1 only.

## Fixed findings

### CR-1 — `#` deletes the message behind an open dialog

Files changed:
- `web/src/app/features/mail/read/mail-read-page.ts`
- `web/src/app/features/mail/read/mail-read-page.spec.ts`

Problem:
`MailReadPage.onKeydown` did not check for open Material dialogs. While a dialog such as the shortcut help was open over the read view, pressing `#` deleted the message underneath and navigated away. The mail list ignores the key in that case (`mail-list-page.ts`, `if (this.dialog.openDialogs.length) return;`), so the read view did not behave "as in the list" (criterion 3).

Fix:
The page now injects `MatDialog`, and `onKeydown` returns early while `this.dialog.openDialogs.length` is non-zero, the same guard the list uses. The doc comment was updated. A new unit test, "the # key > does nothing while a dialog is open, and deletes again once it is closed", opens a real `MatDialog` with a stub component. The dialog's animation durations are 0 because jsdom never finishes animations. The test checks that `#` is not handled (no `preventDefault`, no `DELETE` action, no navigation) while the dialog is open. It then checks that `#` deletes once the dialog has closed. I disabled the guard temporarily and the new test failed (1 failed, 68 passed). The guard is back in place.

Validation:
- `npm --prefix web run test:ci -- --include src/app/features/mail/read/mail-read-page.spec.ts` — Pass (69 tests)
- `npm --prefix web run lint` — Pass ("All files pass linting.")
- `npx --prefix web prettier --write` on both changed files — no further changes

## Not fixed

### CR-2 — After Delete, the first Back press appears to do nothing

Status:
Out of scope (accepted by the parent worker)

Reason:
The `replaceUrl` navigation after Delete is a recorded task assumption. The parent worker accepted the current behavior and told me not to change it. The review rated it P3 and called the fix optional.

## Validation summary

Focused tests:
- `npm --prefix web run test:ci -- --include src/app/features/mail/read/mail-read-page.spec.ts` — Pass, 69 of 69
- Regression check: with the guard disabled, the new test fails (1 failed, 68 passed); with the guard restored, all pass

Full test suite:
- Not run by the fixer. The parent worker runs `.taskcheck` (`sh scripts/check.sh`).

Lint/type-check/build:
- `npm --prefix web run lint` — Pass

`.taskcheck`:
- Not run (the parent worker runs it before `tasks.py done`)

Remaining unresolved findings:
0 (CR-2 accepted as is, not a defect to fix)

## Final status

All validated findings fixed and validation passed.
