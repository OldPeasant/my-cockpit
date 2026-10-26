---
title: Pass COCKPIT_OAUTH_PUBLICURL to the server in deploy compose
status: in_progress
owner: dell26/agent-2
deps: [oauth-connections]
touches: [deploy/docker-compose.yml,deploy/.env.example]
prio: 2
lease_until: 2026-10-26T01:53:55Z
hold: 
releases: 0
extended: 
---
## Goal
OAuth redirect URIs work when the stack runs on a non-standard HTTPS port (e.g. 8443 for rootless
Podman, as `deploy/.env.example` suggests). `docs/oauth-setup.md` tells owners to set
`COCKPIT_OAUTH_PUBLICURL` in that case, but `deploy/docker-compose.yml` does not pass it to the server
(found in the oauth-connections review, CR-3).

## Acceptance criteria
- [x] `deploy/docker-compose.yml` passes `COCKPIT_OAUTH_PUBLICURL` to the server (unset = default `https://${COCKPIT_DOMAIN}`), and `deploy/.env.example` documents it next to the GOOGLE_*/MS_* variables
  - verified (2026-10-26 01:40+0100 by felix/agent-1): git diff 50c8dd50..0fbcd7ce: server env gets COCKPIT_OAUTH_PUBLICURL: ${COCKPIT_OAUTH_PUBLICURL:-https://${COCKPIT_DOMAIN}}; .env.example documents it in the OAuth block above GOOGLE_*/MS_*
- [x] `docker compose -f deploy/docker-compose.yml config` with `COCKPIT_OAUTH_PUBLICURL=https://example.test:8443` shows the value in the server environment, and without it the server falls back to the default
  - verified (2026-10-26 01:40+0100 by felix/agent-1): sh .tmp/cfg.sh (COCKPIT_DOMAIN=cockpit.example.org, docker compose v5.6.1 'config', --env-file /dev/null) -> with COCKPIT_OAUTH_PUBLICURL=https://example.test:8443: server env 'COCKPIT_OAUTH_PUBLICURL: https://example.test:8443'; unset: 'https://cockpit.example.org'; set empty: 'https://cockpit.example.org'

## Notes
- hold by felix/agent-1: Implementation, verify and review evidence are committed; only 'done' is left, but .taskcheck can't pass on host felix: JAVA_HOME=/usr/lib/jvm/java-21-openjdk doesn't exist (only JDK 25 is installed), so scripts/check.sh fails at the server stage. With JAVA_HOME=/usr/lib/jvm/java-25-openjdk the server suite ran >17 min unfinished, above the 15-min TASK_CHECK_TIMEOUT. Fix the host JDK 21 / JAVA_HOME (or run 'done' from a working host), then unhold; the change itself is deploy-only (compose + .env.example).
