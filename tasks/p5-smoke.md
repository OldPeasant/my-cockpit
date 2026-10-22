---
title: Phase 5 demo script
status: in_progress
owner: dell26/agent-2
deps: [web-mail-accounts,web-mail-compose,mail-idle,mail-search,backup-contrib-mail,and-mail-compose,p4-smoke]
touches: [web/e2e/phase5]
prio: 1
lease_until: 2026-10-22T09:27:46Z
---
## Goal
Automate SPEC.md §13.2 Phase 5 against GreenMail.

## Acceptance criteria
- [ ] `web/e2e/phase5` covers steps 1–6 (e2e stack includes GreenMail); earlier phase specs pass

## Notes
- ASSUMPTION (2026-10-22 11:10+0200 by dell26/agent-2): Step 2 needs mail arriving via SMTP, so GreenMail's SMTP port is now published on the host (E2E_SMTP_PORT in deploy/docker-compose.e2e.yml + run-e2e.mjs, outside touches) and the spec delivers with a small SMTP client (web/e2e/phase5/smtp.ts)
