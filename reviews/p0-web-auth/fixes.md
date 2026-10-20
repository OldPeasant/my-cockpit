# Fix Report

Date: 2026-10-20

Task: p0-web-auth - Web setup and login pages

Scope: findings CR-1 to CR-4 of `reviews/p0-web-auth/review.md` (base babb06f4ff5c279f43422fb658dd7be5eb9bacfc, HEAD 75edee3). Changes are uncommitted in the working tree for the parent worker to commit.

## Fixed findings

### CR-1 (P2) — Guard failures other than 401 leave a blank page or a half-logged-out shell

Files changed:
- `web/src/app/core/auth/auth.guards.ts`
- `web/src/app/core/auth/auth.guards.spec.ts`
- `web/src/app/shell/shell.ts`
- `web/src/app/shell/shell.spec.ts`

Problem:
A failing `/setup/status` or `/me` call (network error, 5xx) with any status other than the handled 401 made the guards throw. The navigation was rejected, so the user saw only a toast over a blank page. In `Shell.logout()` the navigation to `/login` could reject with nothing to handle it.

Fix:
- `authGuard`: on a failure, redirect to `/login` with `returnUrl`, so the user can retry from there.
- `loginGuard`: on a failure, return `true`, so the login form is shown.
- `setupGuard`: on a failure (setup status unknown), return `true`, so the setup form is shown. If setup was already done, the server answers 409. Sending the user to `/login` would be wrong when setup is still required.
- Guard behavior for 401, for setup required and not required, and for a logged-in user is unchanged. The interceptor still shows the toast, because these calls are not marked `SILENT_ERRORS`.
- `Shell.logout()`: the navigation promise now has `.catch(() => false)`, so a failed navigation can't become an unhandled rejection.
- New tests:
  - 5 guard tests with a 502 from `/setup/status` or `/me`, covering `authGuard` (both calls), `loginGuard` (both calls) and `setupGuard`.
  - 1 shell test in which `navigateByUrl` rejects. I confirmed it catches the bug: with the `.catch` removed, Vitest reports "Unhandled Rejection: guard failed" and the run fails.

Validation:
- `cd web && npx ng test --watch=false` — Pass (245 tests). Temporarily reverting the shell fix gives 1 unhandled error, as expected.

### CR-2 (P3) — Backoff countdown re-announced every second

Files changed:
- `web/src/app/features/auth/login/login.html`
- `web/src/app/features/auth/login/login.spec.ts`

Problem:
The whole sentence "Too many failed attempts. Try again in N s." was inside `role="alert"`. The countdown changes every second, so screen readers announced it again every second.

Fix:
The notice (`p.notice.backoff`) now has two spans:
- a static `<span role="alert">` with "Too many failed attempts." (`@@login.backoff`), announced once;
- a separate span outside the live region with "Try again in N s." (new id `@@login.backoffWait`).

`&ngsp;` keeps the visible space between them. Without it, Angular strips the whitespace and the text reads "attempts.Try". The visible text is unchanged.

Tests now check the full visible countdown text on `.notice.backoff`. They also check that the `[role=alert]` text stays the static sentence while the countdown ticks.

Validation:
- `cd web && npx ng test --watch=false` — Pass

### CR-3 (P3) — New i18n messages not extracted into messages.xlf

Files changed:
- `web/src/locale/messages.xlf`

Problem:
The task's `@@login.*`, `@@setup.*` and `@@topbar.*` messages were not in the source catalog.

Fix:
Ran `cd web && npm run i18n:extract` after the template changes. The catalog now has 125 messages, including `login.backoff` and `login.backoffWait`.

Validation:
- `cd web && npm run i18n:extract` — Pass ("Extraction Complete. (Messages: 125)")

### CR-4 (P3) — Download test replaces global URL functions without restoring them

Files changed:
- `web/src/app/features/auth/setup/setup.spec.ts`

Problem:
The test replaced `URL.createObjectURL` and `URL.revokeObjectURL` with mocks and never put the originals back.

Fix:
The test now saves the originals before stubbing them and restores them in `onTestFinished`. This also runs when an assertion fails.

Validation:
- `cd web && npx ng test --watch=false` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false` — Pass: 18 files, 245 tests.
- Regression check for CR-1: I temporarily removed the `.catch` in `Shell.logout()`. The run reported 1 unhandled rejection, as expected. The fix was then restored.

Full test suite:
- `cd web && npm run check` (api:check, lint `--max-warnings=0`, test:ci, ng build) — Pass

Lint/type-check/build:
- Included in `npm run check` — Pass. Prettier was run only on the changed files, not on `web/src/app/api/generated`.

`.taskcheck`:
- `sh scripts/check.sh` — Pass ("all checks passed")

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
