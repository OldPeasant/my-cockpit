# Fix Report

Date: 2026-11-08

Task: web-back-navigation (web back buttons return to the page they were opened from)

Scope: current task; findings CR-1 (P2) and CR-2 (P3) of reviews/web-back-navigation/review.md,
and TW-2 of reviews/web-back-navigation/tests.md (the same bug as CR-1). Base
8f36a3d9668cb4a1dffd21b73f42ae828f5bd53d, fixes made on top of HEAD b3310f135 (uncommitted, the
parent worker commits).

Earlier rounds:
- 8b8bdd996: CR-1 (back() resolved before the Back navigation) and TW-1 (setLabel recency).
- 70358e258: TW-1 of the second test pass (a Back that a guard redirects left the back button one
  page off; new followRedirect, back() waits past a redirecting cancel).

## Fixed findings

### CR-1 / TW-2 — A Back that a newer navigation supersedes leaves the back button on the wrong page

Files changed:
- `web/src/app/shared/navigation/back-navigation.ts`

Problem:
A popstate navigation cancelled with `SupersededByNewNavigation` was ignored, so the mirror stayed
on the entry that was left while the browser had already moved to the restored entry. The newer
navigation was then pushed after the wrong entry: the back button named one page and went to
another.

Fix:
`followRedirect` is renamed `followTakenOver` and now handles every cancel for which another
navigation takes over (the existing `isRedirecting`: `Redirect` or `SupersededByNewNavigation`): if
the cancel belongs to the running popstate, the mirror's index moves to the restored entry, entries
unchanged; an unknown entry keeps only the current one. The navigation that takes over then pushes
after (or replaces) that entry in `record`. The constructor's dispatch is simplified accordingly
(any other NavigationCancel or a NavigationError goes to `restoreCancelled`, as before).

Ordering verified in `web/node_modules/@angular/router/fesm2022/_router-chunk.mjs`: a new
transition makes the outer `switchMap` of `setupNavigations` unsubscribe the running one, whose
`finalize` emits the `SupersededByNewNavigation` NavigationCancel; only then does the new inner
pipeline emit its `NavigationStart` (which resets `restoring`). The state manager does not restore
history for this redirecting cancel (`isRedirectingEvent`), so the browser stays on the restored
entry.

Tests:
- The committed test "leads to the page its back button names after a Back another navigation took
  over" passes unchanged (it failed before, per review.md and tests.md).

Validation:
- `cd web && npx ng test --watch=false --include src/app/shared/navigation/back-navigation.spec.ts --include src/app/features/mail/read/mail-read-page.spec.ts` — Pass (2 files, 152 tests)

### CR-2 — A replacing navigation drops the forward entries the browser keeps

Files changed:
- `web/src/app/shared/navigation/back-navigation.ts`
- `web/src/app/shared/navigation/back-navigation.spec.ts`

Problem:
For `replaceUrl` or a same-URL navigation, `record` truncated the mirror to the current entry,
while the browser's `replaceState` keeps the forward entries. After a guard-redirected Back, a
browser Forward then hit an unknown entry and the back button fell back to the parent route.

Fix:
The replace branch of `record` copies all entries and replaces the current one in place, without
truncating.

Tests:
- Added "keeps the page ahead after a Back a guard redirected, as the browser does": history
  `/today`, `/guarded`, `/mail/messages/m1`; a Back redirected to `/login`; then browser Forward
  lands on the message with `previous()` Login, and back() goes to `/login` with Today behind. It
  fails against the truncating code (checked by temporarily restoring that line: 1 failed, 50
  passed) and passes with the fix.

Validation:
- `cd web && npx ng test --watch=false --include src/app/shared/navigation/back-navigation.spec.ts` — Pass (51 tests)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include src/app/shared/navigation/back-navigation.spec.ts --include src/app/features/mail/read/mail-read-page.spec.ts` — Pass, 152 tests

Full test suite:
- `cd web && npx ng test --watch=false` — Pass, 187 files, 4133 tests

Lint/type-check/build:
- `cd web && npx prettier --check` on the two changed files — Pass
- `cd web && npm run lint` — Pass (All files pass linting)

`.taskcheck`:
- Not run by the fixer (server and Android parts unchanged; the parent worker runs `sh scripts/check.sh`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
