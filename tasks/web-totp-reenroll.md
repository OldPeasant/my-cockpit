---
title: Web forced TOTP re-enrollment after restore
status: done
owner: dell26/agent-3
deps: [backup-secrets,p0-web-auth,app-logo]
touches: [web/src/app/features/auth,web/src/app/core/auth]
prio: 1
lease_until: 
---
## Goal
After a restore without secrets the server requires the owner to set up TOTP again (`totpEnrollmentRequired`
in the login and `GET /api/v1/auth/me` responses; every other endpoint answers 403 until done). The web client
must lead the owner through it (SPEC.md §10.2, §4.1).

## Acceptance criteria
- [x] Login accepts password + recovery code (or password only when the server allows it) and, when
      `totpEnrollmentRequired` is true, routes to an enrollment screen instead of the app
  - verified (2026-10-22 03:29+0200 by dell26/agent-3): login.spec.ts + auth.guards.spec.ts + app.spec.ts (route flow: password+recovery code, password-only mode, totpEnrollmentRequired -> /enroll-totp, shell never loaded) via cd web && npx ng test --watch=false --include src/app/features/auth --include src/app/core/auth --include src/app/app.spec.ts -> 207 passed (test-writer run on 3008661a+tests)
- [x] Enrollment screen: starts enrollment (QR code + secret), confirms with a TOTP code, then opens the app;
      wrong code shows the server's validation error
  - verified (2026-10-22 03:29+0200 by dell26/agent-3): totp-enrollment.spec.ts (38 tests: auto start, QR + secret shown, confirm 204 -> opens returnUrl, 400 shows server field message, 401/409/new key) in sh scripts/check.sh on 5beb4fbc -> web 2758 tests passed
- [x] `cd web && npm run check` passes, with unit tests for the new flow
  - verified (2026-10-22 03:29+0200 by dell26/agent-3): sh scripts/check.sh (includes cd web && npm run check: api:check, lint, e2e typecheck, test:ci, build) on 5beb4fbc -> 'All files pass linting', 134 files / 2758 tests passed, all checks passed, exit 0

## Notes
- From backup-secrets review (CR-1): `web/src/app/features/auth/login/login.ts` ignores `totpEnrollmentRequired`
  and always requires a TOTP/recovery code. Server endpoints: `startTotpEnrollment`, `confirmTotpEnrollment`
  in the generated client (`web/src/app/api/generated/fn/auth/`).
- ASSUMPTION (2026-10-22 02:42+0200 by dell26/agent-3): Password-only login is a third login mode reached from the recovery-code view ('No recovery code left after a restore?'), not an empty recovery code field: the client cannot know when the server allows it, and an accidental empty submit stays blocked; a 401 in that mode says a recovery code may still be needed.
- ASSUMPTION (2026-10-22 02:42+0200 by dell26/agent-3): Enrollment page lives at /enroll-totp (new route in app.routes.ts, outside touches but required) and starts the enrollment automatically on open; it keeps returnUrl and offers Sign out. messages.xlf is not re-extracted (left to a dedicated extract, as other tasks do).
- ASSUMPTION (2026-10-22 03:29+0200 by dell26/agent-3): Accepted review finding CR-1 (P3): the /enroll-totp guard sends the user to /login without returnUrl when the server cannot be reached; rare outage edge case, left as is.
- done by dell26/agent-3: `sh scripts/check.sh` passed in 83s on c2e77bb12; criteria verified 3/3
