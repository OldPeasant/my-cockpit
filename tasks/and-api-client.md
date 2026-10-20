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
