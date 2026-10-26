# Code Review

Date: 2026-10-26

Task: unifiedpush-android-client - Android UnifiedPush client (round 4, final revision)

Scope: whole task, commits 4acbc4be335ca1cfe8ee476142d291f3f912aa73..0d881bb2d4a440def7dc64fcc31023fa6a7c7320 (committed changes only; working tree clean). Reviewed revision: 0d881bb2d4a440def7dc64fcc31023fa6a7c7320.

Requirements:
- tasks/unifiedpush-android-client.md (acceptance criteria 1-3 and recorded assumptions)
- AGENTS.md project notes and conventions
- docs/ARCHITECTURE.md (Android stack table)
- reviews/unifiedpush-android-client/fixes.md and the round-3 review (ead4f9994)
- Parent-supplied test run: `android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*' --tests 'ch.sonensei.cockpit.auth.AuthRepositoryLogoutHookTest'` -> BUILD SUCCESSFUL, 82 tests, 0 failures (Gradle could not be run in this review)

Git range:
- Branch: main
- HEAD: 0d881bb2d4a440def7dc64fcc31023fa6a7c7320
- Base/start: 4acbc4be335ca1cfe8ee476142d291f3f912aa73
- Commits reviewed: 25 (15 code, test and review commits plus heartbeat, claim and release board commits)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Changes since round 3

Production code is unchanged since 54d45a508, which round 3 reviewed. Two commits followed:

- 8cb57beba adds two tests to android/app/src/test/java/ch/sonensei/cockpit/push/PushInAppTest.kt.
- 0d881bb2d adds reviews/unifiedpush-android-client/tests.md. It contains no placeholders, TODO or TBD.

### New test: `a push that arrives after a user logout is ignored` (PushInAppTest.kt:265-276)

The test queues two 204 answers, one for the push DELETE and one for the device revoke. It then runs the real `AuthRepository.logout()` and sends a decrypted mail push and a decrypted reminder push through the real `CockpitPushService` for the old instance. The assertions are correct. `logout()` clears the caches before it returns (`clearCaches()` in the `finally` block of AuthRepository.kt:232-239), so `PushRegistrar.clear()` has already removed the instance and the session is logged out. `PushMessageHandler.handle` (PushMessageHandler.kt:79) therefore returns early for both pushes.

Both guards (logged out, and the instance no longer matching) are true together here, so the test does not show which one does the work. That is fine for an integration test, because each guard is also tested on its own in PushMessageHandlerTest.

### New test: `a user logout without a push registration only revokes the device` (PushInAppTest.kt:278-290)

With `fakeStore.instance = null`, `deleteOnServer()` returns at PushRegistrar.kt:112 before it sets `deleted` or takes the lock, and `clear()` returns at line 124. The only server request is therefore the revoke, and `requestCount == 1` with an empty `unregistered` list is the right expectation.

The test runs under `HiltTestApplication`, so `CockpitApp.onCreate` does not call `PushRegistrar.start()`. No background `register()` can create an instance and make the test racy.

## Acceptance criteria check (code inspection)

- **AC1:**
    - Connector 3.3.5 is in android/gradle/libs.versions.toml and in the ARCHITECTURE.md stack table, along with the Tink bump from 1.18.0 to 1.23.0. The plain tink exclusion is in app/build.gradle.kts, as the assumption says.
    - Registration runs at app start and after each login (`PushRegistrar.start`, called from `CockpitApp.onCreate`).
    - The endpoint goes to `PUT /api/v1/push/unifiedpush/{instanceId}` through the generated PushApi, with the keys sent both or neither.
    - A user logout runs the authenticated DELETE through the new `LogoutHook`, which is bounded to 5 s and ordered against PUTs by a Mutex and the `deleted` marker. Every logout unregisters at the distributor through a `SessionCache`.
    - With no distributor, `selectDistributor()` returns false, nothing registers, and the periodic syncs stay.
- **AC2:**
    - The handler acts only on decrypted pushes for the current instance while logged in.
    - A reminder push enqueues a unique one-time ReminderSyncWorker (REPLACE).
    - A mail push shows one content-free notification on channel `mail`.
    - Unknown kinds, invalid JSON, non-UUID ids, non-UTF-8 bytes, empty input and oversized input all return null.
- **AC3:** Tests under `ch.sonensei.cockpit.push` cover message handling, registration and ordering, the Android adapters, the connector and the in-app wiring. The parent's run of the named test filter passed (82 tests in total, including AuthRepositoryLogoutHookTest). Evidence is not yet recorded with `tasks.py verify`; the parent worker does that after this review.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
