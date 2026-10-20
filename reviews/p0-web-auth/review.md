# Code Review

Date: 2026-10-20

Task: p0-web-auth - Web setup and login pages

Scope: committed changes of the task, `git diff babb06f4..HEAD -- web docs` (commits e0d4772, 677d9ec). Uncommitted test-writer files in the working tree (`app.spec.ts`, `api-error.spec.ts`, `qr-code.spec.ts`) were not reviewed.

Requirements:
- tasks/p0-web-auth.md acceptance criteria 1-4 and recorded assumptions (#1-#6)
- SPEC.md §4.1 (setup flow, session cookie, CSRF double-submit, remember device, login backoff)
- api/openapi.yaml (`/api/v1/auth/{login,logout,me}`, `/api/v1/setup`, `/setup/confirm-totp`, `/setup/status`; 429 with `Retry-After`)
- docs/ARCHITECTURE.md (web stack, i18n via `@angular/localize`, conventions), AGENTS.md project notes
- server CSRF configuration (`server/.../core/auth/infra/SecurityConfiguration.kt`) to check the client/server CSRF contract

Git range:
- Branch: main
- HEAD: 677d9ec6cba8084824f381e11dab6ba43cf27e3c
- Base/start: babb06f4ff5c279f43422fb658dd7be5eb9bacfc
- Commits reviewed: 2 (e0d4772, 677d9ec)

Commands run (read-only): `git log --oneline babb06f4..HEAD`, `git diff --stat babb06f4..HEAD`, `git diff babb06f4..HEAD -- <files>`, `git status --short`, `python tasks.py assumptions`, `grep` on `api/openapi.yaml`, `web/src/locale/messages.xlf`, server security config. `npm run check` was not run by the reviewer, because the working tree holds the test-writer's uncommitted changes and the result would not reflect only the committed revision; criterion 4 evidence has to come from the parent's run.

Summary: 0 P0, 0 P1, 1 P2, 3 P3

What was verified as correct:
- Criterion 1: `/setup` is guarded by `setupGuard` (redirects to `/login` once `setupRequired` is false); username/password form with the server's rules (pattern, 12-256 chars, max 64), password repeat; the QR code is drawn from `otpauthUri` as an accessible SVG (`role="img"`, localized label); secret shown as fallback; 10 recovery codes with copy (CDK Clipboard) and download (Blob, `text/plain`); "codes saved" checkbox and TOTP confirmation; wrong code clears the field and allows a retry.
- Criterion 2: password + TOTP, recovery-code switch (enables/disables the matching control so exactly one of `totp`/`recoveryCode` is sent, as the API requires), remember device; 429 shows a countdown from `Retry-After` (parsed in `ApiError`, 60 s fallback), submit disabled while blocked; 401 message does not disclose which part was wrong; the used code is cleared after a failure.
- Criterion 3: `authGuard` on the shell route covers every child route; logout in a top bar account menu; `/login` follows `returnUrl` after success (component and `loginGuard`). `safeReturnUrl` only accepts same-app absolute paths; navigation goes through the Angular router, so no open redirect.
- Security: session stays in the server's HttpOnly cookie, no token or secret is stored client-side or logged; auth calls use `SKIP_LOGIN_REDIRECT` and form calls `SILENT_ERRORS`; CSRF: relative `/api/...` URLs with `withXsrfConfiguration`, and the `XSRF-TOKEN` cookie is issued on the first GET (`/setup/status` or `/me` from the guards) before login/logout POSTs, matching `SecurityConfiguration.kt`; setup POSTs are CSRF-exempt on the server.
- Criterion 4: committed Vitest specs cover setup form, enrollment display, copy/download, confirmation, login form, recovery switch, 401/429/other errors, guard redirects (setup, login, return URL, external URL rejected), `AuthService` caching, 401 handling and `Retry-After` parsing through the real interceptors, and shell logout.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Guard failures other than 401 leave a blank page or a half-logged-out shell**
    - File: `web/src/app/core/auth/auth.guards.ts:26-27`, `web/src/app/core/auth/auth.guards.ts:42-44`, `web/src/app/core/auth/auth.service.ts:47`, `web/src/app/core/auth/auth.service.ts:62`, `web/src/app/shell/shell.ts:111-113`
    - Category: Correctness
    - Problem: `setupRequired()` and `checkSession()` rethrow every error except a 401 of `/me` (server unreachable = status 0, 502 from Caddy while the server restarts, 500). The guards do not catch it and the app has no navigation error handler (`app.config.ts` only has `provideRouter(routes)`), so the navigation is rejected. On first load (or any reload during a server restart) the user sees only a toast over an empty page, with no retry action. In `Shell.logout()` the logout error is swallowed, but the following `navigateByUrl('/login')` runs `loginGuard`, whose `/me` call fails the same way: the promise rejects unhandled, the user stays on the shell while `AuthService` already reports logged out (the shell spec only mocks `logout()` failing, not the guard).
    - Why it matters: the review checklist requires every failure path to end in a recoverable screen; an outage or a restart of the self-hosted server is a normal event and currently gives a dead, blank app.
    - Suggested fix: handle non-401 failures in the guards (e.g. keep the user on a small "server not reachable, retry" state or route, or let `loginGuard` show the login page when `/me` fails), or add `withNavigationErrorHandler` to show a retry; await/handle the navigation result in `Shell.logout()`. Add a guard test for a failing `/setup/status` or `/me`.

## P3 Low

- [ ] **CR-2 | Backoff countdown is re-announced every second by screen readers**
    - File: `web/src/app/features/auth/login/login.html:61-64`
    - Category: Quality
    - Problem: the countdown text "Try again in {{ waitSeconds() }} s." lives inside `role="alert"` (assertive live region) and changes every second, so assistive technology announces it repeatedly for the whole backoff (which grows exponentially).
    - Why it matters: accessibility; the page interrupts the user each second.
    - Suggested fix: announce the block once (e.g. alert with a static message, the countdown in a separate non-live element, or `role="status"` / `aria-live="polite"` with `aria-atomic` and no per-second update).

- [ ] **CR-3 | New i18n messages not extracted into `messages.xlf`**
    - File: `web/src/locale/messages.xlf`
    - Category: Quality
    - Problem: the task adds about 50 `@@login.*`, `@@setup.*`, `@@topbar.account`, `@@topbar.logout` ids, but `messages.xlf` (committed and kept current by p0-web-api-client and p0-web-shell via `npm run i18n:extract`) contains none of them.
    - Why it matters: the committed source catalog no longer matches the templates, so translators work from an outdated file.
    - Suggested fix: run `cd web && npm run i18n:extract` and commit the regenerated file.

- [ ] **CR-4 | Download test replaces global `URL` functions without restoring them**
    - File: `web/src/app/features/auth/setup/setup.spec.ts:137-139`
    - Category: Tests
    - Problem: `Object.assign(URL, { createObjectURL, revokeObjectURL })` installs mocks on the global `URL` and never restores them (only the `click` spy is restored).
    - Why it matters: later tests in the same worker silently use the stubs, which can hide or cause failures in unrelated specs.
    - Suggested fix: use `vi.stubGlobal`/`vi.spyOn` with restore in `afterEach` (or save and restore the original properties).

## Review conclusion

Material issues found; fix before completing the task. No P0/P1 issues: all four acceptance criteria are implemented and covered by committed tests. CR-1 (P2) is a resilience gap on error paths that should be fixed or explicitly dispositioned by the parent worker; CR-2 to CR-4 are small cleanups.
