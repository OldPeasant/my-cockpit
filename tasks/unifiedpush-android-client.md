---
title: Android UnifiedPush client
status: in_progress
owner: dell26/agent-1
deps: [and-reminders, unifiedpush-server]
prio: 3
parent: unifiedpush-android
lease_until: 2026-10-26T20:19:14Z
extended: 
releases: 2
---
## Goal
Part of `unifiedpush-android` (UnifiedPush for Android), which was split.
Goal: The app registers with a UnifiedPush distributor (ntfy) and reacts to server pushes; WorkManager periodic sync stays as the fallback.

## Acceptance criteria
- [x] UnifiedPush connector library added (libs.versions.toml, ARCHITECTURE.md stack table); registration on login, endpoint sent to `PUT /api/v1/push/unifiedpush/{instanceId}`, unregistered and deleted on logout; no distributor installed -> periodic sync only
  - verified (2026-10-26 21:04+0100 by dell26/agent-1): android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*' --tests 'ch.sonensei.cockpit.auth.AuthRepositoryLogoutHookTest' -> 82 passed (PushRegistrarTest 28: register on login/app start, PUT /push/unifiedpush/{id} with keys, logout DELETE+unregister, no distributor -> no registration; AuthRepositoryLogoutHookTest 9); connector 3.3.5 in libs.versions.toml + ARCHITECTURE.md stack table (inspected)
- [x] A reminder push runs the reminder sync; a new-mail push shows a new-mail notification (or runs the mail sync); unknown or garbled messages are ignored
  - verified (2026-10-26 21:04+0100 by dell26/agent-1): android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*' -> PushMessageHandlerTest 12 + PushInAppTest 15 + PushAndroidAdaptersTest 11 passed: reminder push enqueues ReminderSyncWorker, mail push shows content-free notification, unknown/garbled/non-UTF-8/oversized/undecrypted/after-logout pushes ignored
- [x] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*'` passes, covering message handling
  - verified (2026-10-26 21:04+0100 by dell26/agent-1): android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*' (same as cd android && ./gradlew ...) -> BUILD SUCCESSFUL, 73 push tests passed, 0 failures (message handling: PushMessageHandlerTest 12)

## Notes
- Work done on `unifiedpush-android` before the split is on `origin/wip/unifiedpush-android` (if it exists): merge it first if your part builds on it.
- ASSUMPTION (2026-10-26 17:38+0100 by dell26/agent-2): UnifiedPush connector 3.3.5 (Maven Central) brings plain tink 1.23 which duplicates tink-android classes: tink-android bumped 1.18.0 -> 1.23.0 (same version the connector is built against) and plain tink excluded
- ASSUMPTION (2026-10-26 17:38+0100 by dell26/agent-2): Distributor choice has no UI: the saved one, else the system default, else (several, no default) the first installed one; no distributor -> no registration, the existing WorkManager periodic syncs stay the only sync
- ASSUMPTION (2026-10-26 17:38+0100 by dell26/agent-2): Instance id = random UUID per login (SharedPreferences 'push'); registration runs at app start and after each login (PersistentTokenStore.session); endpoint PUT is best effort without retry (next app start registers again; the distributor re-sends the endpoint, the PUT is idempotent)
- ASSUMPTION (2026-10-26 17:38+0100 by dell26/agent-2): Logout: new LogoutHook multibinding in AuthRepository runs before device revocation so DELETE /push/unifiedpush/{id} is still authenticated (5 s bound); non-user logouts (refresh rejected) only unregister locally via SessionCache, the server drops the endpoint on 404/410
- ASSUMPTION (2026-10-26 17:38+0100 by dell26/agent-2): Pushes are only acted on when the connector decrypted them (RFC 8291): plain-text posts to the endpoint (anyone knowing the ntfy topic) are ignored. Reminder push -> one-time ReminderSyncWorker (unique 'push-reminders-sync', REPLACE); mail push -> one content-free 'New mail' notification on channel 'mail' that opens the app
- extended by dell26/agent-2: Implementation + tests committed (d75f6b0b2..99a8fa85e). Left: 3 failing tests in push.* expose production bugs: (1) PushRegistrar lets checked exceptions (KeyStoreException) from the connector escape register/unregister (PushRegistrarTest:270,280) -> catch Exception, unregister must still forget the instance; (2) LibraryUnifiedPushConnector selects no distributor when several are installed without a link activity -> pick first installed. Then quality gate (test-writer, code-reviewer, fixer, reviews/unifiedpush-android-client/quality.json), tasks.py verify 1-3, done.
- release by dell26/agent-2: Implementation complete (09ad3887b feat + fixes 00798e1dc, 58750bac3) with tests (394f10a34, 9f8db5df3, 489062419); push.* + AuthRepositoryLogoutHookTest pass locally. Left: quality gate only. reviews/unifiedpush-android-client/review.md (0 P0-P2, 2 P3) covers ceccb06 = pre-fix code, so it is STALE: re-run test-writer (no tests.md yet) and code-reviewer on HEAD, fixer if needed, write quality.json, record verify 1-3 (AC3 cmd: cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*'), run .taskcheck, then done.
- extended by dell26/agent-2: Implementation + fixes done (f9d2b644a..0efde6956); push.* tests pass locally (cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*'). Left: quality gate only: run test-writer (no reviews/unifiedpush-android-client/tests.md yet) and code-reviewer on HEAD (existing review.md may predate fix b48f9aaa3 - check reviewed revision), fixer if needed, write quality.json, tasks.py verify 1-3, .taskcheck, done.
- release by dell26/agent-2: Implementation is done and committed (push package, LogoutHook in AuthRepository, connector 3.3.5 in libs.versions.toml and the ARCHITECTURE stack table). fixes.md covers round-1 CR-1 (Mutex orders PUT/DELETE); CR-2 was left as is on purpose (assumption). .taskcheck ran with no failures in its output. Left: (1) round-2 review.md P3 CR-1: in PushRegistrar.deleteOnServer set deleted=instance BEFORE requests.withLock, so a lock wait that times out (5s LOGOUT_HOOK_TIMEOUT_MS) still drops queued PUTs, and add a test; run fixer for this. (2) test-writer was never run: tests.md is missing (needs task-scoped coverage). (3) Re-run code-reviewer on the final code. (4) tasks.py verify for AC1-3 (run: cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*'). (5) Write quality.json, then done.
