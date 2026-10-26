# Code Review

Date: 2026-10-26

Task: deploy-oauth-env - Pass COCKPIT_OAUTH_PUBLICURL to the server in deploy compose

Scope: `git diff 50c8dd50..0fbcd7ce` (task commit 0fbcd7ce, files `deploy/docker-compose.yml`, `deploy/.env.example`)

Requirements:
- tasks/deploy-oauth-env.md (acceptance criteria 1 and 2)
- docs/oauth-setup.md (`cockpit.oauth.public-url`, `COCKPIT_OAUTH_PUBLICURL` override)
- server/src/main/kotlin/ch/sonensei/cockpit/core/oauth/infra/OAuthProperties.kt
- server/src/main/resources/application.properties
- deploy/README.md, AGENTS.md, docs/QUALITY.md

Git range:
- Branch: main
- HEAD: 0fbcd7ce
- Base/start: 50c8dd501b49c559e1f65ce14d26901180aab9e6
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Verification performed by the reviewer

- Binding: `application.properties` sets `cockpit.oauth.public-url=https://${COCKPIT_DOMAIN:localhost}`.
  The environment variable `COCKPIT_OAUTH_PUBLICURL` maps to `cockpit.oauth.publicurl` under Spring Boot
  relaxed binding, which matches the canonical form of `cockpit.oauth.public-url`. OS environment variables
  take precedence over `application.properties`, so the compose value reaches `OAuthProperties.publicUrl`
  and `redirectUri()`.
- Default: `${COCKPIT_OAUTH_PUBLICURL:-https://${COCKPIT_DOMAIN}}` uses `:-`, so the variable unset or empty
  (for example an uncommented `COCKPIT_OAUTH_PUBLICURL=` in `.env`) falls back to `https://$COCKPIT_DOMAIN`.
  That is the same as the server default. `COCKPIT_DOMAIN` is required by the caddy service (`:?`),
  so the fallback never resolves to `https://`.
- Ran `docker compose --env-file /dev/null -f deploy/docker-compose.yml config` (Docker Compose v5.6.1)
  from a script in `.tmp/`, with `COCKPIT_DOMAIN=cockpit.example.test` and dummy `COCKPIT_MASTER_KEY`/`POSTGRES_PASSWORD`:
  - `COCKPIT_OAUTH_PUBLICURL=https://example.test:8443` -> server env `COCKPIT_OAUTH_PUBLICURL: https://example.test:8443`
  - unset -> `COCKPIT_OAUTH_PUBLICURL: https://cockpit.example.test`
  - set to empty -> `COCKPIT_OAUTH_PUBLICURL: https://cockpit.example.test`
- `.env.example` documents the variable in the "Optional: OAuth apps" block right above the GOOGLE_*/MS_*
  variables, commented out so that the default applies. The example and callback path agree with
  docs/oauth-setup.md and `OAuthProperties.redirectUri`.
- No secrets in the diff. The change stays within the task's `touches`. Infra-only config, so no unit
  tests are expected and none were changed.

Notes (not findings):
- Acceptance criterion 2 is not yet recorded via `tasks.py verify`. The reviewer's run above shows it holds.
  The parent worker must record its own evidence.
- The `deploy/README.md` rootless-Podman example (ports 18080/18443) does not mention
  `COCKPIT_OAUTH_PUBLICURL`. The file is outside this task's `touches`, and `.env.example` and
  docs/oauth-setup.md already cover it, so this is not reported as a defect.
- The nested default `${A:-https://${B}}` was verified with Docker Compose only. It was not verified with
  `podman-compose`, which deploy/README.md lists as an alternative and which is not installed here.

## Review conclusion

No material issues found.
