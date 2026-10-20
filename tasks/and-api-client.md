---
title: Android API client and auth plumbing
status: in_progress
owner: dell26/agent-2
deps: [and-shell,p0-auth-tokens]
touches: [android/app/build.gradle.kts,android/app/src/main/java/ch/sonensei/cockpit/core/network,android/app/src/test/java/ch/sonensei/cockpit/core/network]
prio: 1
lease_until: 2026-10-20T13:25:06Z
---
## Goal
Retrofit client generated from the OpenAPI contract, with token handling.

## Acceptance criteria
- [ ] openapi-generator Gradle plugin generates Kotlin (Retrofit2 + kotlinx.serialization) models/APIs from `../api/openapi.yaml` at build time (not committed)
- [ ] OkHttp interceptor adds the access token; an `Authenticator` refreshes on 401 once (concurrent requests share one refresh, test with MockWebServer); a refresh failure logs out
- [ ] Server base URL configurable (DataStore), validated as https (http allowed for localhost/emulator)
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.*'` passes, covering token injection, single refresh for concurrent 401s, logout on refresh failure (Robolectric/Compose UI tests for screens)

## Notes
- ASSUMPTION (2026-10-20 15:06+0200 by dell26/agent-2): Tokens are kept by an InMemoryTokenStore behind the TokenStore interface; Keystore/encrypted persistence is and-login's job (its title: secure token storage), which rebinds TokenStore.
- ASSUMPTION (2026-10-20 15:06+0200 by dell26/agent-2): Settings/SettingsPatch (additionalProperties: true) are generated as kotlinx JsonObject via schemaMappings: openapi-generator 7.26.0 kotlin emits uncompilable code for open objects, and other modules add their own settings keys.
- ASSUMPTION (2026-10-20 15:06+0200 by dell26/agent-2): Cleartext http allowed for localhost, 127.0.0.1, ::1 and 10.0.2.2 (emulator host) only, in ServerUrlValidator and a network_security_config.xml; added INTERNET permission to AndroidManifest.xml (outside touches, needed for any API call).
- ASSUMPTION (2026-10-20 15:06+0200 by dell26/agent-2): Refresh 400/401 = rejected -> logout (tokens cleared, AuthSession.loggedOut emits); network errors and 5xx keep the tokens and just fail the request. A 401 on an already-retried request is final (no second refresh).
- ASSUMPTION (2026-10-20 15:06+0200 by dell26/agent-2): Retrofit uses a placeholder base URL; ServerUrlInterceptor rewrites each request to the DataStore-configured server (keeping a path prefix), so changing the server URL needs no rebuild of the client.
