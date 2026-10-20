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
- ASSUMPTION (2026-10-20 14:42+0200 by dell26/agent-4): Migration is in db/migration/core/ (V202610201300__core_oauth.sql), not the core-oauth folder named in touches: MigrationConventionTests require the folder name to equal the [a-z0-9]+ module prefix of the file name.
- ASSUMPTION (2026-10-20 14:42+0200 by dell26/agent-4): The state is bound to the browser session via a hash of the COCKPIT_SESSION cookie token; /start without a session cookie answers 401, and /callback stays behind the normal login (an expired session during consent gets a plain 401).
- ASSUMPTION (2026-10-20 14:42+0200 by dell26/agent-4): purpose (CALENDAR|MAIL|CONTACTS) does not change the scopes (always combined); it is only passed back to the SPA. The callback redirects to /settings/integrations?oauth=connected|error&provider=&purpose=&connectionId=|error= (page does not exist yet; web-cal-sources/settings tasks build it).
- ASSUMPTION (2026-10-20 14:42+0200 by dell26/agent-4): Microsoft issues tokens per resource, so OAuthTokenProvider has accessToken(id) (Graph/Google APIs) plus accessToken(id, OAuthResource.MAIL) for IMAP/SMTP XOAUTH2 (outlook.office.com scopes via the same refresh token). Access tokens are cached in memory only, refreshed 5 min (max half the lifetime) before expiry.
