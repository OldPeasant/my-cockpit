# Code Review

Date: 2026-10-20

Task: oauth-connections - OAuth connections for Google and Microsoft

Scope: `git diff 45613885..809a5538` (code commits a6a5850b and 809a5538). HEAD 5cece2cd only adds the test-writer report and is not code. The scope covers the server `core/oauth` package (api, domain, infra), the migration `db/migration/core/V202610201300__core_oauth.sql`, `application.properties` and `application-dev.properties`, the OAuth tests, `docs/oauth-setup.md`, the `docs/ARCHITECTURE.md` env table, `api/openapi.yaml` and the generated web client. This is a fresh review of the revision that was re-applied onto current main. The earlier review covered the pre-squash revision dd2a7a6.

Requirements:
- `tasks/oauth-connections.md`: acceptance criteria 1-5 and the recorded ASSUMPTION notes
- SPEC.md §4.1 (secrets at rest, session cookie), §4.2 (UUIDv7, ETag/If-Match, Problem Details), §7.1 (OAuth apps, one `oauth_connection` per provider account, combined scopes)
- docs/ARCHITECTURE.md (env table, conventions), AGENTS.md project notes (module boundaries, Flyway naming, generated files, logging rules), `api/README.md`, docs/QUALITY.md
- Parent instructions: check PKCE, the state's binding to the session, encrypted refresh tokens, no secrets in logs, module boundaries, the Flyway migration and that the generated API files are consistent

Git range:
- Branch: main
- HEAD: 5cece2cdc0f2bb2206a4b636a72108f07e3f23e3 (code under review: 809a5538e17c27a8b3650ab2f037ee50ee50a4bb)
- Base/start: 45613885d7ee8e7f0f3d7c0ac9b3470170a6b73b
- Commits reviewed: 2 (a6a5850b, 809a5538)
- Uncommitted changes when the review started: none

Commands run:
- `git log --oneline 45613885..HEAD` -> 5cece2cd (test report), 809a5538 (regenerated openapi.yaml and web client), a6a5850b (implementation and tests)
- `git diff --stat 45613885..809a5538` -> 39 files. I read all 11 production Kotlin files, the migration, both properties diffs, the ARCHITECTURE.md diff and `docs/oauth-setup.md` in full. I read the generated web client diff (index, functions, models, two of the six `fn/o-auth` files) and the list of test methods.
- `git diff --stat dd2a7a6 809a5538 -- server/src/main/kotlin/.../core/oauth server/src/test/kotlin/.../core/oauth server/src/main/resources docs/oauth-setup.md` -> only three migrations from other tasks differ (`core_items_link`, `core_search_document`, `gtd_model`). The OAuth code, tests, migration and setup guide are byte-identical to the revision reviewed earlier.
- `find server/src/main/resources/db/migration -name 'V*.sql'` -> all versions are unique across modules. `V202610201300__core_oauth` sorts before main's later core migrations, and `spring.flyway.out-of-order=true` is set.
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.oauth.*' --tests '*OpenApiContractTest'` -> BUILD SUCCESSFUL. The `test` task really ran. JUnit XML results from this run: OAuthBoundaryWebTests 5, OAuthConfigurationTests 5, OAuthConnectionsWebTests 5, OAuthEdgeCaseWebTests 19, OAuthFlowWebTests 10, OAuthHttpClientTests 11, OAuthTokenProviderTests 5, OpenApiContractTest 1. That is 61 tests, 0 failures, 0 errors, 0 skipped. OpenApiContractTest passing without `UPDATE_OPENAPI` shows that `api/openapi.yaml` matches the server.
- I read `SessionCookies`, `SessionAuthenticationFilter` and `SecurityConfiguration`. The session cookie is `SameSite=Lax`, so the provider's top-level GET redirect to `/callback` carries it. The session token is not rotated on renewal, so the binding stays valid during consent. `/api/**` requires authentication.
- I read `deploy/docker-compose.yml` and `deploy/.env.example` (for CR-3).
- I did not regenerate the web client: that would write into the tree, and I may only write this report. I checked it by inspection: the six operations and two models match the controller's operationIds, enums and required fields.
- `python tasks.py verify oauth-connections`: not run, because the parent told me not to run tasks.py. I did not check the evidence recorded per criterion.

Summary: 0 P0, 0 P1, 0 P2, 3 P3

Acceptance criteria (checked against 809a5538):
1. `/start` and `/callback` are met. They use the authorization code flow with PKCE S256: a 48-byte verifier, stored encrypted, sent at the code exchange. The state is 32 random bytes, stored only as SHA-256. It is single-use, checked for provider and expiry (10 minutes), and compared in constant time with a hash of the `COCKPIT_SESSION` token. `/start` without a session cookie gets 401. Combined scopes come from `OAuthScopes.authorization`. Refresh tokens are `EncryptedSecret`, persisted through `EncryptedSecretConverter`, and rotated tokens go through `cipher.encrypt` in the native `recordRefresh`. Access tokens are cached in memory only and refreshed `min(5 min, lifetime/2)` before they expire.
2. Met. `OAuthTokenProvider.accessToken(id)` and `accessToken(id, resource)` exist. `DELETE /connections/{id}` requires If-Match (428/412), publishes `OAuthConnectionDisconnected` and revokes at Google on a best-effort basis. `GET /connections` and `GET /connections/{id}` (with ETag) return status, never tokens.
3. Met. WireMock acts as the authorization server, with tests for state mismatch (unknown, foreign session, wrong provider, expired, replay) and for the refresh flow (early refresh, rotation, `invalid_grant`, concurrency). `docs/oauth-setup.md` explains how to register the Google and Azure apps and lists the env vars.
4. and 5. Met: they pass in my run (above).

