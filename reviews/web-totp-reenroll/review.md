# Code Review

Date: 2026-10-22

Task: web-totp-reenroll - Web forced TOTP re-enrollment after restore

Scope: `git diff 98d1e4a985b6761a52738793f8990b8bfac4548c..3008661a -- web`. Only committed changes are reviewed. Uncommitted test files that the test-writer may be adding in the working tree are not part of the range. This is a re-review of the final revision, including the fixes for the earlier CR-1 and CR-2.

Requirements:
- tasks/web-totp-reenroll.md: acceptance criteria and the recorded assumptions (password-only login as a third login mode, `/enroll-totp` route in app.routes.ts, enrollment starts automatically, messages.xlf not re-extracted)
- SPEC.md §4.1 and §10.2
- AGENTS.md project notes and docs/ARCHITECTURE.md conventions
- Server contract: `server/.../core/auth/domain/TotpEnrollmentService.kt` and `api/TotpEnrollmentController.kt` (409 when re-enrollment is not required, other sessions ended on confirm, shared login throttle, a replaced secret must be started again)
- reviews/web-totp-reenroll/fixes.md (fixes for the earlier CR-1 and CR-2)

Git range:
- Branch: main
- HEAD: 3008661a
- Base/start: 98d1e4a985b6761a52738793f8990b8bfac4548c
- Commits reviewed: 5 (a87dd44d, b58c240a, b7a3845f, 5842f710, 3008661a)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | `totpEnrollmentGuard` drops the return URL when the server fails**
    - File: `web/src/app/core/auth/auth.guards.ts:95-98`
    - Category: Correctness
    - Problem: When `setupRequired()` or `checkSession(true)` throws, the guard returns `router.parseUrl(LOGIN_PATH)`. The guard has already computed `returnUrl`, and the no-session branch a few lines above passes it on as `?returnUrl=...`. The failure branch does not. `authGuard` keeps the return URL in the same situation.
    - Why it matters: After a temporary server error on `/enroll-totp?returnUrl=/inbox`, the user logs in, enrolls, and lands on the dashboard instead of the page they asked for. This is a small loss of navigation context, not a dead end.
    - Suggested fix: Build the same login `UrlTree` with `returnUrl` in the `catch` branch as in the no-session branch, and change the test "totpEnrollmentGuard sends to /login when the server fails" to expect it.

## Notes (not findings)

- **Earlier CR-1 (401/409 on the enrollment page) is fixed.** `leaveOnSessionChange` handles both statuses:
    - On 401 it goes to `/login` and keeps the safe return URL. `loginGuard` asks the server again, so the stale cached session does not matter.
    - On 409 it refreshes the session with `checkSession(true)` and opens the return URL. If the refresh fails, the guards of the next page still route the user correctly, and no loop is possible because `totpEnrollmentGuard` always asks the server again.
    - This matches the server: `TotpEnrollmentService` throws `ConflictException` when re-enrollment is not required, and confirm ends the other sessions.
    - Tests cover 401 on start and on confirm, 401 with return URL `/`, 409 on start and on confirm, and 409 with a failing refresh.
- **Earlier CR-2 (no way to get a new key) is fixed.**
    - A "Create a new key" button calls `start()`. It is disabled while a start or a confirmation is running.
    - After a 400 the page shows a hint that the key may have been replaced. The hint is not shown after a 429, and a new start clears it.
    - `confirm()` refuses to submit while a new key is being created, so no throttle attempt is spent on the old key.
    - Each of these cases has a test.
- **Criterion 1 is implemented.**
    - In password-only mode the login sends neither `totp` nor `recoveryCode`.
    - If the login response or `/me` reports `totpEnrollmentRequired`, the user goes to `/enroll-totp` through `Login.submit`, `authGuard` and `loginGuard`. The return URL is kept and sanitized.
    - The app-route test `leads a login that needs TOTP enrollment through /enroll-totp back to the return URL` covers the whole flow. Another app-route test checks that the shell, the only place that calls feature APIs, is never rendered for an enrollment session, so no 403 toasts appear.
- **Criterion 2 is implemented.** The page starts enrollment on open, shows the QR code and the key, confirms with the code, and then opens the return URL. A wrong code shows the server's field validation message (tested, including several messages joined together).
- `safeReturnUrl` now compares only the path, so `/login#x`, `/setup?step=2` and `/enroll-totp...` are rejected, while `/logins` and `/enroll-totp-help` are still allowed. All of these cases are tested.
- `app.routes.ts` and `app.spec.ts` are outside the task's `touches`, but the route is required and the change is covered by a recorded assumption.
- **No evidence is recorded yet.** `python tasks.py verify web-totp-reenroll` shows all three criteria without evidence. The parent must record evidence before running `done`. Criterion 3 needs an actual `cd web && npm run check` run on this revision. fixes.md reports a passing run.

## Review conclusion

No material issues found. CR-1 is a low-priority improvement and does not block the acceptance criteria.
