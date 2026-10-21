---
title: Forward COCKPIT_AUTH_PASSKEY_* to the server in deploy
status: todo
owner:
deps: [passkeys-server]
touches: [deploy/docker-compose.yml,deploy/.env.example,deploy/README.md]
prio: 2
---
## Goal
The Android app's passkey origin (`android:apk-key-hash:...`) and optional RP overrides reach the server in the
production stack. `passkeys-server` defaults the RP ID and web origin from `COCKPIT_DOMAIN`, but
`deploy/docker-compose.yml` only forwards a fixed list of variables (found in review CR-1 of passkeys-server).

## Acceptance criteria
- [ ] `deploy/docker-compose.yml` forwards `COCKPIT_AUTH_PASSKEY_RP_ID`, `COCKPIT_AUTH_PASSKEY_ORIGINS`, `COCKPIT_AUTH_PASSKEY_RP_NAME` (empty by default) to the server; `deploy/.env.example` and `deploy/README.md` document them (placeholder Android hash only)
- [ ] `docker compose -f deploy/docker-compose.yml config` with a sample `.env` shows the variables on the server service

## Notes
