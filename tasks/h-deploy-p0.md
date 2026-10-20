---
title: HUMAN: first deploy on the owner's server
status: todo
owner:
deps: [p0-smoke]
quality: exempt
quality_reason: human deployment task, no code
hold: HUMAN TASK for the owner: deploy to the server (SPEC.md §13.3). Claim with TASK_OWNER=<you> python tasks.py claim h-deploy-p0 --force
prio: 2
---
## Goal
The owner deploys the stack with the real domain (SPEC.md §13.3).

## Acceptance criteria
- [ ] DNS record points to the server; `deploy/gen-env.sh` run; `docker compose up -d`; valid TLS certificate
- [ ] Setup + TOTP login works on the production URL (demo Phase 0 step 6)

## Notes
