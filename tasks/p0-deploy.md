---
title: Docker image, compose stack, Caddy
status: in_progress
owner: dell26/agent-3
deps: [p0-web-deps,p0-api-conventions]
touches: [Dockerfile,.dockerignore,deploy,server/src/main/kotlin/ch/sonensei/cockpit/core/spa,server/src/test/kotlin/ch/sonensei/cockpit/core/spa]
prio: 1
lease_until: 2026-10-20T09:33:11Z
---
## Goal
Deployable stack (SPEC.md §11).

## Acceptance criteria
- [ ] Multi-stage `Dockerfile`: builds web with Node 24 and the server with JDK 21 (same versions as `.nvmrc`/toolchain), copies the web build into the server's static resources, runs as non-root; `docker build .` succeeds (record with the actual command, podman is fine)
- [ ] Server serves the SPA with a fallback to `index.html` for non-`/api` paths (test in `core/spa`)
- [ ] `deploy/docker-compose.yml` (caddy, server, postgres with volumes, data dir volume), `deploy/Caddyfile` (TLS for `COCKPIT_DOMAIN`, security headers: HSTS, CSP, X-Content-Type-Options, frame-ancestors), `deploy/.env.example`, `deploy/gen-env.sh` (random master key + db password)
- [ ] `docker compose -f deploy/docker-compose.yml up` with a generated env (localhost, internal TLS) answers `/actuator/health` UP through Caddy (record)
- [ ] `deploy/host-backup.sh` example (pg_dump + data dir via restic) and `deploy/README.md` with first-deploy steps

## Notes
- ASSUMPTION (2026-10-20 11:16+0200 by dell26/agent-3): Spring Security is still Boot's default (no SecurityFilterChain yet), so the SPA answers anonymous requests with 401; SpaFallbackTests run with @WithMockUser. Making the SPA public is p0-auth-login's criterion, so this task adds no security config.
- ASSUMPTION (2026-10-20 11:16+0200 by dell26/agent-3) [confirmed 2026-10-20 11:18+0200 by Peter Gürtler]: SPEC §11 says /actuator/health is internal only, but the criterion asks for health UP through Caddy: Caddy proxies only /actuator/health and answers 404 for every other /actuator/* path.
