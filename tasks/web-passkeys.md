---
title: Web passkeys
status: in_progress
owner: dell26/agent-1
deps: [passkeys-server,p0-web-devices,p0-web-auth,web-totp-reenroll]
touches: [web/src/app/features/settings/security,web/src/app/features/auth]
prio: 2
lease_until: 2026-10-22T10:07:29Z
---
## Goal
Use passkeys in the browser.

## Acceptance criteria
- [ ] Register/delete passkeys in Security settings; 'Sign in with a passkey' on login
- [ ] Vitest component/service tests cover ceremony error handling; `cd web && npm run check` passes
- [ ] Playwright `e2e/passkeys/passkeys.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/passkeys/passkeys.spec.ts`, see p0-e2e-harness): virtual authenticator (CDP) registers and logs in

## Notes
- ASSUMPTION (2026-10-22 11:52+0200 by dell26/agent-1): The WebAuthn browser helper (features/auth/passkey/webauthn.ts) is shared by login and Security settings, so settings/security imports it from features/auth; core/ was outside the task's touches. Options/credentials are converted to/from JSON by hand instead of parseCreationOptionsFromJSON/toJSON for older browsers.
- ASSUMPTION (2026-10-22 11:52+0200 by dell26/agent-1): Passkey login refreshes AuthService via checkSession(true) after loginWithPasskey instead of adding a method to core/auth (outside touches); costs one extra GET /auth/me. The 'remember me' checkbox of the login form also applies to passkey login. The passkey button is hidden when the browser has no WebAuthn.
