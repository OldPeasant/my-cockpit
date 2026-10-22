# Code Review

Date: 2026-10-22

Task: web-totp-reenroll - Web forced TOTP re-enrollment after restore

Scope: `git diff d566559d..d77fd814 -- web` (committed changes only; the test-writer's uncommitted spec additions in the working tree were read for context but are not part of the reviewed range)

Requirements:
- tasks/web-totp-reenroll.md acceptance criteria and recorded assumptions (password-only as a third login mode, auto-start on page open, /enroll-totp route, messages.xlf not re-extracted)
- SPEC.md §4.1, §10.2 (secrets omitted without a backup passphrase, TOTP re-enrollment forced on first login)
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions
- Server contract: server/src/main/kotlin/ch/sonensei/cockpit/core/auth/domain/LoginService.kt, domain/TotpEnrollmentService.kt, api/TotpEnrollmentController.kt

Git range:
- Branch: main
- HEAD: d77fd814
- Base/start: d566559d47b65a84b1419e68a26bd3c5ef2bfd93
- Commits reviewed: 3 (154242f2, 13e9efea, d77fd814)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Enrollment page does not react to 401/409 from start or confirm; the user gets stuck with a generic error**
    - File: `web/src/app/features/auth/totp-enrollment/totp-enrollment.ts:63-67` and `:88-93`
    - Category: Correctness
    - Problem: Both calls use `quiet()` (no login redirect, no toast), and every `ApiError` is only shown as text. The server ends this session when another session confirms (`TotpEnrollmentService.confirm` deletes the other sessions), and it answers 409 when re-enrollment is no longer required (for example another tab of the same session already confirmed). In those cases the page shows "The request failed (HTTP 401)." or "TOTP re-enrollment is not required". "Try again" then repeats the same 401/409. `AuthService` also keeps `totpEnrollmentRequired` cached as true, so the page cannot move on by itself.
    - Why it matters: The screen can still be recovered through "Sign out" or a reload, but neither the message nor the retry button tells the user what to do. A 401 for an ended session normally leads to the login page.
    - Suggested fix: On 401, navigate to the login page and keep the return URL. On 409, call `auth.checkSession(true)` and navigate to `safeReturnUrl(...)`, or to the login page when the session is gone. Add a test for each case.

- [ ] **CR-2 | No way to create a new key once one is shown; a replaced secret only produces repeated "invalid code" errors**
    - File: `web/src/app/features/auth/totp-enrollment/totp-enrollment.html:8-60` (the `start()` retry button only exists in the `startError()` branch)
    - Category: Correctness
    - Problem: The server comment says that when another `start` replaces this session's pending secret, a confirm gets an invalid-code error and the session "must start again" (`TotpEnrollmentService`, class comment). Auto-start makes this easy to trigger: a second tab or a reload on another device creates a new secret. The page offers no "new key" action while a key is shown, and the server's field message ("is not a valid code for the new TOTP secret") does not say that the key may have been replaced. Every further attempt also counts against the shared login throttle, which leads to 429.
    - Why it matters: The user can scan a stale QR code and burn throttle attempts without knowing that a reload would fix it.
    - Suggested fix: Add a secondary "Create a new key" button (calls `start()`) next to the confirm form, or add a hint after a 400 that the key may have been replaced and the page can create a new one. Cover it with a test.

## Notes (not findings)

- Criteria 1 and 2 are implemented and match the server contract:
    - Password-only mode sends neither `totp` nor `recoveryCode`, which is what `LoginService.checkSecondFactor` expects.
    - `totpEnrollmentRequired` from login and `/me` sends the user to `/enroll-totp`.
    - The new secret is shown as a QR code and as the key.
    - Confirm shows the server's field validation error and opens the app after a 204.
- The guards keep an enrollment session out of the shell. The shell holds every feature provider that calls the API, so no other endpoint is called and no 403 toast can appear during enrollment.
- `safeReturnUrl` rejects absolute and protocol-relative URLs and the `/enroll-totp` loop. It is tested.
- `python tasks.py verify web-totp-reenroll` showed no evidence recorded for any criterion yet. Record evidence before `done`; criterion 3 needs an actual `cd web && npm run check` run.

## Review conclusion

No material issues found. CR-1 and CR-2 are low-priority resilience improvements and do not block the acceptance criteria.
