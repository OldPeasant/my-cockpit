# Code Review

Date: 2026-10-26

Task: unifiedpush-android - UnifiedPush for Android (integration task of the split into unifiedpush-server and unifiedpush-android-client)

Scope: the task's own commit 32f761b4a (adds `android/app/src/test/java/ch/sonensei/cockpit/push/PushServerContractTest.kt`), plus a read-only integration review of the already-merged server side (`server/.../core/push`) and Android client (`android/.../cockpit/push`) against this task's acceptance criteria.

Requirements:
- tasks/unifiedpush-android.md (acceptance criteria 1 and 2, recorded assumption about no device/ntfy)
- tasks/unifiedpush-server.md and tasks/unifiedpush-android-client.md (subtask criteria and assumptions, for context)
- SPEC.md section 2 (Android push: WorkManager periodic sync 15 min, local AlarmManager reminders, UnifiedPush/ntfy optional)
- docs/ARCHITECTURE.md stack table, AGENTS.md project notes
- api/openapi.yaml (Push tag, `/api/v1/push/unifiedpush/{instanceId}`, `UnifiedPushRegistrationRequest`)

Git range:
- Branch: main
- HEAD: 0da24853f2a4556586ebdf412ac78565935fd58d
- Base/start: 6f4f9f3219df0df732133275508bafd8e8862b48
- Commits reviewed: 2 (32f761b4a test change, rebased as 0da24853f; ec8d9da4a touches only tasks/)
- Uncommitted changes: none

Commands run (read-only):
- `git log --oneline 6f4f9f32..HEAD`, `git show 32f761b4a`, `git status --short`
- read: PushServerContractTest.kt, CockpitPushService.kt, PushMessageHandler.kt, PushRegistrar.kt, UnifiedPushConnector.kt, NewMailNotifier.kt, CockpitApp.kt, reminders/ReminderSync.kt, reminders/ReminderScheduler.kt, AndroidManifest.xml (push service)
- read: UnifiedPushController.kt, PushMessage.kt, PushDispatcher.kt, UnifiedPushService.kt, UnifiedPushRegistration.kt, WebPushKeys.kt, infra/WebPushCrypto.kt, infra/UnifiedPushClient.kt, ReminderController `upcoming`, ReminderRepository `findPending`, mail/sync/NewMailAnnouncer.kt publish site
- read: generated client `android/app/build/generated/openapi/.../apis/PushApi.kt` and `models/UnifiedPushRegistrationRequest.kt`; `api/openapi.yaml` Push paths and schema
- `grep` of the server tests pinning the payload (`PushDispatcherTests`, `PushEndToEndTests`)
- `grep` of `android/app/build/test-results/testDebugUnitTest/TEST-ch.sonensei.cockpit.push.*.xml`: 6 suites, 75 tests, 0 failures, 0 errors, 0 skipped (2026-10-26T22:01Z, includes PushServerContractTest 2/2)
- `python tasks.py verify unifiedpush-android`: both criteria still unrecorded at review time

