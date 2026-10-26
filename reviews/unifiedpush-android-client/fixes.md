# Fix Report

Date: 2026-10-26

Task: unifiedpush-android-client (UnifiedPush Android client)

Scope: current task; only finding CR-1 of `reviews/unifiedpush-android-client/review.md`, as instructed by the parent worker.

## Fixed findings

### CR-1 — A late endpoint PUT can recreate the server registration after the logout's DELETE

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/push/PushRegistrar.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/push/PushRegistrarTest.kt`

Problem:
`onNewEndpoint` checked login state and instance when its coroutine started, then sent the PUT. Nothing ordered it
against `deleteOnServer()`'s DELETE. The server could therefore process the DELETE first and then a late PUT, which
recreated the registration after logout.

Fix:
`PushRegistrar` now has a `kotlinx.coroutines.sync.Mutex` (`requests`) that the endpoint PUT and the logout DELETE
both hold while they send. Inside the lock, `deleteOnServer()` records the logged-out instance in a `@Volatile`
field `deleted` and then sends the DELETE. Inside the same lock, `onNewEndpoint` checks again that the user is
logged in, that the instance is current and that it is not `deleted`. If any check fails, it drops the PUT. This has
three effects:
- a PUT that is already being sent finishes before the DELETE goes out;
- a PUT that is waiting behind the DELETE, or that arrives later in the same logout (before `clear()`), is not sent;
- after `clear()`, the next login gets a new instance, so its endpoint is sent normally.
`onUnregistered`, `register` and `clear` are unchanged.

New regression tests in `PushRegistrarTest`:
- `an endpoint arriving after the logout's delete is not sent`
- `an endpoint waiting while the logout's delete is sent is dropped` (DELETE is suspended in flight, so the PUT queues behind it and is dropped)
- `the logout's delete waits for an endpoint already being sent` (PUT is suspended in flight, so the DELETE waits; order is PUT then DELETE)
- `the next login sends its endpoint again after a logout`

All three new tests that cover ordering failed when run against the previous `PushRegistrar.kt` (27 tests, 3 failed),
and they pass with the fix.

Validation:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*' lintDebug` — Pass (BUILD SUCCESSFUL; PushRegistrarTest 27/27, PushMessageHandlerTest 12/12, LibraryUnifiedPushConnectorTest 7/7, PushInAppTest 13/13, PushAndroidAdaptersTest 11/11)

## Not fixed

### CR-2 — Every process start re-registers and may PUT the same endpoint again

Status:
Out of scope (intentional behavior)

Reason:
The parent worker said not to fix it. Re-registering at every start is intentional and recorded as a task
assumption: it is the client's only retry path after a failed PUT. The reviewer rated it a low-priority efficiency
issue, not a correctness issue.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*' lintDebug` — Pass (70 push tests, 0 failures; ktlint and Android lint clean)
- Same PushRegistrarTest suite against the previous production file — 3 of 27 failed, as expected; this shows the new tests catch the bug

Full test suite:
- `sh scripts/check.sh` — Fail, in an unrelated server test only: `ch.sonensei.cockpit.backup.BackupEventsStreamTests` (`duplicate key value violates unique constraint "app_user_single_row"`). This task changes no files under `server/`. Run alone, the class passes: `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.backup.BackupEventsStreamTests'` gives 1/1 pass. So the failure depends on test order or shared database state, and this fix does not cause it.

Lint/type-check/build:
- ktlintCheck and lintDebug (Android) — Pass

`.taskcheck`:
- Fail (unrelated, order-dependent server test described above; Android and push parts pass)

Remaining unresolved findings:
1 (CR-2, intentionally not fixed)

## Final status

All validated findings fixed and validation passed. The only failure is an unrelated, order-dependent server test in `.taskcheck` that passes when run alone.
