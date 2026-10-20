# Code Review

Date: 2026-10-20

Task: oauth-connections - OAuth connections for Google and Microsoft

Scope: `git diff f04232b..dd2a7a6` (commits ee544db, 3000ab3, bf52f98, c53d042, dd2a7a6): server `core/oauth` package (api, domain, infra), migration `db/migration/core/V202610201300__core_oauth.sql`, `application*.properties`, OAuth tests, `docs/oauth-setup.md`, `docs/ARCHITECTURE.md` env table, `api/openapi.yaml`, generated web client (skimmed only, because it is generated). This is the second review. It focuses on the changes since the first review (`git diff bf52f98..dd2a7a6`): the CR-1 fix in `OAuthTokenService` and `OAuthConnectionService`, the two new concurrency tests in `OAuthEdgeCaseWebTests`, the new `OAuthBoundaryWebTests`, and `@DirtiesContext` on `OAuthWebTestBase`. I re-checked the rest of the scope briefly.

Requirements:
- `tasks/oauth-connections.md`: acceptance criteria and the recorded ASSUMPTION notes
- SPEC.md §4.1, §4.2, §7.1, §10, §14
- docs/ARCHITECTURE.md (package layout, env vars), AGENTS.md project notes
- `reviews/oauth-connections/fixes.md` (what the CR-1 fix claims to do) and the parent's instructions: CR-2 and CR-3 were accepted as non-blocking

Git range:
- Branch: main
- HEAD: dd2a7a6274e77a6fcc51dffece486a8a6ab97aa6
- Base/start: f04232ba2f16cc2ebe3a23b7314ee3594d289736
- Commits reviewed: 5 (3 in the first review, plus c53d042 and dd2a7a6)
- Uncommitted changes when the review started: none

Commands run:
- `git -C <repo> diff --stat bf52f98..dd2a7a6` -> 8 files: 3 review reports, `OAuthConnectionService.kt`, `OAuthTokenService.kt`, `OAuthBoundaryWebTests.kt`, `OAuthEdgeCaseWebTests.kt`, `OAuthWebTestBase.kt`
- `git -C <repo> diff bf52f98..dd2a7a6 -- server/src` -> read in full. I also read the current `OAuthTokenService.kt`, `OAuthConnectionService.kt` and `OAuthTokenProvider.kt` in full.
- `grep` over `server/src/main` for `OAuthConnectionDisconnected`, `exclusive(`, `open-in-view` and timeouts -> no listener for the event exists yet; `exclusive` is used only by `accessToken`, `disconnect` and the reconnect branch of `complete`; `spring.jpa.open-in-view=false`; the OAuth HTTP client has a 10 s connect timeout and a 20 s read timeout
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.oauth.*' --tests '*OpenApiContractTest'` -> BUILD SUCCESSFUL. The test task really ran (not up to date). JUnit XML results: OAuthHttpClientTests 11, OAuthEdgeCaseWebTests 19, OAuthFlowWebTests 10, OAuthConnectionsWebTests 5, OAuthBoundaryWebTests 5, OAuthTokenProviderTests 5, OAuthConfigurationTests 5, OpenApiContractTest 1. That is 61 tests, 0 failures, 0 errors, 0 skipped.
- `python tasks.py verify oauth-connections`: not run, because the parent agent told me not to run tasks.py. I did not check the recorded evidence per criterion.

Summary: 0 P0, 0 P1, 0 P2, 3 P3 (CR-1 resolved; CR-2 and CR-3 accepted by the parent, still open)

Acceptance criteria (rechecked against dd2a7a6):
1. Start/callback with PKCE S256, a single-use state bound to the session and checked for expiry and provider, combined scopes, refresh tokens stored encrypted, access tokens cached in memory and refreshed early. Met. The new boundary test covers the 10-minute lifetime boundary and Google's offline/consent parameters.
2. `OAuthTokenProvider.accessToken`, DELETE with If-Match (428/412) plus revocation, list/get with status and ETag. Met. The new boundary test checks that a status change bumps the ETag and that the old ETag then gets 412.
3. WireMock tests (state mismatch, refresh flow) and the setup guide. Met, and I ran the tests (above).
4. and 5. The focused Gradle test run and `OpenApiContractTest` pass in my run (above).

