---
title: OAuth connections for Google and Microsoft
status: in_progress
owner: dell26/agent-1
deps: [p0-crypto,p0-auth-login,p0-settings]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/oauth,server/src/test/kotlin/ch/sonensei/cockpit/core/oauth,server/src/main/resources/db/migration/core-oauth,docs/oauth-setup.md]
prio: 1
lease_until: 2026-10-20T14:30:21Z
releases: 1
extended: 
---
## Goal
One OAuth connection per provider account, reused by calendar, mail, contacts (SPEC.md §7.1).

## Acceptance criteria
- [x] `/api/v1/oauth/{google|microsoft}/start?purpose=` (authorization code + PKCE, state bound to the session) and `/callback`; combined scopes for calendar/mail/contacts; refresh tokens encrypted; access tokens refreshed before expiry
  - verified (2026-10-20 16:12+0200 by dell26/agent-1): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.oauth.*' --tests '*OpenApiContractTest' (HEAD 94ebf125, Postgres Testcontainers + WireMock) -> 61 tests, 0 failures; covered by OAuthFlowWebTests: 'start redirects to the provider with PKCE, state and the combined scopes', 'a state from another browser session is rejected and consumed', 'callback exchanges the code with the PKCE verifier and stores the refresh token encrypted', OAuthTokenProviderTests 'the access token of the callback is reused until shortly before it expires, then refreshed'
- [x] `OAuthTokenProvider.accessToken(connectionId)` for modules; revoke/disconnect endpoint; connection list with status
  - verified (2026-10-20 16:12+0200 by dell26/agent-1): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.oauth.*' --tests '*OpenApiContractTest' (HEAD 94ebf125, Postgres Testcontainers + WireMock) -> 61 tests, 0 failures; OAuthTokenProviderTests (accessToken(id) and accessToken(id, MAIL)), OAuthConnectionsWebTests 'the list shows each connection with its status and never a token', 'disconnect needs If-Match, revokes at Google and removes the connection'
- [x] Tests with WireMock as the authorization server (state mismatch rejected, refresh flow); `docs/oauth-setup.md` explains registering the Google and Azure apps and env vars
  - verified (2026-10-20 16:12+0200 by dell26/agent-1): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.oauth.*' --tests '*OpenApiContractTest' (HEAD 94ebf125, Postgres Testcontainers + WireMock) -> 61 tests, 0 failures; WireMock is the token/revocation server for both providers (OAuthWebTestBase): 'a state that was never issued is rejected without a token request', 'a state is rejected at the other provider, after expiry and on replay', refresh tests incl. 'a rotated refresh token replaces the stored one', 'a revoked refresh token marks the connection reconnect required'; read docs/oauth-setup.md -> sections Redirect URI, Google, Microsoft (Azure app registration), Environment variables
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.oauth.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-20 14:42+0200 by dell26/agent-4): Migration is in db/migration/core/ (V202610201300__core_oauth.sql), not the core-oauth folder named in touches: MigrationConventionTests require the folder name to equal the [a-z0-9]+ module prefix of the file name.
- ASSUMPTION (2026-10-20 14:42+0200 by dell26/agent-4): The state is bound to the browser session via a hash of the COCKPIT_SESSION cookie token; /start without a session cookie answers 401, and /callback stays behind the normal login (an expired session during consent gets a plain 401).
- ASSUMPTION (2026-10-20 14:42+0200 by dell26/agent-4): purpose (CALENDAR|MAIL|CONTACTS) does not change the scopes (always combined); it is only passed back to the SPA. The callback redirects to /settings/integrations?oauth=connected|error&provider=&purpose=&connectionId=|error= (page does not exist yet; web-cal-sources/settings tasks build it).
- ASSUMPTION (2026-10-20 14:42+0200 by dell26/agent-4): Microsoft issues tokens per resource, so OAuthTokenProvider has accessToken(id) (Graph/Google APIs) plus accessToken(id, OAuthResource.MAIL) for IMAP/SMTP XOAUTH2 (outlook.office.com scopes via the same refresh token). Access tokens are cached in memory only, refreshed 5 min (max half the lifetime) before expiry.
- ASSUMPTION (2026-10-20 14:42+0200 by dell26/agent-4): Account identity comes from the unsigned-checked ID token of the token response (OIDC Core 3.1.3.7, TLS channel); one connection per (provider, sub); reconnecting the same account updates it.
- ASSUMPTION (2026-10-20 14:42+0200 by dell26/agent-4): Disconnect = DELETE /api/v1/oauth/connections/{id} with If-Match; publishes OAuthConnectionDisconnected synchronously before deleting (modules referencing a connection must handle it or use on delete set null), then revokes at Google best effort; Microsoft has no revocation endpoint. Background refreshes do not bump the version (native update), so ETags stay stable.
- ASSUMPTION (2026-10-20 14:42+0200 by dell26/agent-4): invalid_grant on refresh drops the refresh token and sets status RECONNECT_REQUIRED; other OAuth errors only record lastError; network/5xx errors leave the connection unchanged. Added GET /api/v1/oauth/providers (configured flags) and GET /connections/{id} for the UI.
- release by dell26/agent-4: Implementation complete and committed (OAuth start/callback with PKCE + session-bound state, encrypted refresh tokens, OAuthTokenProvider with pre-expiry refresh, disconnect/revoke, connection list, providers endpoint, WireMock tests, docs/oauth-setup.md, regenerated api/openapi.yaml + web client). CR-1 fixed; test-writer/code-reviewer/fixer reports in reviews/oauth-connections/ (written for pre-squash commit dd2a7a6). Work is squashed into one commit on main (pull --rebase replayed generated-file conflicts per commit); sh scripts/check.sh passes on that tree. LEFT: (1) check report freshness per docs/QUALITY.md, re-run test-writer + code-reviewer if stale; (2) run ./gradlew test --tests 'ch.sonensei.cockpit.core.oauth.*' and OpenApiContractTest, record tasks.py verify for criteria 1-5; (3) write and commit reviews/oauth-connections/quality.json; (4) tasks.py done.
