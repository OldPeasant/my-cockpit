# Fix Report

Date: 2026-11-08

Task: web-back-navigation (web back buttons return to the page they were opened from)

Scope: current task; finding TW-1 of the second test pass (reviews/web-back-navigation/tests.md: a
Back that a guard redirects leaves the back button one page off). Base: `git merge-base HEAD
origin/main`; fixes made on top of b7aa7a329 (uncommitted, the parent worker commits).

Earlier round: the previous fix round (CR-1, back() resolving before the Back navigation, and the
earlier TW-1, setLabel recency) was fixed in commit 8b8bdd996. Of the current review report, CR-2
(tighten the cancelled-Back test) was already done by the test writer; CR-1 (P3, refused Back then
browser Forward) is accepted by the parent worker as a known limitation and was not changed here.

## Fixed findings

### TW-1 — A Back that a guard redirects leaves the back button one page off

Files changed:
- `web/src/app/shared/navigation/back-navigation.ts`
- `web/src/app/shared/navigation/back-navigation.spec.ts`

Problem:
When a guard of the restored page redirects a popstate (Back/Forward) navigation, `BackNavigation`
ignored the redirecting NavigationCancel. The router keeps the browser on the restored entry and
schedules the redirect as an imperative navigation with `replaceUrl` (verified in
`@angular/router/fesm2022/_router-chunk.mjs`, `RedirectRequest` handling:
`replaceUrl: ... || isBrowserTriggeredNavigation(currentTransition.source)`). The mirror therefore
replaced the entry it was still on (the page that was left) instead of the restored one, so
`previous()` named one page while `location.back()` went to another. Also, back() resolved on that
redirecting NavigationCancel, before the redirect had ended.

Fix:
- New `followRedirect(id)`: on a NavigationCancel with code `Redirect` whose id is the running
  popstate (`restoring`), the mirror moves its index to the restored entry (found by `restoredId`;
  if unknown, it keeps just the current entry, so nothing is known behind) and clears `restoring`,
  without changing entries. The follow-up `replaceUrl` navigation then replaces that entry in
  `record`, as the browser does.
- `back()` no longer resolves on a redirecting NavigationCancel (`Redirect` or
  `SupersededByNewNavigation`, the existing `isRedirecting` helper); it waits for the navigation that
  takes over, still bounded by the 1.5 s safety timeout. Doc comment updated.

Tests:
- Kept unchanged and now passing: "leads to the page its back button names after a Back a guard
  redirected".
- Added "resolves back() only when the navigation a guard redirected the Back to has ended": without
  settle(), after `await back()` the redirect's NavigationEnd to `/login` has been seen and the router
  is on `/login`.
- Added "goes to the parent route after a redirected Back to an entry it never saw": the restored
  entry has an unknown navigation id; after the redirect nothing is known behind.
- The first two fail against the HEAD version of `back-navigation.ts` (checked by temporarily putting
  the HEAD file back: 2 failed, 44 passed; then the fix was restored). The unknown-entry test covers
  the new not-found branch.

Validation:
- `cd web && npx ng test --watch=false --include src/app/shared/navigation/back-navigation.spec.ts --include src/app/features/mail/read/mail-read-page.spec.ts` — Pass (2 files, 148 tests)

## Not fixed

### CR-1 (review.md) — refused Back then browser Forward

Status:
Out of scope for this round (accepted as a known limitation by the parent worker)

Reason:
The parent worker decided not to fix it; no code changed for it.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include src/app/shared/navigation/back-navigation.spec.ts --include src/app/features/mail/read/mail-read-page.spec.ts` — Pass, 148 tests
- Same spec against the HEAD production file — 2 failed (the redirect tests), 44 passed, as expected

Full test suite:
- `cd web && npx ng test --watch=false` — Pass, 187 files, 4129 tests

Lint/type-check/build:
- `cd web && npx prettier --check` on the two changed files — Pass
- `cd web && npm run lint` — Pass (All files pass linting)

`.taskcheck`:
- Not run by the fixer (server and Android parts unchanged; the parent worker runs `sh scripts/check.sh`)

Remaining unresolved findings:
0 (CR-1 accepted as a known limitation)

## Final status

All validated findings fixed and validation passed.
