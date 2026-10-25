---
title: Forward COCKPIT_AUTH_PASSKEY_* to the server in deploy
status: in_progress
owner: r1963macos/agent-2
deps: [passkeys-server]
touches: [deploy/docker-compose.yml,deploy/.env.example,deploy/README.md]
prio: 2
lease_until: 2026-10-25T22:33:39Z
---
## Goal
The Android app's passkey origin (`android:apk-key-hash:...`) and optional RP overrides reach the server in the
production stack. `passkeys-server` defaults the RP ID and web origin from `COCKPIT_DOMAIN`, but
`deploy/docker-compose.yml` only forwards a fixed list of variables (found in review CR-1 of passkeys-server).

## Acceptance criteria
- [ ] `deploy/docker-compose.yml` forwards `COCKPIT_AUTH_PASSKEY_RP_ID`, `COCKPIT_AUTH_PASSKEY_ORIGINS`, `COCKPIT_AUTH_PASSKEY_RP_NAME` (empty by default) to the server; `deploy/.env.example` and `deploy/README.md` document them (placeholder Android hash only)
- [ ] `docker compose -f deploy/docker-compose.yml config` with a sample `.env` shows the variables on the server service

## Notes
- ASSUMPTION (2026-10-25 23:14+0100 by r1963macos/agent-2): docker-compose defaults COCKPIT_AUTH_PASSKEY_RP_NAME to 'My Cockpit' instead of empty: Spring takes an empty env var literally (@Value default only applies when unset), so forwarding '' would give the authenticator an empty RP name. RP_ID and ORIGINS are forwarded empty (the server treats blank as unset); .env.example leaves all three empty.
