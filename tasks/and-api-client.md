---
title: Android API client and auth plumbing
status: in_progress
owner: dell26/agent-5
deps: [and-shell,p0-auth-tokens]
touches: [android/app/build.gradle.kts,android/app/src/main/java/ch/sonensei/cockpit/core/network,android/app/src/test/java/ch/sonensei/cockpit/core/network]
prio: 1
lease_until: 2026-10-20T15:51:22Z
releases: 2
extended: 
---
## Goal
Retrofit client generated from the OpenAPI contract, with token handling.

## Acceptance criteria
- [x] openapi-generator Gradle plugin generates Kotlin (Retrofit2 + kotlinx.serialization) models/APIs from `../api/openapi.yaml` at build time (not committed)
  - verified (2026-10-20 17:05+0200 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.*' --rerun-tasks -> openApiGenerate ran from ../api/openapi.yaml, 48 generated .kt files under android/app/build (git-ignored), git ls-files tracks 0 generated sources; generated AuthApi used by AuthApiTokenRefresher, tests pass
- [x] OkHttp interceptor adds the access token; an `Authenticator` refreshes on 401 once (concurrent requests share one refresh, test with MockWebServer); a refresh failure logs out
  - verified (2026-10-20 17:05+0200 by dell26/agent-1): ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.*' -> 69 passed, 0 failed; TokenAuthenticatorTest (MockWebServer): token injection, 8 concurrent 401s -> exactly 1 refresh (success, rejected, failing 503), rejected refresh logs out; TokenAuthenticatorUnitTest covers waiters and CAS interleavings
- [x] Server base URL configurable (DataStore), validated as https (http allowed for localhost/emulator)
  - verified (2026-10-20 17:05+0200 by dell26/agent-1): ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.*' -> ServerUrlTest (19) + NetworkSecurityConfigTest (2) pass: URL stored in DataStore, https required, http only for localhost/127.0.0.1/::1/10.0.2.2 matching network_security_config.xml, invalid URLs rejected; device enforcement of network_security_config not run (no emulator)
- [x] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.*'` passes, covering token injection, single refresh for concurrent 401s, logout on refresh failure (Robolectric/Compose UI tests for screens)
  - verified (2026-10-20 17:05+0200 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.*' ktlintCheck --rerun-tasks -> BUILD SUCCESSFUL, 69 tests, 0 failures/skips (token injection, single refresh for concurrent 401s, logout on refresh failure); JaCoCo core.network 99.4% lines, 99.0% branches

## Notes
- ASSUMPTION (2026-10-20 15:06+0200 by dell26/agent-2): Tokens are kept by an InMemoryTokenStore behind the TokenStore interface; Keystore/encrypted persistence is and-login's job (its title: secure token storage), which rebinds TokenStore.
- ASSUMPTION (2026-10-20 15:06+0200 by dell26/agent-2): Settings/SettingsPatch (additionalProperties: true) are generated as kotlinx JsonObject via schemaMappings: openapi-generator 7.26.0 kotlin emits uncompilable code for open objects, and other modules add their own settings keys.
- ASSUMPTION (2026-10-20 15:06+0200 by dell26/agent-2): Cleartext http allowed for localhost, 127.0.0.1, ::1 and 10.0.2.2 (emulator host) only, in ServerUrlValidator and a network_security_config.xml; added INTERNET permission to AndroidManifest.xml (outside touches, needed for any API call).
- ASSUMPTION (2026-10-20 15:06+0200 by dell26/agent-2): Refresh 400/401 = rejected -> logout (tokens cleared, AuthSession.loggedOut emits); network errors and 5xx keep the tokens and just fail the request. A 401 on an already-retried request is final (no second refresh).
- ASSUMPTION (2026-10-20 15:06+0200 by dell26/agent-2): Retrofit uses a placeholder base URL; ServerUrlInterceptor rewrites each request to the DataStore-configured server (keeping a path prefix), so changing the server URL needs no rebuild of the client.
- ASSUMPTION (2026-10-20 15:37+0200 by dell26/agent-2): A request already in flight to the previous server that returns 401 after a server switch + new login may be retried there with the new short-lived access token (refresh token never exposed); accepted as low risk since the old server was user-configured and trusted, not checked per-request host
- release by dell26/agent-2: Implementation complete (generated Retrofit client, token interceptor + single-flight Authenticator, logout on refresh failure, DataStore server URL w/ https validation, MockWebServer tests). First test-writer/code-reviewer round done; findings TW-1, TW-2, CR-1..CR-4 fixed in a70b9ac/a2c399e (fixes.md). scripts/check.sh passes at a2c399e. LEFT: re-run test-writer and code-reviewer on final code (existing tests.md/review.md are stale), tasks.py verify for criteria 1-4, write+commit reviews/and-api-client/quality.json, then tasks.py done.
- release by dell26/agent-1: Implementation complete, all 4 criteria verified, 3 review rounds done; quality evidence committed (reviews/and-api-client/quality.json, reviewed_revision 8515b269; later commits are only main merges + evidence, no android changes). sh scripts/check.sh passes at 75c48f20. Only left: run 'python tasks.py done and-api-client'.
- ASSUMPTION (2026-10-20 17:30+0200 by dell26/agent-5): wip history was rebased, so reviewed commit 8515b269 no longer exists; quality.json reviewed_revision now names its rebased counterpart 3327a54, which has identical task code (git diff 3327a54 HEAD outside tasks/reviews is empty); helper reports not re-run
