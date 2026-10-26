# Code Review

Date: 2026-10-26

Task: unifiedpush-android-client - Android UnifiedPush client (round 3)

Scope: whole task, commits 4acbc4be335ca1cfe8ee476142d291f3f912aa73..10eadb04c52c1421a76ddd70da180e00e03bbd1e (committed changes only; uncommitted test-writer files ignored). Reviewed revision: 10eadb04c52c1421a76ddd70da180e00e03bbd1e.

Requirements:
- tasks/unifiedpush-android-client.md (acceptance criteria 1-3 and recorded assumptions)
- AGENTS.md project notes and conventions
- docs/ARCHITECTURE.md (Android stack table)
- api/openapi.yaml (UnifiedPushRegistrationRequest) and the server push module (payload format, registration table)
- reviews/unifiedpush-android-client/fixes.md (round-2 CR-1 fix)

Git range:
- Branch: main
- HEAD: 10eadb04c52c1421a76ddd70da180e00e03bbd1e
- Base/start: 4acbc4be335ca1cfe8ee476142d291f3f912aa73
- Commits reviewed: 20 (10 code/test/review commits plus heartbeat, claim and release board commits)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Round-2 finding confirmed fixed

Round-2 P3 CR-1 (deleteOnServer set `deleted` only inside the lock, so a logout hook timeout while waiting for a slow PUT let the queued PUTs through): fixed in 10eadb04c. `PushRegistrar.deleteOnServer` (android/app/src/main/java/ch/sonensei/cockpit/push/PushRegistrar.kt:111-120) now sets `deleted = instance` before `requests.withLock`, and `onNewEndpoint` checks `instance == deleted` inside the lock (line 91), so PUTs waiting on the lock and later ones are dropped even when the wait is cancelled. The stale value cannot block a later login, because `clear()` forgets the instance and the next login creates a new random id. The new test in PushRegistrarTest.kt (`endpoints waiting or arriving after the logout's delete timed out are dropped`) holds the lock with a slow PUT, times the delete out after 100 ms, then checks that exactly one PUT and no DELETE were sent. That matches the scenario in the finding.

## Acceptance criteria check (code inspection)

- AC1: connector 3.3.5 is in android/gradle/libs.versions.toml and the ARCHITECTURE.md stack table (Tink bump 1.18.0 to 1.23.0 is recorded there too). The plain tink exclusion is in app/build.gradle.kts, as the task assumption says. Registration runs at app start and after each login (`PushRegistrar.start` collects the logged-in flow). The endpoint goes to `PUT /push/unifiedpush/{instanceId}` through the generated PushApi. Keys are sent both or neither, which matches the server check constraint. A user logout runs the DELETE through the new LogoutHook (still authenticated, 5 s bound), and every logout unregisters at the distributor through a SessionCache. With no distributor, `selectDistributor()` returns false and nothing registers, so the existing periodic syncs stay.
- AC2: `PushMessageHandler` acts only on decrypted pushes for the current instance while logged in. A reminder push enqueues a unique one-time ReminderSyncWorker (REPLACE). A mail push shows one notification on channel `mail` with no contents. Unknown kinds, invalid JSON, non-UUID ids, non-UTF-8 bytes, empty input and oversized input all return null. The wire format `{"kind","id"}` matches server PushMessage.toJson.
- AC3: tests exist under ch.sonensei.cockpit.push for message handling, registration, ordering, the adapters and the connector. I could not run Gradle in this review. `tasks.py verify unifiedpush-android-client` currently shows no evidence for criteria 1-3, so the parent worker still has to run the named command and record the results.

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
