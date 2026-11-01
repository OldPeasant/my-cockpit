---
title: HUMAN: first deploy on the owner's server
status: done
owner: Peter Gürtler
deps: [p0-smoke]
quality: exempt
quality_reason: human deployment task, no code
hold: 
prio: 2
releases: 0
extended: 
lease_until: 
---
## Goal
The owner deploys the stack with the real domain (SPEC.md §13.3).

## Acceptance criteria
- [x] DNS record points to the server; `deploy/gen-env.sh` run; `docker compose up -d`; valid TLS certificate
  - verified (2026-11-01 06:00+0100 by Peter Gürtler): The app works
- [x] Setup + TOTP login works on the production URL (demo Phase 0 step 6)
  - verified (2026-11-01 06:00+0100 by Peter Gürtler): It works in the running app

## Notes
- unhold by Peter Gürtler: Done
- done by Peter Gürtler: `sh scripts/check.sh` passed in 336s on 5aa862019; criteria verified 2/2
