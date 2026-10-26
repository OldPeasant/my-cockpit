---
title: HUMAN: register Azure OAuth app (Microsoft)
status: dropped
owner: 
deps: [oauth-connections]
quality: exempt
quality_reason: human configuration task, no code
hold: 
prio: 2
lease_until: 
extended: 
---
## Goal
Owner registers the Microsoft Entra (Azure) app following docs/oauth-setup.md. Nothing else waits for it:
until then Microsoft is disabled (`GET /api/v1/oauth/providers` shows `configured: false`).

## Acceptance criteria
- [ ] App registration with Web redirect URI `https://<COCKPIT_DOMAIN>/api/v1/oauth/microsoft/callback`, delegated Graph permissions as in docs/oauth-setup.md, client secret (expiry noted); `MS_CLIENT_ID`/`MS_CLIENT_SECRET`/`MS_TENANT` in the production `.env`

## Notes
- dropped by Peter Gürtler: Microsoft deferred (ADR 0003)
