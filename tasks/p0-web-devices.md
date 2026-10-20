---
title: Settings: security and devices
status: in_progress
owner: dell26/agent-4
deps: [p0-web-settings,p0-auth-tokens]
touches: [web/src/app/features/settings/security]
prio: 2
lease_until: 2026-10-20T14:26:16Z
---
## Goal
Show and revoke Android devices; regenerate recovery codes.

## Acceptance criteria
- [ ] Lists devices (name, last used) with revoke + confirm dialog
- [ ] Server: `POST /api/v1/auth/recovery-codes` (requires current TOTP) regenerates codes; UI shows them once
- [ ] Vitest component/service tests cover device list, revoke flow; `cd web && npm run check` passes
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` passes (Postgres via Testcontainers where persistence is involved) for the recovery code endpoint

## Notes
- ASSUMPTION (2026-10-20 15:45+0200 by dell26/agent-4): POST /api/v1/auth/recovery-codes shares the global LoginThrottle with login (wrong TOTP counts as a failed attempt, 429 + Retry-After when blocked) so a stolen session cannot brute-force TOTP; wrong TOTP is a 400 with field 'totp', not 401, so the SPA does not treat it as a logout
- ASSUMPTION (2026-10-20 15:45+0200 by dell26/agent-4): Regenerating recovery codes deletes all old codes (used and unused) and returns 10 new ones; the UI holds them only in component state until 'I saved them' or leaving the page (copy button, no download)
- ASSUMPTION (2026-10-20 15:45+0200 by dell26/agent-4): Edited settings.routes.ts (outside touches) to replace the Security placeholder with the new section, as the routes file says section tasks do
