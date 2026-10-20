---
title: Pass COCKPIT_OAUTH_PUBLICURL to the server in deploy compose
status: todo
owner:
deps: [oauth-connections]
touches: [deploy/docker-compose.yml,deploy/.env.example]
prio: 2
---
## Goal
OAuth redirect URIs work when the stack runs on a non-standard HTTPS port (e.g. 8443 for rootless
Podman, as `deploy/.env.example` suggests). `docs/oauth-setup.md` tells owners to set
`COCKPIT_OAUTH_PUBLICURL` in that case, but `deploy/docker-compose.yml` does not pass it to the server
(found in the oauth-connections review, CR-3).

## Acceptance criteria
- [ ] `deploy/docker-compose.yml` passes `COCKPIT_OAUTH_PUBLICURL` to the server (unset = default `https://${COCKPIT_DOMAIN}`), and `deploy/.env.example` documents it next to the GOOGLE_*/MS_* variables
- [ ] `docker compose -f deploy/docker-compose.yml config` with `COCKPIT_OAUTH_PUBLICURL=https://example.test:8443` shows the value in the server environment, and without it the server falls back to the default

## Notes
