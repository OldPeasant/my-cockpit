---
title: HUMAN: register Google OAuth app
status: todo
owner:
deps: [oauth-connections]
quality: exempt
quality_reason: human configuration task, no code
hold: HUMAN TASK for the owner: put the Google client id/secret into the production .env (docs/oauth-setup.md)
prio: 2
---
## Goal
Owner registers the Google OAuth app following docs/oauth-setup.md (SPEC.md §13.3). Microsoft/Azure is
h-oauth-azure, done later; until then Microsoft stays disabled (no MS_CLIENT_ID).

## Acceptance criteria
- [ ] Google client id/secret in the production `.env`; consent screen configured; redirect URI matches

## Notes
- 2026-10-20: Google Cloud project, consent screen and web client registered by the owner; .env entry pending the deploy
