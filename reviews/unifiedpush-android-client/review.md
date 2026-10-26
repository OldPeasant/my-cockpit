# Code Review

Date: 2026-10-26

Task: unifiedpush-android-client - Android UnifiedPush client (round 5, final revision after rebase)

Scope: whole task, commits 4acbc4be335ca1cfe8ee476142d291f3f912aa73..0d50a6ee5b6137f294a9a19566e0acac1390c614 (committed changes only; working tree clean). Reviewed revision: 0d50a6ee5b6137f294a9a19566e0acac1390c614.

Requirements:
- tasks/unifiedpush-android-client.md (acceptance criteria 1-3 and recorded assumptions)
- AGENTS.md project notes and conventions
- docs/ARCHITECTURE.md (Android stack table)
- api/openapi.yaml (`/api/v1/push/unifiedpush/{instanceId}`, `UnifiedPushRegistrationRequest`)
- Previous round-4 review of revision 0d881bb2d4a440def7dc64fcc31023fa6a7c7320 (0 findings)
- Parent-supplied test runs (Gradle could not be run in this review): `android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*' --tests 'ch.sonensei.cockpit.auth.AuthRepositoryLogoutHookTest'` -> BUILD SUCCESSFUL, 82 tests, 0 failures; `sh scripts/check.sh` -> pass

Git range:
- Branch: main
- HEAD: 0d50a6ee5b6137f294a9a19566e0acac1390c614
- Base/start: 4acbc4be335ca1cfe8ee476142d291f3f912aa73 (confirmed ancestor of HEAD)
- Commits reviewed: 35 (11 production and test commits, plus review-report, verify, heartbeat, claim and release board commits)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Changes since round 4

`git diff 0d881bb2d HEAD -- android docs` is empty: production code, tests, build files and the stack table are byte-identical to the revision round 4 reviewed. The commits since then are a rebase onto newer main plus commits that touch only `tasks/` and `reviews/` (test report, review report, quality.json). The round-4 result therefore still holds.

## Re-check of the final code

I re-read the production sources in full at HEAD: PushRegistrar.kt, PushMessageHandler.kt, CockpitPushService.kt, UnifiedPushConnector.kt, NewMailNotifier.kt. I also re-read the diffs to AuthRepository.kt, AuthModule.kt, CockpitApp.kt, AndroidManifest.xml, app/build.gradle.kts, libs.versions.toml and ARCHITECTURE.md.

- **Ordering of PUT and logout DELETE.**
    - `deleteOnServer()` sets `deleted` before it waits on the `requests` Mutex (PushRegistrar.kt:115-116). A logout-hook timeout during the wait therefore still drops queued PUTs.
    - `onNewEndpoint` checks login state, the current instance and `deleted` inside the lock (PushRegistrar.kt:89-91).
- **Exception handling.**
    - `bestEffort` rethrows `CancellationException` and swallows the rest without logging. This is correct, because the endpoint grants the right to push.
    - `register`/`clear` catch checked library exceptions. `clear()` still forgets the instance when unregistering fails.
- **Logout hook.** AuthRepository runs the hooks under `NonCancellable`, each bounded by `LOGOUT_HOOK_TIMEOUT_MS`, before the device revocation. A failing hook does not stop the logout. Logouts the device did not start skip the hooks, as the recorded assumption says, and the `SessionCache` still unregisters locally and dismisses the notification.
- **Message handling.**
    - Only decrypted pushes for the current instance while logged in are acted on.
    - The payload is bounded to 4096 bytes and decoded with strict UTF-8. Unknown kinds and non-UUID ids return null.
    - A reminder push enqueues the unique `push-reminders-sync` work with REPLACE and a network constraint. A mail push shows one content-free notification on channel `mail`, checks the permission and catches `SecurityException`.
- **Integration.**
    - The generated `UnifiedPushRegistrationRequest(endpoint, auth, p256dh)` matches the OpenAPI schema: `endpoint` is required, and `auth`/`p256dh` are optional and sent both or neither.
    - The service is declared `exported="false"` with the connector's `PUSH_EVENT` action.
    - The tink exclusion and the Tink 1.23.0 bump match the recorded assumption and the stack table.
- **Security.** No secrets, endpoints, or push or mail contents are logged or shown. The notification text is fixed string resources only.

## Acceptance criteria check (code inspection plus parent test evidence)

- **AC1:** Connector 3.3.5 is in libs.versions.toml and the ARCHITECTURE.md stack table. Registration runs at app start and after each login (`PushRegistrar.start` from `CockpitApp.onCreate`). The endpoint goes to `PUT /api/v1/push/unifiedpush/{instanceId}` through the generated PushApi. A user logout deletes the endpoint on the server through the `LogoutHook`, and every logout unregisters at the distributor through a `SessionCache`. With no distributor, `selectDistributor()` returns false and only the periodic syncs run.
- **AC2:** A reminder push runs ReminderSyncWorker once. A mail push shows the new-mail notification. Unknown, garbled, undecrypted, foreign-instance and after-logout pushes are ignored.
- **AC3:** Tests under `ch.sonensei.cockpit.push` (PushMessageHandlerTest, PushRegistrarTest, PushAndroidAdaptersTest, LibraryUnifiedPushConnectorTest, PushInAppTest) cover message handling, registration and ordering, the adapters, the connector and the in-app wiring. The parent's run of the named filter passed (82 tests including AuthRepositoryLogoutHookTest). The verify evidence in the task file names the command and its result.

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
