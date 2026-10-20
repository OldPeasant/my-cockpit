---
title: Android dependencies and test setup
status: in_progress
owner: dell26/agent-3
deps: []
touches: [android/gradle/libs.versions.toml,android/build.gradle.kts,android/app/build.gradle.kts,android/gradle.properties]
prio: 1
lease_until: 2026-10-20T08:50:08Z
---
## Goal
Add the Android libraries the spec needs up front (SPEC.md §2.1, §9.2).

## Acceptance criteria
- [ ] Version catalog + app build add: Hilt (KSP), Navigation Compose, Retrofit + OkHttp + kotlinx.serialization converter, Room (KSP), WorkManager, DataStore, androidx.security-crypto (or Tink), Credential Manager, Glance (widget), Coil, Robolectric + Compose UI test (unit tests), MockK, kotlinx-coroutines-test, Turbine
- [ ] A Robolectric Compose UI test of the placeholder screen runs in `testDebugUnitTest`
- [ ] `./gradlew ktlintCheck testDebugUnitTest lintDebug` passes; ARCHITECTURE.md stack table updated

## Notes
