# Code Review

Date: 2026-10-27

Task: unifiedpush-android - UnifiedPush for Android (integration task of the split into unifiedpush-server and unifiedpush-android-client); review of the final code

Scope: `git diff origin/main HEAD` (all task-owned commits, final code after the rebase/merge of `origin/wip/unifiedpush-android`)

Reviewed revision: 53cdfe6d490dda9a6cc3900a98fb5d692c3115e7

Requirements:
- tasks/unifiedpush-android.md (acceptance criteria 1 and 2; assumptions: no device/emulator/ntfy in the agent environment, so end-to-end evidence combines server and Android tests; a reminder push loads the reminder by id and shows it once via `ReminderScheduler.showFiredByServer`; a redelivered push after a later sync may re-post the notification)
- SPEC.md section 2 (Android: periodic sync, local alarms, UnifiedPush/ntfy optional)
- docs/ARCHITECTURE.md, docs/QUALITY.md, AGENTS.md project notes (no logging of user content, fictitious test data, stay inside `touches`)
- Server side of the contract: `server/.../core/push/domain/PushDispatcher.kt` (reminder push sent by a `@TransactionalEventListener` after the fire transaction commits), `server/.../core/reminders/domain/ReminderService.kt` (`ReminderFiredEvent` only for reminders whose item still exists), `server/.../core/reminders/api/ReminderController.kt` (`GET /api/v1/reminders/{id}`, `firedAt`, `item.available`)

Git range:
- Branch: main
- HEAD: 53cdfe6d490dda9a6cc3900a98fb5d692c3115e7
- Base/start: 6c501511638ac9750976e9d8b9aca232231f8116 (origin/main)
- Commits reviewed: 8 (0827bb996, e1dd3cb93, f3e5e1e6c, f29a214cc, b65cc62e3, 9bf2f18a1, c7d8f8bf5, merge 53cdfe6d4)
- Uncommitted changes: only the untracked test-writer report `reviews/unifiedpush-android/tests.md`; not reviewed as code

Commands run (read-only):
- `git log --oneline origin/main..HEAD`, `git diff --stat origin/main HEAD`, full diff of `android/app/src/main`
- `git diff --stat 4681a7a90 HEAD` (previously reviewed revision to now): production code unchanged; only `PushReminderDeliveryTest.kt` (+35 lines) in the task's code, plus `docs/deploy-runbook.md` arriving from main, and task/review files
- read in full: `PushReminderDelivery.kt`, `PushMessageHandler.kt`, `NewMailNotifier.kt` (incl. `WorkManagerPushSyncTrigger`), `ReminderScheduler.kt`, `ReminderStore.kt` mapping, `PushReminderDeliveryTest.kt`; server `PushDispatcher.kt`, `ReminderFiredEvent` publishing, `ReminderController` GET by id
- generated client signature `RemindersApi.getReminder(id: UUID): Response<ReminderResponse>` (suspend) matches the use in `PushReminderDelivery`
- test results in `android/app/build/test-results/testDebugUnitTest` (2026-10-26T23:00Z run): LibraryUnifiedPushConnectorTest 7, PushAndroidAdaptersTest 12, PushInAppTest 17, PushMessageHandlerTest 12, PushRegistrarTest 28, PushRegistrationKeysContractTest 1, PushReminderDeliveryTest 20, PushServerContractTest 2, ReminderShowFiredByServerTest 7 = 106 tests, 0 failures, 0 errors; matches the parent's reported run
- `python tasks.py verify unifiedpush-android` (listing only): both criteria not yet recorded

Checks (no finding):
- Reminder push flow: a decrypted push for the current instance while logged in calls `showReminder(id)` then `syncReminders()`. The delivery worker is unique per reminder (`push-reminder-{id}`, KEEP) with a network constraint, and does not replace `push-reminders-sync` (REPLACE) or the periodic sync. Periodic sync as fallback is untouched.
- `PushReminderDelivery`: logged-out check before any request; `CancellationException` rethrown; `IOException` gives OFFLINE/retry; other exceptions ignored without logging (no user data in logs); only a 2xx with a body whose id matches, `firedAt` set and `item.available` is shown.
- Concurrency with logout: `showFiredByServer` is `@Synchronized` like `clear()`, `fire()` and `apply()`, and checks `isLoggedIn` under that lock, so a logout during the request cannot leave a notification behind. Ordering against the local alarm and the sync is safe in both directions (the notified set is shared; `retainNotified` prunes ids the server no longer lists).
- Server contract: the push is sent only after the fire transaction commits, so `GET /reminders/{id}` sees `firedAt`; the payload format `{"kind","id"}` is pinned by `PushServerContractTest`; registration key encoding by `PushRegistrationKeysContractTest`.
- The change to `reminders/ReminderScheduler.kt` lies outside the task's `touches`, but it is small, additive and recorded as an assumption.
- The new test cases in c7d8f8bf5 (pushed reminder opens its item with the reminder's time; untitled item) assert behavior through Robolectric notifications and intents; test data is fictitious. No earlier assertions were removed or weakened.

Summary: 0 P0, 0 P1, 0 P2, 2 P3

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
    - Problem: `ReminderResponse.toFired()` repeats the field mapping of `ReminderResponse.toScheduled()` (id, itemType, itemId, title, remindAtMillis). Only the `firedAt` guard is inverted. (Carried over from the previous review; still present.)
    - Why it matters: A field later added to `ScheduledReminder` must be added in both places, or a push-shown reminder looks or behaves differently from an alarm-shown one; no test compares the two mappings.
    - Suggested fix: Extract the shared mapping into one internal function in `reminders/` (without the `firedAt` check) and have `toScheduled` and `toFired` apply their own guard before calling it.

- [ ] **CR-2 | A 5xx answer (e.g. proxy 502/503 during a server restart) is not retried**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/push/PushReminderDelivery.kt:54` (and the comment at line 92)
    - Category: Correctness
    - Problem: Any non-2xx answer, including 502/503/504, maps to `IGNORED`, so the worker returns `Result.success()` and never retries. Only an `IOException` gives `OFFLINE`/retry. The worker comment "Network up but server down: retry with backoff" therefore only holds when the server is reached directly; in the documented deployment (`docs/deploy-runbook.md`, Caddy in front of the server) a down backend answers 502 instead of failing the connection.
    - Why it matters: For a reminder this device never synced (the case this delivery path exists for), the push is the only way it reaches the device: the reminder sync lists only pending reminders. A transient 5xx right after the push loses that notification. Probability is low because the server sent the push moments before, so this is minor.
    - Suggested fix: Treat 5xx (or at least 502/503/504) as `OFFLINE` so the worker retries with backoff, keep 4xx as `IGNORED`, and add a delivery test for a 503 answer; or adjust the comment to state that only connection failures are retried.

## Review conclusion

No material issues found. The final code satisfies the reviewed behavior for both acceptance criteria as far as code and tests can show it: registration, reminder and new-mail push handling, and the unchanged periodic sync fallback line up with the server contract, and the 106 focused tests pass. Both findings are optional low-priority improvements and do not block completion.

Notes for the parent worker (not findings):
- Both acceptance criteria are still unrecorded in `tasks.py verify`. Criterion 1's on-device/ntfy part cannot run here (recorded assumption): record the combined server tests (WireMock distributor, RFC 8291 encryption, reminder and mail pushes) and Android tests as evidence and mark the on-device part as not run, rather than claiming it.
- The re-post of a notification for a push redelivered after a later sync (ntfy caches messages while the device is offline) is real but limited, and is covered by the recorded assumption.
