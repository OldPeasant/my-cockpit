# Fix Report

Date: 2026-10-26

Task: unifiedpush-android - UnifiedPush for Android

Scope: current task; findings CR-1 (P2) and CR-2 (P3) of `reviews/unifiedpush-android/review.md`. Base commit e3fa1fb302ad85caf59c2cdd697d84c3824e338d.

## Fixed findings

### CR-1 — A reminder push cannot deliver the reminder it announces when the device did not already have it

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/push/PushReminderDelivery.kt` (new)
- `android/app/src/main/java/ch/sonensei/cockpit/push/PushMessageHandler.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/push/NewMailNotifier.kt` (`WorkManagerPushSyncTrigger`)
- `android/app/src/main/java/ch/sonensei/cockpit/push/CockpitPushService.kt` (entry point)
- `android/app/src/main/java/ch/sonensei/cockpit/reminders/ReminderScheduler.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/push/PushReminderDeliveryTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/push/PushMessageHandlerTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/push/PushInAppTest.kt`

Problem:
Validated against the code. A reminder push only enqueued the reminder sync. The server answers that sync from `GET /api/v1/reminders/upcoming`, which lists only reminders that have not fired yet. The pushed reminder had already fired, so the sync never returned it. A reminder that was created on another client after the last sync was therefore never shown on the device.

Fix:
- `PushSyncTrigger` now also has `showReminder(id)`. On a reminder push, `PushMessageHandler` calls `showReminder(event.id)` and then `syncReminders()`, so the existing sync still runs.
- `WorkManagerPushSyncTrigger.showReminder` enqueues a one-time `PushReminderWorker`:
  - the work waits for a network connection (`NetworkType.CONNECTED`);
  - it gets the reminder id as input data;
  - it uses a unique work name per reminder (`push-reminder-<id>`) with policy KEEP, so it never replaces the `push-reminders-sync` work or the periodic `reminders-sync` work.
- `PushReminderDelivery.deliver(id)`:
  - does nothing while logged out;
  - otherwise calls the generated `RemindersApi.getReminder(id)` (`GET /api/v1/reminders/{id}`);
  - ignores the answer when it is not 2xx (404 included), when the body is missing or unreadable, when it belongs to another id, when `firedAt` is absent (a pending reminder is left to its local alarm), or when the item is unavailable (trashed).
  - An `IOException` returns OFFLINE and the worker retries. A `CancellationException` is rethrown.
  - Nothing is logged.
- New `@Synchronized ReminderScheduler.showFiredByServer(reminder, stillValid)`:
  - it holds the same lock as `clear()`, `fire()` and `apply()`;
  - it checks `stillValid` (`session.isLoggedIn`) and `ReminderStore.notifiedIds()`, calls `markNotified`, and shows the reminder through the existing `ReminderNotifier`, all under that lock;
  - so a logout during the request can never leave a notification behind;
  - a reminder that was already shown (by its local alarm or an earlier push) is not shown again;
  - after a push has shown a reminder, its local alarm (`fire()`) does not show it again.

Known limit (unchanged behavior of the existing store, not introduced here): `ReminderScheduler.apply` keeps notified ids only while `/upcoming` lists them. If the distributor delivers the same push again after a later sync, the same notification (same tag) can be posted once more.

Validation:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*' --tests 'ch.sonensei.cockpit.reminders.*'` — Pass. Push: 8 suites, 94 tests, 0 failures. Reminders: 9 suites, 98 tests, 0 failures. New tests:
  - `PushReminderDeliveryTest` (16), which covers:
    - an unsynced fired reminder is loaded by id and shown once;
    - a second push does not show it again;
    - a reminder the alarm already showed is not shown again;
    - after the push showed a reminder, its alarm does not show it again;
    - logged out: no request is sent;
    - a logout during the request: nothing is shown;
    - 404, 500 and an unreadable body are ignored;
    - a trashed item, a reminder that has not fired, and an answer for another id are ignored;
    - offline returns OFFLINE and the worker retries;
    - a cancelled request is rethrown;
    - the worker handles its input, and fails on a missing or invalid id;
    - the trigger enqueues one unique network-constrained work per reminder.
  - `PushInAppTest`: the delivery from the real Hilt graph sends an authenticated `GET /api/v1/reminders/{id}`.
  - `PushMessageHandlerTest` and `PushInAppTest`: a reminder push calls both `showReminder(id)` and the sync.
- `cd android && ./gradlew ktlintCheck lintDebug` — Pass

### CR-2 — The registration key format and PUT result are not pinned across the two sides

Files changed:
- `android/app/src/test/java/ch/sonensei/cockpit/push/PushRegistrationKeysContractTest.kt` (new)

Problem:
Validated. The app tests used placeholder keys that the server would reject. No test showed that the keys the connector produces reach the PUT body in the format `WebPushKeys` accepts.

Fix:
- I checked the format in the connector itself (`org.unifiedpush.android.connector:connector:3.3.5`, `internal/keys/UtilsKt`). It fills `PublicKeySet` with `serialize(ECPublicKey)` and `b64encode(ByteArray)`. A probe run under Robolectric showed that both produce unpadded base64url, and that `serialize` gives the 65-byte uncompressed point.
- The new Robolectric test:
  - generates a real P-256 key pair and a fictitious 16-byte secret that contains the bytes `0xFB`/`0xEF`, which differ between base64 and base64url;
  - encodes both with the connector's own encoders (by reflection, so a format change in a library upgrade fails the test);
  - passes them through `PushRegistrar.onNewEndpoint` the way `CockpitPushService.onNewEndpoint` does;
  - asserts the PUT path and that the body has exactly the fields `endpoint`, `p256dh` and `auth`;
  - asserts that the keys are sent unchanged and match `^[A-Za-z0-9_-]+$` (no padding);
  - asserts that `p256dh` decodes to 65 bytes starting with `0x04` with the generated X and Y coordinates, and that `auth` decodes to the 16-byte secret.
- Server side: an existing test already covers it, so no server change was made. `server/src/test/kotlin/ch/sonensei/cockpit/core/push/UnifiedPushWebTests.kt` ("PUT stores the endpoint and a second PUT replaces it") sends `PushTestKeys.P256DH` and `PushTestKeys.AUTH` and expects 204. Those are a real P-256 point and a 16-byte secret, both unpadded base64url (`Base64.getUrlEncoder().withoutPadding()`). `UnifiedPushWebEdgeCaseTests` also accepts the padded form.
- The optional part (treat a non-2xx `Response` as a failure in `PushRegistrar.bestEffort`) was not done. Nothing reacts to the failure, because the call stays silent best effort by design, so it would change no behavior. The review marked it optional.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*'` — Pass (`PushRegistrationKeysContractTest` 1/1)
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.push.UnifiedPushWebTests'` — Pass (5/5, Testcontainers Postgres)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*' --tests 'ch.sonensei.cockpit.reminders.*'` — Pass (push 94 tests, reminders 98 tests, 0 failures)
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.push.UnifiedPushWebTests'` — Pass (5/5)

Full test suite:
- `sh scripts/check.sh` (lint and unit tests of server, web and android) — Pass ("all checks passed")

Lint/type-check/build:
- `cd android && ./gradlew ktlintCheck lintDebug` — Pass

`.taskcheck`:
- Pass (`sh scripts/check.sh`, exit 0)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
