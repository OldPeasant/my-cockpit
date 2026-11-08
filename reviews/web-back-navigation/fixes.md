# Fix Report

Date: 2026-11-08

Task: web-back-navigation (web back buttons return to the page they were opened from)

Scope: current task; findings CR-1 (reviews/web-back-navigation/review.md) and TW-1 (the low-severity
setLabel observation in reviews/web-back-navigation/tests.md). Base commit
9a29669abe86beff6ba450f28a4435ba4087f0f3, fixes made on top of 4e18250b3 (uncommitted, the parent worker commits).

## Fixed findings

### CR-1 — back() resolves before the Back navigation, so a second triage can apply again

Files changed:
- `web/src/app/shared/navigation/back-navigation.ts`
- `web/src/app/shared/navigation/back-navigation.spec.ts`
- `web/src/app/features/mail/read/mail-read-page.spec.ts`

Problem:
`BackNavigation.back()` returned as soon as it had called `location.back()`. The router handles the
popstate later (in a setTimeout), so `MailReadPage.triage()` cleared `triaging` while the moved message
was still shown. A second `e`/`!`/`#` or click in that window ran `mail.apply` again (a second Delete
on a message in Trash could delete it for good).

Fix:
When it goes back in the history, `back()` now subscribes to the router events before calling
`location.back()` and resolves on the next NavigationEnd, NavigationCancel, NavigationError or
NavigationSkipped. A 1.5 s safety timeout (`BACK_TIMEOUT_MS`) resolves it anyway, so a popstate the
browser never reports cannot hang callers. `MailReadPage` needed no change: its `finally` now runs only
after the page was left, so `triaging` stays set (buttons disabled, shortcuts ignored) during the Back.
The fallback path (`navigateByUrl`) is unchanged.

New tests:
- back-navigation.spec.ts "resolves back() only when the Back navigation has ended": back() is still
  pending and the URL unchanged after the microtasks; once it resolves the previous page is shown.
- back-navigation.spec.ts "resolves back() after a short while when no navigation follows": with
  `location.back` stubbed out and fake timers, back() is pending after 1 s and resolved after 2 s.
- mail-read-page.spec.ts "while going back" › "does not delete again on a second # until the list is
  shown" and "... on a second click ...": a second `#` key press, or a click on Delete after change
  detection, while the popstate navigation is still pending does not call `mail.apply` again; the app
  ends on the folder with exactly one DELETE.

All four new tests fail against the previous `back-navigation.ts` (checked by temporarily restoring
the HEAD version of the file, then putting the fix back).

Validation:
- `cd web && npx ng test --watch=false --include src/app/shared/navigation/back-navigation.spec.ts --include src/app/features/mail/read/mail-read-page.spec.ts` — Pass (138 tests)
- Same command with the HEAD version of back-navigation.ts — 5 failed (the 5 new tests, incl. TW-1), 133 passed, as expected

### TW-1 — setLabel with the same name does not refresh its recency

Files changed:
- `web/src/app/shared/navigation/back-navigation.ts`
- `web/src/app/shared/navigation/back-navigation.spec.ts`

Problem:
`setLabel` returned early when the path already had the same name, so the path kept its old position
and could be evicted from the 100-name list although the page had just named itself again.

Fix:
The early return now only applies when the path already has that name and is the most recent entry;
otherwise the entry is moved to the most recent end, as for a new or changed name.

New test:
- back-navigation.spec.ts "keeps a name set again among the most recent names": p1 named, 99 other
  names, p1 named again with the same name, one more name; p1 still has its name.

Validation:
- `cd web && npx ng test --watch=false --include src/app/shared/navigation/back-navigation.spec.ts --include src/app/features/mail/read/mail-read-page.spec.ts` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include src/app/shared/navigation/back-navigation.spec.ts --include src/app/features/mail/read/mail-read-page.spec.ts` — Pass, 2 files, 138 tests

Full test suite:
- `cd web && npx ng test --watch=false` — Pass, 187 files, 4119 tests

Lint/type-check/build:
- `cd web && npx prettier --check` on the three changed files — Pass
- `cd web && npm run lint` — Pass (All files pass linting)

`.taskcheck`:
- Not run by the fixer (server and Android parts unchanged; the parent worker runs `sh scripts/check.sh`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
