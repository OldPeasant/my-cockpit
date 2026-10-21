# Code Review

Date: 2026-10-21

Task: and-reminders - Android reminders

Scope: `git diff ab559a26..a32d9704` (merge-base with origin/main to HEAD), committed changes only

Requirements:
- tasks/and-reminders.md (acceptance criteria 1-3, Notes and recorded assumptions)
- SPEC.md §4.6, §9.2
- docs/ARCHITECTURE.md, AGENTS.md project notes and conventions
- Parent instructions (CR-2 of the earlier review, touches outside the declared list, is covered by a recorded assumption)

Git range:
- Branch: main
- HEAD: a32d970404b55b9d908f4d402da3f014e1869394
- Base/start: ab559a2607242da1a6fab61280c0fe213f2b703e
- Commits reviewed: 10 (749be4f6, 510237ca, 401bb277, 389ee96a, 35af2289, 35227183, 965b5208, b2bea52a merge, 67037b99, a32d9704); no uncommitted changes

Summary: 0 P0, 1 P1, 1 P2, 0 P3

Verified during review: `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.reminders.*'` -> 93 tests, 0 failures, 0 errors (9 classes). The earlier CR-1 (Recents replay) is fixed in `MainActivity.kt` with `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` and covered by `ReminderActivityIntentTest`. The Settings fallback after a denied request (35227183) is correct and tested.

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | The reminder sync is not started again after a logout and a new login in the same activity**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/reminders/ReminderPermissionPrompt.kt:89`
    - Category: Correctness
    - Problem: `ReminderPromptViewModel`'s `init` is the only caller of `syncScheduler.start()`. The prompt is composed in `CockpitShell`'s `prompts` slot, outside the NavHost (`auth/CockpitRoot.kt:26`), so `hiltViewModel()` scopes the ViewModel to `MainActivity`. A logout (by the user, or by the network layer when it rejects the refresh token) runs the reminders `SessionCache` (`RemindersModule.kt`). That cache cancels the periodic and one-time sync work and clears all alarms. The user then lands on `LoginRoute`, logs in again, and `CockpitShell` comes back with the same ViewModel instance. `init` does not run again, so neither the periodic sync nor the immediate sync is enqueued. Until the activity is destroyed (process death or backing out of the app), no reminder gets an alarm. The ViewModel's `dismissed` and `notificationsRequested` flows (lines 73-74) also keep the values from before the logout, although `ReminderStore.clear()` reset them. The GTD sync avoids this problem because `GtdSyncScheduler.ensureScheduled()` is called on every screen start. `ReminderSyncSchedulerTest` "after a cancel (logout) the next start (login) schedules the periodic sync again" only tests the scheduler class. No test shows that anything calls `start()` after a login.
    - Why it matters: This breaks criterion 1 ("Synced upcoming reminders are scheduled ... rescheduled after ... sync") in a realistic flow. An expired or rejected refresh token followed by a re-login leaves the user with no reminder notifications on this device and no visible error.
    - Suggested fix: Start the sync whenever a session begins, not when the ViewModel is created. For example, call `syncScheduler.start()` from a `LaunchedEffect`/`LifecycleResumeEffect` in `ReminderPermissionPrompt`, or observe the logged-in state (e.g. `PersistentTokenStore.session`) in an app-scoped component. Re-read `dismissed`/`notificationsRequested` from the store at the same time. Add a test that logs out and logs in again in one `ActivityScenario` and asserts that the reminder sync work is enqueued again.

## P2 Medium

- [ ] **CR-2 | Logout leaves shown reminder notifications that a later sync already removed from the store**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/reminders/ReminderScheduler.kt:82`
    - Category: Security
    - Problem: `clear()` only dismisses the notifications of reminders still in `store.scheduled`. In the normal flow, the local alarm fires and the notification is shown (the id is marked notified). The server's scheduler fires the reminder at about the same time, and `GET /reminders/upcoming` only returns pending reminders (`ReminderService.upcoming` -> `findPending`). The next sync (at most 15 min later) therefore drops the reminder from the store: it is due and not listed, but already notified, so it is not in `wanted` (lines 44-55). From then on, `clear()` does not know about it, and the notification stays in the shade after logout. That notification shows the item title as its content title (`ReminderNotifier.kt`; `VISIBILITY_PRIVATE` only hides it on the lock screen) and has a tap intent into the app. `ReminderLogoutTest` only covers a notification whose reminder is still stored, so the test misses this case.
    - Why it matters: The class documentation and the logout contract say that "no alarm or notification of the user stays behind". The user's data stays visible on the device after logout, for example on a shared or handed-over device.
    - Suggested fix: On logout, cancel all notifications this feature posted, not just the stored ones. For example, iterate `NotificationManager.activeNotifications` and cancel those with `id == NOTIFICATION_ID` (tag = reminder id), or those on `CHANNEL_ID`. Extend `ReminderLogoutTest` with a reminder that was shown and then dropped by a sync that no longer lists it.

## P3 Low

None.

## Review conclusion

Material issues found; fix before completing the task.
