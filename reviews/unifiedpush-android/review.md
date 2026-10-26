# Code Review

Date: 2026-10-27

Task: unifiedpush-android - UnifiedPush for Android (integration task of the split into unifiedpush-server and unifiedpush-android-client); final review after the round-2 fix

Scope: `git diff origin/main HEAD` (all task-owned commits, final code), with focus on commit 419fb4a18 (5xx answer gives OFFLINE/retry)

Reviewed revision: 419fb4a188b0e700d4e9ef9366b172767f3ac2c1

Requirements:
- tasks/unifiedpush-android.md (acceptance criteria 1 and 2 and the recorded assumptions)
- SPEC.md section 2 (Android: periodic sync, local alarms, UnifiedPush/ntfy optional)
- docs/ARCHITECTURE.md, docs/QUALITY.md, AGENTS.md project notes
- reviews/unifiedpush-android/fixes.md round 2 (parent decisions on the previous CR-1 and CR-2)

Git range:
- Branch: main
- HEAD: 419fb4a188b0e700d4e9ef9366b172767f3ac2c1
- Base/start: 6c501511638ac9750976e9d8b9aca232231f8116 (origin/main)
- Commits reviewed: 9 (0827bb996, e1dd3cb93, f3e5e1e6c, f29a214cc, b65cc62e3, 9bf2f18a1, c7d8f8bf5, merge 53cdfe6d4, 419fb4a18)
- Uncommitted changes: only the test-writer report `reviews/unifiedpush-android/tests.md`; not reviewed as code

Commands run (read-only):
- `git log --oneline 6c5015116..HEAD`, `git diff --stat origin/main HEAD`, `git show 419fb4a18`
- read in full: `push/PushReminderDelivery.kt` (final), `WorkManagerPushSyncTrigger` in `push/NewMailNotifier.kt`; checked the OkHttp interceptors in `core/network/` (none converts an HTTP status into an exception)
- test results in `android/app/build/test-results/testDebugUnitTest` (run 2026-10-26T23:07Z): LibraryUnifiedPushConnectorTest 7, PushAndroidAdaptersTest 12, PushInAppTest 17, PushMessageHandlerTest 12, PushRegistrarTest 28, PushRegistrationKeysContractTest 1, PushReminderDeliveryTest 22, PushServerContractTest 2, ReminderShowFiredByServerTest 7 = 108 tests, 0 failures, 0 errors; matches the parent's reported run

Checks of the fix (no finding):
- Status handling: `response.code() >= 500` is checked before the body and the `isSuccessful` check, so every 5xx gives `OFFLINE`; 4xx, 2xx without a body, a mismatched id, an unfired reminder or an unavailable item stay `IGNORED`. The worker maps only `OFFLINE` to `Result.retry()`.
- Interaction with the unreadable-body handling: `getReminder` returns `Response<ReminderResponse>`, so Retrofit converts the body only for 2xx answers; a 5xx with an HTML or empty body (proxy 502) does not throw and reaches the new check. The catch-all `Exception` branch therefore still covers only an unreadable 2xx body, and the test with `{"id":` on a 200 still gives `IGNORED`. No interceptor in `core/network/` turns a 5xx into an exception.
- Retry loop and backoff: retries use WorkManager's default exponential backoff (30 s, capped at 5 h) under the existing network constraint. The loop ends as soon as the delivery is not `OFFLINE`: on success, when the reminder was already notified (local alarm or sync), after a logout (checked before any request) or on a 4xx. The worker never reschedules itself, so there is no tight loop.
- Unique-work policy: the per-reminder work `push-reminder-{id}` uses KEEP, so a repeated push of the same reminder while a retry is backing off does not add a second request or reset the backoff, and pushes of different reminders do not affect each other; the reminder sync (`push-reminders-sync`, REPLACE) is enqueued independently and is not blocked by a retrying delivery.
- Tests: the former "error answer" test now uses 403 (intended behavior change, not a weakened assertion); new tests cover 503, 500 and an empty 502 giving `OFFLINE`, the worker returning `Result.retry()`, nothing shown or marked notified, and a retried delivery after a 503 showing the reminder once.
- Comments and KDoc of `PushReminderResult` and the worker were updated to the new behavior.
- No regressions: the fix touches only `PushReminderDelivery.kt` and its test; the rest of the diff is unchanged since the previous review at 53cdfe6d4, whose checks (registration, reminder and new-mail push handling, logout race under `@Synchronized showFiredByServer`, server contract tests, unchanged periodic sync fallback) still hold.

Previous findings:
- CR-2 (5xx not retried): fixed in 419fb4a18, verified above.
- CR-1 (second copy of the ReminderResponse to ScheduledReminder mapping, `PushReminderDelivery.kt:66` vs `reminders/ReminderStore.kt`): declined by the parent as an optional P3 refactor (fixes.md round 2). The duplication is still present; it is a low-priority maintainability point and does not block completion, so it is not repeated as a finding here.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. The 5xx fix is correct, consistent with the unreadable-body handling and the per-reminder KEEP work, and covered by tests; the 108 focused tests pass.

Notes for the parent worker (not findings):
- Retries have no attempt cap, as before for connection failures: a server that keeps answering 5xx while the user stays logged in causes one request per backoff period (at most every 5 h) until it recovers or the reminder is notified another way. This is bounded in cost and matches the intent of the fix.
- Criterion 1's on-device/ntfy part cannot run here (recorded assumption): record the combined server and Android tests as evidence and mark the on-device part as not run, rather than claiming it.
