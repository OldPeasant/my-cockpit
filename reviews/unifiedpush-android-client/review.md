# Code Review

Date: 2026-10-26

Task: unifiedpush-android-client - Android UnifiedPush client

Scope: committed changes 4acbc4be335ca1cfe8ee476142d291f3f912aa73..f4865e8644fc60afebadd3b7a064fa5705a06596 (push package, AuthRepository/AuthModule logout hooks, CockpitApp, AndroidManifest, libs.versions.toml, app/build.gradle.kts, docs/ARCHITECTURE.md, tests). This is a second review round on the final code. It re-checks the CR-1 fix (a Mutex that orders the PUT and the DELETE) for correctness.

Requirements:
- tasks/unifiedpush-android-client.md (acceptance criteria 1-3 and the recorded assumptions)
- AGENTS.md project notes (conventions, no logging of secrets)
- docs/ARCHITECTURE.md (stack table)
- api/openapi.yaml `UnifiedPushRegistrationRequest`, and the server `core/push` payload format `{"kind","id"}`
- Parent instructions (scope, CR-1/CR-2 context)

Git range:
- Branch: main
- HEAD: f4865e8644fc60afebadd3b7a064fa5705a06596 (reviewed revision)
- Base/start: 4acbc4be335ca1cfe8ee476142d291f3f912aa73
- Commits reviewed: 9 (f95cd57cf, e163ca5e0, 0b379b07b, bef681fe6, d251563ce, fc20e0af7, c637117ad, ae380a7d7, f4865e864)
- Uncommitted changes: none at the start of the review

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A logout hook timeout during an in-flight PUT skips the DELETE and leaves `deleted` unset**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/push/PushRegistrar.kt:111-118`
    - Category: Correctness
    - Problem: `deleteOnServer()` sets `deleted = instance` only after it gets `requests`. `onNewEndpoint` holds that lock for the whole network PUT. The project's OkHttp client sets no timeouts of its own, so the defaults apply: 10 s connect and 10 s read, with no call timeout. On a slow network the PUT can therefore outlast the 5 s `LOGOUT_HOOK_TIMEOUT_MS`. `withTimeoutOrNull` then cancels the wait for the lock. No DELETE is sent and `deleted` stays null. Other `onNewEndpoint` coroutines may be queued on the lock. While `revokeDevice` runs (up to 5 s more), the session is still logged in, so one of them can still get the lock and send its PUT.
    - Why it matters: In this narrow case the server keeps a registration after a user logout. The impact is small: `clear()` then unregisters at the distributor, the server drops the registration on the distributor's 404/410 (as the task assumption says), and the keys are gone, so any later push is ignored. Still, the comment "drops the ones still waiting" does not hold in this case, and no test covers the hook timeout racing a held lock. The Mutex itself is sound: it cannot deadlock (it is never taken re-entrantly), `return@launch` inside the inline `withLock` releases it, `bestEffort` rethrows cancellation, and the wait for the lock can be cancelled, so the logout's 5 s bound holds.
    - Suggested fix: Set `deleted = instance` before `requests.withLock { ... }`, so waiting and later PUTs are dropped even if the lock wait times out. Optionally, give the PUT its own short `withTimeoutOrNull` so it holds the lock for less than the hook bound. Add a test in which the PUT outlasts the hook timeout.

## Notes (not findings)

- CR-1 from the previous round (PUT and DELETE could reach the server out of order) is fixed in f4865e864. Three tests cover it: a PUT that arrives after the DELETE is not sent, a PUT waiting on the lock is dropped, and the DELETE waits for a PUT already in flight.
- CR-2 from the previous round (the endpoint is sent again on every process start) was left as is on purpose. The task assumption says this is the retry path and that the PUT is idempotent. It is not reported again.
- AC1: the library is in libs.versions.toml and the ARCHITECTURE.md stack table, together with the tink 1.23 bump and the exclusion of plain tink, both recorded as assumptions. `register()` runs at start and after each login. `onNewEndpoint` sends the PUT with `endpoint`/`auth`/`p256dh`, which matches the OpenAPI schema (only `endpoint` is required, and the keys are sent only together). Logout runs the DELETE through the `LogoutHook` before the device is revoked, then calls `clear()` and dismisses the notification. Without a distributor, `selectDistributor()` returns false and nothing is registered.
- AC2: a reminder push enqueues a unique, REPLACE one-time `ReminderSyncWorker`. A mail push shows a notification without any mail content. A push is ignored if it was not decrypted, belongs to another instance, arrives after logout, is unknown, is garbled, is not UTF-8, or is larger than 4 KiB. The payload format matches the server's `PushMessage.toJson()`.
- Security: the service is `exported="false"`. Nothing logs the endpoint or exception details. Plain-text posts to the endpoint are ignored.
- AC3: the tests are in `PushMessageHandlerTest`, `PushRegistrarTest`, `PushInAppTest`, `PushAndroidAdaptersTest`, `LibraryUnifiedPushConnectorTest` and `AuthRepositoryLogoutHookTest`. I did not run them in this review. The criteria are not yet recorded with `tasks.py verify`, and `reviews/unifiedpush-android-client/tests.md` does not exist yet. Both are the parent worker's remaining quality-gate steps.

## Review conclusion

No material issues found.