Verification of the CR-1 fix:
- Correctness: `accessToken`, `disconnect` and the reconnect branch of `complete` now share one `ReentrantLock` per connection (`OAuthTokenService.exclusive`, line 50). `disconnect` (`OAuthConnectionService.kt:189-199`) deletes and commits, then calls `forget` while it still holds the lock. A refresh that was in flight finishes first. A refresh that waited re-reads the row and gets `OAuthConnectionNotFoundException`, so no token can be cached again for a deleted connection. A reconnect (`complete`, lines 126-132) stores the new refresh token, then calls `forget` and `remember` under the lock, so `recordRefresh` from the old chain can no longer overwrite it. If the If-Match check or the not-found check fails, `withLock` releases the lock in its `finally`.
- New connections: when no row exists for the provider account, `store` runs without the lock. That is safe: no caller can know the id yet, so no refresh can be in flight. The lookup and `store` run in separate transactions. If a disconnect or create slips in between, the result is still consistent (a new row with a new id, or an update of a row whose id nobody has fetched yet).
- Deadlock: the lock order is always lock first, then DB connection (`refresh`, `disconnect`, `store`). No current code path takes the lock while it holds a transaction. Open-in-view is off, so a web request does not hold a connection before it enters the service. The event is published under the lock, but no listener exists yet. A future listener on the same thread can call `accessToken` for the same id without deadlocking, because the lock is reentrant. The longest wait for the lock is one refresh, bounded by the HTTP timeouts (about 30 s).
- Tests: the two new tests wait until WireMock has recorded the refresh request (it is recorded before the 1 s fixed delay), then disconnect or reconnect. `fixes.md` reports that they fail when the lock is removed, and they pass in my run. `@DirtiesContext` on `OAuthWebTestBase` only affects test lifecycle (it closes the WireMock-specific contexts and their Hikari pools). It is a reasonable fix for the `too many clients` failures it describes.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [x] **CR-1 | Token refresh is not coordinated with disconnect and reconnect** (resolved in dd2a7a6)
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/oauth/domain/OAuthTokenService.kt:50-53`, `server/src/main/kotlin/ch/sonensei/cockpit/core/oauth/domain/OAuthConnectionService.kt:126-132,189-199`
    - Category: Correctness
    - Problem: A refresh in flight could cache a token again after a disconnect. It could also overwrite the refresh token a reconnect had just stored.
    - Why it matters: A module could keep using an access token for a disconnected connection, or a reconnect could lose its new grant.
    - Suggested fix: Done. Disconnect and reconnect now run under the per-connection refresh lock (see "Verification of the CR-1 fix"). Regression tests: `OAuthEdgeCaseWebTests` "a disconnect during a refresh ..." and "a reconnect during a refresh ...".

- [ ] **CR-2 | ID token claims are used without checking `iss` and `aud`** (accepted as non-blocking by the parent; unchanged)
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/oauth/infra/OAuthHttpClient.kt:90-105`
    - Category: Security
    - Problem: `identity` takes `sub`, `email` and `name` from the ID token payload without checking that `aud` contains the client id or that `iss` matches the provider. OIDC Core §3.1.3.7 steps 2-3 require both checks, even when the signature check is skipped for a token received directly over TLS.
    - Why it matters: The risk is low, because the token comes straight over TLS from the configured token endpoint. The checks would catch a misconfigured endpoint or tenant.
    - Suggested fix: Check `aud` against `registration.clientId` and `iss` against the provider's issuer (for Microsoft, a tenant pattern). Add a test where a wrong `aud` gives `token_exchange_failed`.

- [ ] **CR-3 | Microsoft connections report only the Graph scopes as granted** (accepted as non-blocking by the parent; unchanged)
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/oauth/domain/OAuthConnectionService.kt:119,160`
    - Category: Correctness
    - Problem: The code is redeemed with the Graph-only scope, so `connection.scopes = grant.scope` stores Microsoft's Graph and OIDC scopes and leaves out the IMAP and SMTP scopes the owner consented to.
    - Why it matters: A future mail module that reads `scopes` could decide mail access is missing.
    - Suggested fix: For Microsoft, store the requested authorization scopes, or document that `scopes` lists only the API token's scopes.

## Review conclusion

No material issues found. The CR-1 fix is correct: there is no deadlock path in the current code, the lock is held across the DB transaction and event publication on purpose and with bounded waits, and new connections are handled safely without the lock. The regression tests pass. The new boundary tests and `@DirtiesContext` add no problems. CR-2 and CR-3 stay open as P3 items the parent accepted. The focused OAuth tests and `OpenApiContractTest` pass on dd2a7a6 (61 tests, 0 failures).
