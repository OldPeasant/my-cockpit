---
title: HUMAN: register Google OAuth app
status: in_progress
owner: Peter Gürtler
deps: [oauth-connections]
quality: exempt
quality_reason: human configuration task, no code
hold: 
prio: 2
releases: 0
extended: 
lease_until: 
---
## Goal
Owner registers the Google OAuth app following docs/oauth-setup.md (SPEC.md §13.3). Microsoft/Azure is
deferred (ADR 0003): no Azure app, no MS_CLIENT_ID.

## Acceptance criteria
- [ ] Google client id/secret in the production `.env`; consent screen configured; redirect URI matches

## Notes
- 2026-10-20: Google Cloud project, consent screen and web client registered by the owner; .env entry pending the deploy
