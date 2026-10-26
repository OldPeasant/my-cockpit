# Code Review

Date: 2026-10-26

Task: unifiedpush-android - UnifiedPush for Android (integration task of the split into unifiedpush-server and unifiedpush-android-client); final review round

Scope: the task's commits 44885e9fb..4681a7a90 (final code), including a check that the fixes for the previous round's findings (CR-1, CR-2 in the earlier review; fixes in `reviews/unifiedpush-android/fixes.md`) are correct.

Reviewed revision: 4681a7a9098eb4ebc55c0882baf93f44a921d4cf

Requirements:
- tasks/unifiedpush-android.md (acceptance criteria 1 and 2; assumptions: no device/ntfy here, reminder push loads the reminder by id via `ReminderScheduler.showFiredByServer`, redelivery after a later sync may re-post)
- SPEC.md section 2 (Android: periodic sync, local alarms, UnifiedPush/ntfy optional)
- docs/ARCHITECTURE.md, AGENTS.md project notes (no logging of user content, fictitious test data)
- Server contract: `server/.../core/push/domain/PushDispatcher.kt` (reminder push after fire), `server/.../core/reminders/api/ReminderController.kt` (`GET /api/v1/reminders/{id}`, `ReminderResponse.firedAt`, `item.available`)

Git range:
- Branch: main
- HEAD: 4681a7a9098eb4ebc55c0882baf93f44a921d4cf
- Base/start: 44885e9fbfb8aac9a868b31a58a4dfed9857d8e3
- Commits reviewed: 5 (499dfea43, 8dad9d477, b2435b512, 26ebcc4ed, 4681a7a90)
- Uncommitted changes: none at review start

Commands run (read-only):
- `git log --oneline 44885e9fb..4681a7a90`, `git diff --stat` and full diff of `android/app/src/main` and the push test files
- read: ReminderScheduler.kt (full), AuthSession.kt, ReminderStore `toScheduled`, ReminderController.kt (GET by id, response schema), PushDispatcher `onReminderFired`
- `git grep` for a custom WorkerFactory / `Configuration.Provider` (none: the default factory calls the `(Context, WorkerParameters)` constructor, as `PushReminderWorker` provides)
- test results in `android/app/build/test-results/testDebugUnitTest` (2026-10-26T22:45Z): push suites PushRegistrarTest 28, PushRegistrationKeysContractTest 1, PushServerContractTest 2, LibraryUnifiedPushConnectorTest 7, PushInAppTest 17, PushReminderDeliveryTest 18, PushAndroidAdaptersTest 12, PushMessageHandlerTest 12; ReminderShowFiredByServerTest 7; all 0 failures, 0 errors, 0 skipped
- `python tasks.py verify unifiedpush-android` (listing only): both criteria not yet recorded

Checks of the previous fixes (no finding):
- CR-1 fix is correct. A decrypted, current-instance reminder push calls `showReminder(id)` and then `syncReminders()`. The worker is unique per reminder (`push-reminder-<id>`, KEEP), needs the network, and never replaces `push-reminders-sync` or the periodic `reminders-sync`. `PushReminderDelivery` calls the existing `GET /api/v1/reminders/{id}`. It shows the reminder only for a 2xx answer with a matching id, `firedAt` set and `item.available`. `IOException` gives a retry, `CancellationException` is rethrown, and nothing is logged. `showFiredByServer` holds the same lock as `clear()`/`fire()`/`apply()` and checks `isLoggedIn` under that lock. `AuthSession.logout` clears the tokens before the session caches are cleared, so a logout during the request cannot leave a notification behind (same pattern as `apply`). Ordering against the sync is safe both ways: if the sync runs first, the reminder stays in the store as `firedByServer`; the push then marks it notified and the later alarm's `fire()` returns null. If the push runs first, `retainNotified` drops the id on the next sync.
- CR-2 fix is correct. `PushRegistrationKeysContractTest` encodes a real P-256 key and 16-byte secret with the connector's own encoders and pins the PUT body (unpadded base64url, 65-byte uncompressed point). The server already pins acceptance of that format in `UnifiedPushWebTests`. The optional non-2xx handling was declined with a reason, which is acceptable for a best-effort call.
- The test changes do not weaken earlier tests: existing assertions are kept, and the "ignored" cases now also assert that `showReminder` is not called.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Second copy of the ReminderResponse to ScheduledReminder mapping**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/push/PushReminderDelivery.kt:63` (duplicates `android/app/src/main/java/ch/sonensei/cockpit/reminders/ReminderStore.kt:31`)
    - Category: Quality
    - Problem: `ReminderResponse.toFired()` repeats the field mapping of `ReminderResponse.toScheduled()` (id, itemType, itemId, title, remindAtMillis). Only the `firedAt` guard is inverted.
    - Why it matters: If a field is later added to `ScheduledReminder` (for example a deep link for the notification tap), it has to be added in both places. A push-shown reminder could then look or behave differently from an alarm-shown one, and no test compares the two.
    - Suggested fix: Pull the shared mapping out of `toScheduled` into one internal function in `reminders/` (for example `toScheduledReminder()` without the `firedAt` check). Have both `toScheduled` and `toFired` apply their own `firedAt`/`available` guard and then call it.

## Review conclusion

No material issues found. Both fixes from the previous round are correct and tested, and the reminder/mail push paths line up with the server contract (payload, `GET /reminders/{id}`, registration keys). The periodic sync fallback is unchanged. CR-1 is an optional cleanup and does not block completion.

Notes for the parent worker (not findings):
- The duplicate re-post that the recorded assumption describes is real but limited. A distributor that queues pushes while the device is offline (ntfy caches messages) can, after reconnect, deliver a reminder push after a sync has already pruned that id from the notified set. The same notification tag is then posted once more. The assumption covers this.
- Both acceptance criteria are still unrecorded in `tasks.py verify`. Per the recorded assumption, criterion 1's on-device/ntfy part cannot run here: record that part with `--skip` and a reason, plus the combined server and Android test evidence, rather than claiming it.
