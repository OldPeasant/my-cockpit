# Code Review

Date: 2026-10-26

Task: unifiedpush-android-client - Android UnifiedPush client

Scope: `git diff bca3d1ddae4000140494d57a630ee4c0fa8b90e9..ceccb065280d050edadd02d88b8d261704b7c3d7` (committed changes only; mostly production code under `android/app/src/main/java/ch/sonensei/cockpit/push/`, `auth/AuthRepository.kt`, `auth/AuthModule.kt`, `CockpitApp.kt`, `AndroidManifest.xml`, resources, `libs.versions.toml`, `app/build.gradle.kts`, `docs/ARCHITECTURE.md`; tests looked at for coverage of the criteria)

Requirements:
- `tasks/unifiedpush-android-client.md` acceptance criteria and ASSUMPTION notes
- `SPEC.md` (Android push, phase 7 "UnifiedPush for Android")
- `docs/ARCHITECTURE.md` (Android stack table)
- `AGENTS.md` project conventions (lint, no secrets/contents logged, generated API client, stack table updates)
- Server contract: `server/src/main/kotlin/ch/sonensei/cockpit/core/push/` (`UnifiedPushController`, `UnifiedPushService`, `PushDispatcher`, `PushMessage`, `WebPushKeys`) and `api/openapi.yaml` (`UnifiedPushRegistrationRequest`)

Git range:
- Branch: main
- HEAD: ceccb065280d050edadd02d88b8d261704b7c3d7
- Base/start: bca3d1ddae4000140494d57a630ee4c0fa8b90e9
- Commits reviewed: 5 (25d892d6a, d4916de95, 0d815b1da, 28b02ae76, ceccb0652)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Verification notes

- AC1: `unifiedpush-connector` 3.3.5 is in `libs.versions.toml` and `app/build.gradle.kts`, and the ARCHITECTURE.md stack table lists it. The tink bump to 1.23.0 is in the table too. `PushRegistrar.start()` (called from `CockpitApp.onCreate`) registers at app start and after each login, using the logged-in flow with `distinctUntilChanged`. `onNewEndpoint` sends `PUT /api/v1/push/unifiedpush/{instanceId}`. The generated `PushApi` path and verb match the controller, and the body fields `endpoint`/`auth`/`p256dh` match the generated model's constructor order. `PushRegistrarTest` checks this against the serialized JSON. The instance id (a UUID string) matches the server pattern `^[A-Za-z0-9._-]{1,128}$`. The keys from the connector are base64url, which `WebPushKeys` accepts with or without padding. On a user logout, `LogoutHook` sends DELETE before the device is revoked, and `SessionCache` then unregisters at the distributor and forgets the instance. When no distributor is installed, `selectDistributor()` returns false and nothing is registered, so the existing WorkManager periodic syncs are left as they are.
- AC2: The client parses the payload as `{"kind":"reminder"|"mail","id":"<uuid>"}`, which matches `PushMessage.toJson()` and the `PushKind.wire` values. A reminder push enqueues a one-time `ReminderSyncWorker`, and a mail push shows a notification with no message content. These messages are ignored and covered by tests: unknown kinds, non-JSON input, wrong types, non-UUID ids, invalid UTF-8, empty or oversized payloads, undecrypted pushes, pushes for another instance and pushes while logged out.
- AC3: In the latest local test results (`android/app/build/test-results/testDebugUnitTest`), all `ch.sonensei.cockpit.push.*` suites pass (59 tests, 0 failures), as does `AuthRepositoryLogoutHookTest` (9 tests). I did not re-run them. The parent must record its own run as evidence.
- Security and logging: none of the new code logs anything. Exceptions are swallowed without logging, so the endpoint and user data stay out of logs. The service is `exported="false"` with the `PUSH_EVENT` filter, as the connector requires. The `PendingIntent` is `FLAG_IMMUTABLE`.
- Lint: the latest `lint-results-debug.txt` (2026-10-26 18:04) reports 0 errors and 0 warnings. The only 2 hints are in unrelated files.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A late endpoint PUT can recreate the server registration after the logout's DELETE**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/push/PushRegistrar.kt:77`
    - Category: Correctness
    - Problem: `onNewEndpoint` checks `session.isLoggedIn && instance == store.current()` when its coroutine starts and then sends the PUT. `deleteOnServer()` (the logout hook, line 98) sends DELETE on another coroutine, and nothing orders the two requests. Example: the app starts, `register()` makes the distributor send the endpoint, and the user logs out a moment later. If the PUT is still in flight when the DELETE is sent, the server can process the DELETE first and then the PUT, which recreates the registration. `clear()` afterwards only unregisters locally, so the server keeps pushing to an endpoint whose keys the device has forgotten. Unless the distributor answers 404 or 410, `PushDispatcher` never removes it.
    - Why it matters: Logout is meant to remove this device's endpoint on the server (AC1). The window is small and the payloads are ids only, encrypted with keys the device no longer has, so the impact is a stale registration and wasted pushes, not a data leak.
    - Suggested fix: Order the two requests. For example, run the endpoint PUT and `deleteOnServer` under one `Mutex` in `PushRegistrar`, and in `deleteOnServer` mark the instance as being logged out (or clear it) before sending DELETE, so a PUT that has not been sent yet is dropped.

- [ ] **CR-2 | Every process start re-registers and may PUT the same endpoint again**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/push/PushRegistrar.kt:56`
    - Category: Quality
    - Problem: `start()` runs in `CockpitApp.onCreate`, so it runs on every process start, including background starts by WorkManager periodic syncs, alarms and push deliveries. Each time it calls `connector.register(...)`. By design (see the task ASSUMPTION) the distributor answers with the endpoint again, and `onNewEndpoint` sends another PUT. The client does not remember which endpoint it last sent, so an unchanged endpoint is sent again, and the server logs `registered` at INFO each time.
    - Why it matters: When a process is cold-started for a push, a second round trip follows right after it, and the periodic workers can cause more. That costs battery and network, and adds noise to the server log. Because the behavior is intentional (it is the only retry path), this is a low-priority efficiency issue.
    - Suggested fix: Store the last endpoint and keys the server accepted (a 2xx response) next to the instance in `PreferencesPushInstanceStore`, and skip the PUT when they have not changed. Clear them in `store.clear()`. Re-registering at start is fine as long as an unchanged endpoint is not sent again.

## Review conclusion

No material issues found.
