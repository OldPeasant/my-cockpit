---
title: Web forced TOTP re-enrollment after restore
status: todo
owner:
deps: [backup-secrets,p0-web-auth]
touches: [web/src/app/features/auth,web/src/app/core/auth]
prio: 1
---
## Goal
After a restore without secrets the server requires the owner to set up TOTP again (`totpEnrollmentRequired`
in the login and `GET /api/v1/auth/me` responses; every other endpoint answers 403 until done). The web client
must lead the owner through it (SPEC.md §10.2, §4.1).

## Acceptance criteria
- [ ] Login accepts password + recovery code (or password only when the server allows it) and, when
      `totpEnrollmentRequired` is true, routes to an enrollment screen instead of the app
- [ ] Enrollment screen: starts enrollment (QR code + secret), confirms with a TOTP code, then opens the app;
      wrong code shows the server's validation error
- [ ] `cd web && npm run check` passes, with unit tests for the new flow

## Notes
- From backup-secrets review (CR-1): `web/src/app/features/auth/login/login.ts` ignores `totpEnrollmentRequired`
  and always requires a TOTP/recovery code. Server endpoints: `startTotpEnrollment`, `confirmTotpEnrollment`
  in the generated client (`web/src/app/api/generated/fn/auth/`).
