---
title: Web passkeys
status: todo
owner:
deps: [passkeys-server,p0-web-devices,p0-web-auth]
touches: [web/src/app/features/settings/security,web/src/app/features/auth]
prio: 2
---
## Goal
Use passkeys in the browser.

## Acceptance criteria
- [ ] Register/delete passkeys in Security settings; 'Sign in with a passkey' on login
- [ ] Vitest component/service tests cover ceremony error handling; `cd web && npm run check` passes
- [ ] Playwright `e2e/passkeys/passkeys.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/passkeys/passkeys.spec.ts`, see p0-e2e-harness): virtual authenticator (CDP) registers and logs in

## Notes
