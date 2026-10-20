---
title: Android API client and auth plumbing
status: in_progress
owner: dell26/agent-2
deps: [and-shell,p0-auth-tokens]
touches: [android/app/build.gradle.kts,android/app/src/main/java/ch/sonensei/cockpit/core/network,android/app/src/test/java/ch/sonensei/cockpit/core/network]
prio: 1
lease_until: 2026-10-20T13:19:59Z
---
## Goal
Retrofit client generated from the OpenAPI contract, with token handling.

## Acceptance criteria
- [ ] openapi-generator Gradle plugin generates Kotlin (Retrofit2 + kotlinx.serialization) models/APIs from `../api/openapi.yaml` at build time (not committed)
- [ ] OkHttp interceptor adds the access token; an `Authenticator` refreshes on 401 once (concurrent requests share one refresh, test with MockWebServer); a refresh failure logs out
- [ ] Server base URL configurable (DataStore), validated as https (http allowed for localhost/emulator)
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.*'` passes, covering token injection, single refresh for concurrent 401s, logout on refresh failure (Robolectric/Compose UI tests for screens)

## Notes
