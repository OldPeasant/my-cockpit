---
title: Settings: security and devices
status: todo
owner:
deps: [p0-web-settings,p0-auth-tokens]
touches: [web/src/app/features/settings/security]
prio: 2
---
## Goal
Show and revoke Android devices; regenerate recovery codes.

## Acceptance criteria
- [ ] Lists devices (name, last used) with revoke + confirm dialog
- [ ] Server: `POST /api/v1/auth/recovery-codes` (requires current TOTP) regenerates codes; UI shows them once
- [ ] Vitest component/service tests cover device list, revoke flow; `cd web && npm run check` passes
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` passes (Postgres via Testcontainers where persistence is involved) for the recovery code endpoint

## Notes
