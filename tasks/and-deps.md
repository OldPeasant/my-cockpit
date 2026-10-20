---
title: Android dependencies and test setup
status: in_progress
owner: dell26/agent-3
deps: []
touches: [android/gradle/libs.versions.toml,android/build.gradle.kts,android/app/build.gradle.kts,android/gradle.properties]
prio: 1
lease_until: 2026-10-20T09:18:04Z
---
## Goal
Add the Android libraries the spec needs up front (SPEC.md §2.1, §9.2).

## Acceptance criteria
- [x] Version catalog + app build add: Hilt (KSP), Navigation Compose, Retrofit + OkHttp + kotlinx.serialization converter, Room (KSP), WorkManager, DataStore, androidx.security-crypto (or Tink), Credential Manager, Glance (widget), Coil, Robolectric + Compose UI test (unit tests), MockK, kotlinx-coroutines-test, Turbine
  - verified (2026-10-20 11:00+0200 by dell26/agent-3): Inspected android/gradle/libs.versions.toml + app/build.gradle.kts: Hilt 2.59 (KSP 2.3.4), Navigation Compose, Retrofit 3 + OkHttp 5 + kotlinx.serialization converter, Room 2.8.4 (KSP), WorkManager, DataStore, Tink, Credential Manager, Glance, Coil 3, Robolectric 4.16 + Compose ui-test-junit4, MockK, coroutines-test, Turbine; ./gradlew testDebugUnitTest resolves and compiles all of them -> BUILD SUCCESSFUL; TestLibrariesSmokeTest exercises MockK/coroutines-test/Turbine -> 3 passed
- [x] A Robolectric Compose UI test of the placeholder screen runs in `testDebugUnitTest`
  - verified (2026-10-20 11:00+0200 by dell26/agent-3): cd android && ./gradlew testDebugUnitTest --rerun-tasks -> PlaceholderScreenTest (Robolectric, v2 createComposeRule, setContent { PlaceholderScreen() } + assertIsDisplayed) 3/3 passed; JaCoCo report shows PlaceholderScreen.kt 10/11 lines covered
- [ ] `./gradlew ktlintCheck testDebugUnitTest lintDebug` passes; ARCHITECTURE.md stack table updated

## Notes
- ASSUMPTION (2026-10-20 10:47+0200 by dell26/agent-3): Chose Tink (tink-android) over androidx.security-crypto: security-crypto 1.1 deprecated its APIs, which would trip warningsAsErrors lint once used
- ASSUMPTION (2026-10-20 10:47+0200 by dell26/agent-3): Library versions picked from known stable releases and confirmed by Gradle resolution; Maven metadata endpoints were not reachable from this sandbox, so they may not be the very latest
- ASSUMPTION (2026-10-20 10:47+0200 by dell26/agent-3): Hilt/Room plugins are applied but no @HiltAndroidApp Application or Room database is added; wiring them is left to and-shell / and-capture-queue