Security: the callback only redirects to the fixed path `/settings/integrations`, with enum, UUID and slug values only. The provider's `error` value is not reflected (it is checked by regex and only logged at INFO). Log messages and exception messages hold only provider names, connection ids and RFC 6749 error codes. `TokenGrant`, `OAuthAccessToken`, `EncryptedSecret` and `Registration` redact their secrets in `toString`. A test captures the log output and asserts that no token, code, verifier, client secret or session token appears in it. The architecture fits the project: domain uses infra (repositories, HTTP client) the same way the existing `auth` module does, and the reviewed code adds no dependencies on other modules. The CR-1 lock fix from the earlier review is present unchanged (`OAuthTokenService.exclusive`, used by `accessToken`, `disconnect` and the reconnect branch of `complete`).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | ID token claims are used without checking `iss` and `aud`** (carried over as CR-2 from the review of dd2a7a6, where the parent accepted it as non-blocking; still present)
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/oauth/infra/OAuthHttpClient.kt:90-105`
    - Category: Security
    - Problem: `identity` takes `sub`, `email` and `name` from the ID token payload. It does not check that `aud` contains the client id or that `iss` matches the provider. OIDC Core §3.1.3.7 steps 2-3 still require both checks when the signature check is skipped for a token received directly over TLS.
    - Why it matters: The risk is low, because the token comes straight over TLS from the configured token endpoint. The checks would catch a misconfigured token endpoint or tenant before a connection is keyed on a foreign `sub`.
    - Suggested fix: Check `aud` against `registration.clientId` and `iss` against the provider's issuer (for Microsoft, a tenant pattern). Add a test where a wrong `aud` gives `token_exchange_failed`.

- [ ] **CR-2 | Microsoft connections report only the Graph scopes as granted** (carried over as CR-3 from the review of dd2a7a6, where the parent accepted it as non-blocking; still present)
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/oauth/domain/OAuthConnectionService.kt:119,160`
    - Category: Correctness
    - Problem: The code is redeemed with the Graph-only `tokenRequest(provider, API)` scope, so `connection.scopes = grant.scope` stores the scopes of Microsoft's Graph token. It leaves out `IMAP.AccessAsUser.All` and `SMTP.Send`, which the owner consented to.
    - Why it matters: `scopes` is exposed in the API as "Granted scopes". A future mail module or UI that reads it could conclude that mail access is missing.
    - Suggested fix: For Microsoft, store the requested authorization scopes (or merge in the mail scopes), or document that `scopes` lists only the API token's scopes.

- [ ] **CR-3 | The documented `public-url` override does not reach the server container in the Compose deployment**
    - File: `docs/oauth-setup.md:28-30` (and `deploy/docker-compose.yml:33-44`, `deploy/.env.example:9-11`)
    - Category: Integration
    - Problem: The setup guide tells owners on a non-standard port to set `COCKPIT_OAUTH_PUBLICURL=https://cockpit.example.org:8443`. `docker-compose.yml` gives the server an explicit `environment:` list without `env_file`, and that list does not include this variable, so putting it in `deploy/.env` has no effect. `.env.example` itself suggests 8080/8443 for rootless Podman. In that setup, `redirect_uri` becomes `https://<domain>/api/v1/oauth/<provider>/callback` without the port, and the provider redirects to the wrong origin.
    - Why it matters: Owners who follow the guide on a non-standard port get a broken redirect and no hint why.
    - Suggested fix: Either forward `COCKPIT_OAUTH_PUBLICURL: ${COCKPIT_OAUTH_PUBLICURL:-https://${COCKPIT_DOMAIN}}` in the server's compose environment (a small change outside this task's `touches`, which could be a follow-up task), or say in the guide that the variable has to be added to the server's `environment:` in `deploy/docker-compose.yml`.

## Review conclusion

No material issues found. The re-applied OAuth code is identical to the revision reviewed earlier, and it meets all five acceptance criteria. PKCE S256, a single-use state bound to the session, encrypted refresh tokens, early access-token refresh, redacted logging and the per-connection lock all check out in the current code. The migration version is unique, and `api/openapi.yaml` matches the server (OpenApiContractTest passes without the update flag). The 61 focused tests pass in my run. The three P3 findings are non-blocking. CR-1 and CR-2 were already accepted by the parent in the previous round. CR-3 is new and only affects the documentation and deployment of non-standard ports.
