# Fix Report

Date: 2026-10-22

Task: web-totp-reenroll - Web forced TOTP re-enrollment after restore

Scope: current task; findings CR-1 and CR-2 (both P3) from `reviews/web-totp-reenroll/review.md`, approved for fixing by the parent worker. Changes stay in `web/src/app/features/auth`.

Note: an earlier fixer run was interrupted before it wrote a report. Its uncommitted working-tree changes in `web/src/app/features/auth/totp-enrollment/` were reviewed against both findings and the server contract (`TotpEnrollmentService`, `TotpEnrollmentController`, `AuthService`, the auth guards). They were adopted as they were, plus one small addition (see CR-2).

## Fixed findings

### CR-1 — Enrollment page does not react to 401/409 from start or confirm

Files changed:
- `web/src/app/features/auth/totp-enrollment/totp-enrollment.ts`
- `web/src/app/features/auth/totp-enrollment/totp-enrollment.spec.ts`

Problem:
Both `start()` and `confirm()` showed every `ApiError` as text. A 401 (the session ended because another session confirmed first) or a 409 (re-enrollment no longer required, e.g. confirmed in another tab) left the user on a page where "Try again" repeated the same error, and `AuthService` still cached `totpEnrollmentRequired = true`.

Fix (adopted from the working tree):
- New private `leaveOnSessionChange(error)`, called from the `catch` blocks of `start()` and `confirm()`.
- On 401 it navigates to the login page and keeps the safe return URL (no `returnUrl` when it is `/`). `loginGuard` asks the server again (`checkSession(true)`), so the stale cache does not matter.
- On 409 it calls `auth.checkSession(true)` to refresh the cached session, then navigates to `safeReturnUrl(...)`. If the session is gone, `authGuard` sends the user to login. If the refresh fails, the error is swallowed (the interceptor already showed a toast) and the guards of the next page decide.
- `returnUrl()` helper, shared with the success path.

Tests (adopted): 409 on confirm leads to the return URL after `checkSession(true)`. 409 on start leads to `/` for an unsafe return URL. 409 still leaves the page when the refresh fails. 401 on confirm and on start leads to `/login?returnUrl=...`. 401 with return URL `/` leads to `/login`. The existing "offers sign out while starting failed" test now uses a 502, because a 409 no longer stays on the page.

Validation:
- `cd web && npx ng test --watch=false --include src/app/features/auth --include src/app/core/auth` — Pass (7 files, 155 tests)

### CR-2 — No way to create a new key once one is shown

Files changed:
- `web/src/app/features/auth/totp-enrollment/totp-enrollment.html`
- `web/src/app/features/auth/totp-enrollment/totp-enrollment.ts`
- `web/src/app/features/auth/totp-enrollment/totp-enrollment.spec.ts`

Problem:
After another `start` replaced the pending secret, the user only got repeated "is not a valid code for the new TOTP secret" errors. Each error counted against the login throttle, and nothing told the user to create a new key.

Fix:
- Adopted from the working tree:
  - A secondary "Create a new key" button (`button.new-key`, i18n `@@totpEnrollment.newKey`) in the confirm form. It calls `start()` and is disabled while starting or submitting.
  - A hint (`.key-replaced`, i18n `@@totpEnrollment.keyReplaced`) shown after a 400 from confirm. It says the key may have been replaced and suggests creating a new one. It is cleared on every new start or confirm attempt and is not shown for a 429.
- Added in this run: `confirm()` now also returns early while `starting()` is true, and the submit button is disabled then. Without this, the new button would let the user submit a code for the old key while the new key is being created, which costs another throttle attempt.

Tests: a new key is created on request and shown. The hint appears after a 400 and is cleared by a new key. No hint for a 429. "Create a new key" is disabled during a confirmation. New in this run: no confirmation is sent while a new key is being created.

Validation:
- `cd web && npx ng test --watch=false --include src/app/features/auth/totp-enrollment` — Pass (1 file, 30 tests)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include src/app/features/auth --include src/app/core/auth` — Pass (7 files, 155 tests)
- `cd web && npx ng test --watch=false --include src/app/features/auth/totp-enrollment` — Pass (30 tests)

Full test suite:
- `cd web && npm run check` — Pass (lint clean; 134 test files, 2750 tests)

Lint/type-check/build:
- `cd web && npm run lint` — Pass ("All files pass linting.", `--max-warnings=0`)

`.taskcheck`:
- `sh scripts/check.sh` — Pass ("== all checks passed", exit 0). It took more than 10 minutes because other agents' Gradle builds were running on the same machine at the same time.

Remaining unresolved findings:
0

Notes:
- The new i18n ids (`totpEnrollment.keyReplaced`, `totpEnrollment.newKey`) are not extracted to `messages.xlf`. This follows the task's recorded assumption that extraction is left to a dedicated extract.

## Final status

All validated findings fixed and validation passed.
