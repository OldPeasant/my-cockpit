---
title: OAuth connections for Google and Microsoft
status: in_progress
owner: dell26/agent-4
deps: [p0-crypto,p0-auth-login,p0-settings]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/oauth,server/src/test/kotlin/ch/sonensei/cockpit/core/oauth,server/src/main/resources/db/migration/core-oauth,docs/oauth-setup.md]
prio: 1
lease_until: 2026-10-20T13:00:28Z
---
## Goal
One OAuth connection per provider account, reused by calendar, mail, contacts (SPEC.md §7.1).

## Acceptance criteria
- [ ] `/api/v1/oauth/{google|microsoft}/start?purpose=` (authorization code + PKCE, state bound to the session) and `/callback`; combined scopes for calendar/mail/contacts; refresh tokens encrypted; access tokens refreshed before expiry
- [ ] `OAuthTokenProvider.accessToken(connectionId)` for modules; revoke/disconnect endpoint; connection list with status
- [ ] Tests with WireMock as the authorization server (state mismatch rejected, refresh flow); `docs/oauth-setup.md` explains registering the Google and Azure apps and env vars
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.oauth.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
