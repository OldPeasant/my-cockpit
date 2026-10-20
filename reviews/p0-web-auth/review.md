# Code Review

Date: 2026-10-20

Task: p0-web-auth - Web setup and login pages

Scope: committed changes of the task at the final revision, `git diff babb06f4..2a12481 -- web docs` (commits e0d4772, 677d9ec, 75edee3, 2a12481). This is the second review; it verifies the fixes for CR-1 to CR-4 of the first review (revision 677d9ec) and re-reviews the final task code. Uncommitted working-tree changes were not reviewed (there were none at review time).

Requirements:
- tasks/p0-web-auth.md acceptance criteria 1-4 and recorded assumptions (#1-#6)
- SPEC.md §4.1 (setup flow, session cookie, CSRF double-submit, remember device, login backoff)
- api/openapi.yaml (auth, setup endpoints; 429 with `Retry-After`)
- docs/ARCHITECTURE.md (web stack, i18n via `@angular/localize`, conventions), AGENTS.md project notes
- reviews/p0-web-auth/fixes.md (fixer report for CR-1 to CR-4)

Git range:
- Branch: main
- HEAD: 2a1248148a9ada920a70f31f49f9240b5011dc72
- Base/start: babb06f4ff5c279f43422fb658dd7be5eb9bacfc
- Commits reviewed: 4 (e0d4772, 677d9ec, 75edee3, 2a12481)

Commands run (read-only): `git log --oneline babb06f4..2a12481`, `git diff --stat babb06f4..2a12481`, `git diff 75edee3..2a12481 -- web ':!web/src/locale'`, `git status --short` (clean), `grep` on `web/src/locale/messages.xlf` (ids `login.*` 15, `setup.*` 32, `topbar.*` 6 = 53, equal to the 53 distinct `@@login/setup/topbar` ids in the task's templates and code; `login.backoff` and `login.backoffWait` present). Full reads of `auth.guards.ts`, `auth.service.ts`, `login.ts`, `login.html`, `setup.ts`, `shell.ts`, `app.routes.ts`. The reviewer did not run `npm run check`; the fixer reports `cd web && npm run check` and `sh scripts/check.sh` passing (245 web tests), and criterion 4 evidence has to come from the parent's own run on 2a12481.

Summary: 0 P0, 0 P1, 0 P2, 0 P3 (open). Previous findings CR-1 to CR-4: all resolved.

## Status of previous findings

- [x] **CR-1 (P2) | Guard failures other than 401 leave a blank page or a half-logged-out shell** - Resolved.
    - `auth.guards.ts`: `authGuard` now catches failures of `/setup/status` and `/me` and redirects to `/login?returnUrl=...`; `loginGuard` shows the login form on failure; `setupGuard` shows the setup form when the status is unknown (server answers 409 if setup was already done, which the setup form shows as `userMessage`). The 401/setup/logged-in paths are unchanged. Because `setupRequired()` only caches a successful answer, the next navigation after the outage asks again, so a recovered server leads to the right page.
    - `shell.ts:114`: `navigateByUrl(LOGIN_PATH).catch(() => false)` removes the unhandled rejection after logout.
    - Tests: 5 new guard tests with a 502 from `/setup/status` or `/me` (`auth.guards.spec.ts`), 1 shell test with a rejecting navigation (`shell.spec.ts`).
- [x] **CR-2 (P3) | Backoff countdown re-announced every second** - Resolved. `login.html:61-66`: only the static "Too many failed attempts." is in `role="alert"`; the ticking "Try again in N s." is outside the live region. `login.spec.ts` checks both the visible text and that the alert text stays static while counting down.
- [x] **CR-3 (P3) | New i18n messages not extracted** - Resolved. `messages.xlf` contains all 53 task message ids (see commands above).
- [x] **CR-4 (P3) | Download test leaks global `URL` stubs** - Resolved. `setup.spec.ts:139-143` saves the originals and restores them in `onTestFinished`, which also runs when an assertion fails.

## Re-review of the final code

- Criterion 1 (setup page): unchanged since the first review and still correct; `setupGuard` restricts `/setup` to setup-needed (with the outage fallback above).
- Criterion 2 (login page): password + TOTP, recovery-code switch, remember device, 429 countdown from `Retry-After` (60 s fallback), submit disabled while blocked.
- Criterion 3 (guard, logout, return URL): `authGuard` on the shell route covers all children; logout in the top bar account menu; `/login` follows a `safeReturnUrl`-filtered return URL (no open redirect).
- Criterion 4 (tests): specs cover setup and login forms, guard redirects including the new outage paths, `AuthService`, `ApiError` retry-after parsing, QR code and shell logout. `npm run check` result to be recorded by the parent.
- Security: no change to the session/CSRF handling reviewed before; the new `catch` blocks only swallow errors in navigation decisions, never grant access (`authGuard` returns `true` only after a successful `/me`).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. All four findings of the first review (CR-1 to CR-4) are fixed on revision 2a12481 with tests, and the final code meets the acceptance criteria; the parent worker must still record its own `npm run check` / `.taskcheck` run on this revision as evidence for criterion 4.
