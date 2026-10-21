# Fix Report

Date: 2026-10-21

Task: and-reminders - Android reminders

Scope: current task; findings CR-1 (P1) and CR-2 (P2) of `reviews/and-reminders/review.md` (reviewed HEAD a32d9704)

## Fixed findings

### CR-1 — The reminder sync is not started again after a logout and a new login in the same activity

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/reminders/ReminderPermissionPrompt.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/reminders/ReminderUiTest.kt`

Problem:
`ReminderPromptViewModel.init` was the only caller of `syncScheduler.start()`. The ViewModel is scoped to `MainActivity`, so after a logout (which cancels the sync work and clears the store) and a new login, `init` did not run again: no periodic or immediate reminder sync was enqueued. The `dismissed`/`notificationsRequested` flows also kept their values from before the logout.

Fix:
Replaced the `init` block with `ReminderPromptViewModel.onShellShown()`, which re-reads `dismissed` and `notificationsRequested` from the store and calls `syncScheduler.start()`. `ReminderPermissionPrompt` calls it from `LaunchedEffect(viewModel)`. The prompt lives in `CockpitShell`'s prompts slot, which `CockpitRoot` composes only while the user is logged in, so the effect runs again on every shell start, including the start after a new login. This works like the GTD sync's `ensureScheduled()`. `start()` stays idempotent: the periodic work uses KEEP behind an AtomicBoolean that `cancel()` resets, and the immediate work uses KEEP.

Regression test: `ReminderUiTest` "a logout and a new login with the same ViewModel start the sync again and forget the prompt state". It reuses one ViewModel, removes the prompt from the composition and brings it back (the same way as `CockpitRoot`'s `if (loggedIn)`), and clears the scheduler in between (logout). It asserts that the sync is started a second time, that the prompt is shown again for the new session's reminders, and that `notificationsViaSettings` is reset. I checked that this test fails when the effect runs only once per process.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.reminders.*'` — Pass

### CR-2 — Logout leaves shown reminder notifications that a later sync already removed from the store

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/reminders/ReminderNotifier.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/reminders/ReminderScheduler.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/reminders/ReminderLogoutTest.kt`

Problem:
`ReminderScheduler.clear()` only dismissed the notifications of reminders that were still in `store.scheduled`. A shown reminder that a later sync had dropped from the store stayed in the notification shade after logout, with its item title.

Fix:
Added `ReminderNotifier.dismissAll()`. It cancels every active notification with `id == NOTIFICATION_ID` on the `reminders` channel (`NotificationManager.activeNotifications`, by tag). `clear()` calls it after it dismisses the stored reminders and before it clears the store. The `SessionCache` that runs on every logout, from the user or from the network layer, already calls `clear()`.

Regression test: `ReminderLogoutTest` "logging out also removes a shown notification whose reminder a later sync dropped". The reminder is shown, a sync no longer lists it (so it leaves the store), and a logout follows. The test asserts that no alarm, notification, stored reminder or sync work remains. I checked that this test fails without the `dismissAll()` call.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.reminders.*'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.reminders.*'` -> Pass (95 tests in 9 classes, 0 failures, 0 errors)
- Mutation check: I reverted each fix on its own, temporarily -> the matching new test failed; then I restored the fixes

Full test suite:
- `cd android && ./gradlew testDebugUnitTest lintDebug` -> Pass (572 tests, 0 failures, 0 errors; lint clean)

Lint/type-check/build:
- ktlintCheck and lintDebug are part of the commands above -> Pass

`.taskcheck`:
- `sh scripts/check.sh` -> Pass ("all checks passed")

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