Integration checks (no finding):
- Registration contract: app `PushApi.registerUnifiedPush` is `PUT api/v1/push/unifiedpush/{instanceId}` with JSON body `{endpoint, auth?, p256dh?}`; server `@PutMapping("/{instanceId}")` under `/api/v1/push/unifiedpush`, same field names, keys both-or-neither (app sends both or neither). Instance id is `UUID.randomUUID().toString()`, matching `^[A-Za-z0-9._-]{1,128}$`. DELETE matches `unregisterUnifiedPush`. The generated client is built from the same `api/openapi.yaml`.
- Payload: server `PushMessage.toJson()` writes `{"kind":"reminder"|"mail","id":"<uuid>"}`; app `parsePushEvent` reads exactly those keys and kinds, tolerates unknown keys, size limit 4096 is well above the ~60 bytes. The new contract test pins this; server tests pin the same literal format.
- Encryption: when keys are registered the server encrypts (RFC 8291 `aes128gcm`); the app only acts on `decrypted == true`, which is consistent because the app always forwards the connector's key set when present.
- Key encoding: server accepts base64url with or without padding (`WebPushKeys.decode`), which is the documented connector format (unpadded base64url). Not pinned by a test; see CR-2.
- Fallback: `CockpitApp` only starts `PushRegistrar`; the periodic `reminders-sync` (15 min, `WorkManagerReminderSyncScheduler`) and GTD periodic sync are untouched; registration failure or no distributor leaves them as the only path. The push-triggered work uses its own unique name `push-reminders-sync`, so it never replaces the periodic work.
- Mail push: server publishes `PushMessage(PushKind.MAIL, ...)` from `NewMailAnnouncer`; app shows a content-free notification.

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A reminder push cannot deliver the reminder it announces when the device did not already have it**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/push/PushMessageHandler.kt:89` (with `server/src/main/kotlin/ch/sonensei/cockpit/core/reminders/infra/ReminderRepository.kt:37` and `server/src/main/kotlin/ch/sonensei/cockpit/core/push/domain/PushDispatcher.kt:30`)
    - Category: Integration
    - Problem: The server sends the reminder push from `onReminderFired`, after the transaction that set `firedAt` commits. The app reacts by running `ReminderSyncWorker`, which calls `GET /api/v1/reminders/upcoming`, backed by `findPending` (`where r.firedAt is null`). The reminder named in the push is therefore never in the response. `ReminderScheduler.apply` keeps fired reminders only if they are already in the local store (`firedByServer`). A reminder created on another client after the device's last sync (up to 15 minutes, longer under Doze) and due before the next sync is not shown on the device, even though the server pushed it. The app ignores `PushEvent.Reminder.id`.
    - Why it matters: Showing reminders the local alarms cannot know about is the main reason a reminder push exists (alarms already cover known reminders). End to end, the reminder push does not do this. This is not caught today because the Android tests stop at "a ReminderSyncWorker is enqueued" and the server tests stop at "the distributor received the payload". AC1 is met literally ("server sends reminder pushes"), so this is P2, not P1.
    - Suggested fix: On a reminder push, load the announced reminder by id (`GET /api/v1/reminders/{id}`, which already exists) and notify it through the existing `ReminderScheduler.fire`/notifier path if it is not yet notified. Keep the sync as well. Alternatively, record an assumption that the reminder push only refreshes alarms and does not deliver newly fired reminders. Add a test that covers this case.

## P3 Low

- [ ] **CR-2 | The registration key format and PUT result are not pinned across the two sides**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/push/PushInAppTest.kt:178`, `android/app/src/main/java/ch/sonensei/cockpit/push/PushRegistrar.kt:95`
    - Category: Tests
    - Problem: The new contract test pins the payload direction (server to app) but not the registration direction. The app-side tests use placeholder keys (`"fictitious-p256dh"`, `"BPubKey"`), which the server's `WebPushKeys` would reject with 400. No test shows that the key strings the connector's `PublicKeySet` produces (unpadded base64url of a 65-byte point and a 16-byte secret) pass `WebPushKeys` and `UnifiedPushService.register`. The PUT result is also never checked: the generated `registerUnifiedPush` returns `Response<Unit>`, so a 400 does not throw, and `bestEffort` silently drops it.
    - Why it matters: If either side's key encoding drifts, every registration fails silently. The app falls back to periodic sync with no signal, and no test fails.
    - Suggested fix: Add an app-side test (like PushServerContractTest) that builds a real P-256 key pair and 16-byte secret, encodes them the way the connector does, and asserts the PUT body shape. Add a server-side test that registers those exact strings and gets 204. Optionally, treat a non-2xx `Response` as a failure inside `bestEffort` so it stays visible in tests.

## Review conclusion

Material issues found; fix before completing the task. The task's own commit (the contract test) is correct and passes, and the PUT/DELETE, payload, encryption and periodic-sync fallback line up between server and app. CR-1 is an end-to-end gap in what a reminder push achieves. The parent worker must fix it or record an explicit assumption before completing the task. CR-2 is a test hardening item. Criterion 1 involves running behavior (device, ntfy distributor). The recorded assumption says it cannot be run here, so it should be recorded with `--skip` for the on-device part rather than claimed.
