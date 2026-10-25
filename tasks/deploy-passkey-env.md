---
title: Forward COCKPIT_AUTH_PASSKEY_* to the server in deploy
status: in_progress
owner: r1963macos/agent-2
deps: [passkeys-server]
touches: [deploy/docker-compose.yml,deploy/.env.example,deploy/README.md]
prio: 2
lease_until: 2026-10-25T22:48:46Z
releases: 1
extended: 
---
## Goal
The Android app's passkey origin (`android:apk-key-hash:...`) and optional RP overrides reach the server in the
production stack. `passkeys-server` defaults the RP ID and web origin from `COCKPIT_DOMAIN`, but
`deploy/docker-compose.yml` only forwards a fixed list of variables (found in review CR-1 of passkeys-server).

## Acceptance criteria
- [x] `deploy/docker-compose.yml` forwards `COCKPIT_AUTH_PASSKEY_RP_ID`, `COCKPIT_AUTH_PASSKEY_ORIGINS`, `COCKPIT_AUTH_PASSKEY_RP_NAME` (empty by default) to the server; `deploy/.env.example` and `deploy/README.md` document them (placeholder Android hash only)
  - verified (2026-10-25 23:14+0100 by r1963macos/agent-2): git show 939dae22 -- deploy docs: docker-compose.yml server.environment has COCKPIT_AUTH_PASSKEY_RP_ID/ORIGINS (${..:-} empty) and RP_NAME (${..:-My Cockpit}, see assumption); .env.example has all three empty with placeholder android:apk-key-hash:REPLACE_WITH_APK_KEY_HASH; README.md Configuration table + Android origin example + keytool|python3 hash one-liner (tested against fake keytool output -> valid base64url)
  - verified (2026-10-25 23:25+0100 by r1963macos/agent-2): git show 727052f9 2dc672c0 -- deploy docs: compose server.environment forwards COCKPIT_AUTH_PASSKEY_RP_ID/ORIGINS (empty default) and RP_NAME (default 'My Cockpit', see assumption); .env.example has all three empty, placeholder android:apk-key-hash:REPLACE_WITH_APK_KEY_HASH only; README table + Android origin example + apksigner|python3 hash filter -> python3 -I .tmp/tw-dpe/check_readme_filter.py: 8/8 pass on sample apksigner/keytool output (no real APK/SDK here, keytool -genkeypair denied)
- [x] `docker compose -f deploy/docker-compose.yml config` with a sample `.env` shows the variables on the server service
  - verified (2026-10-25 23:15+0100 by r1963macos/agent-2): docker compose -f deploy/docker-compose.yml --env-file .tmp/sample.env config --format json (sample = .env.example with RP_ID=example.org, ORIGINS=https://cockpit.example.org,android:apk-key-hash:REPLACE_WITH_APK_KEY_HASH) -> services.server.environment has COCKPIT_AUTH_PASSKEY_RP_ID=example.org, ORIGINS=that list, RP_NAME=My Cockpit; with --env-file deploy/.env.example -> RP_ID='', ORIGINS='', RP_NAME='My Cockpit'

## Notes
- ASSUMPTION (2026-10-25 23:14+0100 by r1963macos/agent-2): docker-compose defaults COCKPIT_AUTH_PASSKEY_RP_NAME to 'My Cockpit' instead of empty: Spring takes an empty env var literally (@Value default only applies when unset), so forwarding '' would give the authenticator an empty RP name. RP_ID and ORIGINS are forwarded empty (the server treats blank as unset); .env.example leaves all three empty.
- release by r1963macos/agent-2: Complete except done: both criteria verified, test-writer/code-reviewer/fixer reports + quality.json committed (reviewed_revision 2dc672c0, CR-1 fixed, re-reviewed clean). Only blocker: .taskcheck on r1963macos fails because the Docker daemon is down (369 server Testcontainers tests: 'Could not find a valid Docker environment'; Android SDK also missing). A machine with Docker only needs to merge wip/deploy-passkey-env and run 'python tasks.py done deploy-passkey-env'.
