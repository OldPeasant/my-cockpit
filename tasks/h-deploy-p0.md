---
title: HUMAN: first deploy on the owner's server
status: todo
owner:
deps: [p0-smoke]
quality: exempt
quality_reason: human deployment task, no code
hold: 
prio: 2
releases: 0
extended: 
---
## Goal
The owner deploys the stack with the real domain (SPEC.md §13.3).

## Acceptance criteria
- [ ] DNS record points to the server; `deploy/gen-env.sh` run; `docker compose up -d`; valid TLS certificate
- [ ] Setup + TOTP login works on the production URL (demo Phase 0 step 6)

## Notes
- unhold by Peter Gürtler: Done
