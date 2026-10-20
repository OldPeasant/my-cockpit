---
title: HUMAN: register Azure OAuth app (Microsoft)
status: todo
owner:
deps: [oauth-connections]
quality: exempt
quality_reason: human configuration task, no code
hold: HUMAN TASK for the owner: register the Azure app (docs/oauth-setup.md), later
prio: 2
---
## Goal
Owner registers the Microsoft Entra (Azure) app following docs/oauth-setup.md. Nothing else waits for it:
until then Microsoft is disabled (`GET /api/v1/oauth/providers` shows `configured: false`).

## Acceptance criteria
- [ ] App registration with Web redirect URI `https://<COCKPIT_DOMAIN>/api/v1/oauth/microsoft/callback`, delegated Graph permissions as in docs/oauth-setup.md, client secret (expiry noted); `MS_CLIENT_ID`/`MS_CLIENT_SECRET`/`MS_TENANT` in the production `.env`

## Notes
