---
title: Android dependencies and test setup
status: in_progress
owner: dell26/agent-3
deps: []
touches: [android/gradle/libs.versions.toml,android/build.gradle.kts,android/app/build.gradle.kts,android/gradle.properties]
prio: 1
lease_until: 2026-10-20T09:08:00Z
---
## Goal
Add the Android libraries the spec needs up front (SPEC.md §2.1, §9.2).

## Acceptance criteria
- [ ] Version catalog + app build add: Hilt (KSP), Navigation Compose, Retrofit + OkHttp + kotlinx.serialization converter, Room (KSP), WorkManager, DataStore, androidx.security-crypto (or Tink), Credential Manager, Glance (widget), Coil, Robolectric + Compose UI test (unit tests), MockK, kotlinx-coroutines-test, Turbine
- [ ] A Robolectric Compose UI test of the placeholder screen runs in `testDebugUnitTest`
- [ ] `./gradlew ktlintCheck testDebugUnitTest lintDebug` passes; ARCHITECTURE.md stack table updated

## Notes
- ASSUMPTION (2026-10-20 10:47+0200 by dell26/agent-3): Chose Tink (tink-android) over androidx.security-crypto: security-crypto 1.1 deprecated its APIs, which would trip warningsAsErrors lint once used
- ASSUMPTION (2026-10-20 10:47+0200 by dell26/agent-3): Library versions picked from known stable releases and confirmed by Gradle resolution; Maven metadata endpoints were not reachable from this sandbox, so they may not be the very latest
- ASSUMPTION (2026-10-20 10:47+0200 by dell26/agent-3): Hilt/Room plugins are applied but no @HiltAndroidApp Application or Room database is added; wiring them is left to and-shell / and-capture-queue
