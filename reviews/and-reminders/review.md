# Code Review

Date: 2026-10-21

Task: and-reminders - Android reminders

Scope: Committed changes of the task, `git diff 44de27f6..HEAD -- android` (6 commits "and-reminders: ..."); no uncommitted changes.

Requirements:
- tasks/and-reminders.md (acceptance criteria and recorded assumptions in Notes)
- SPEC.md §2 (Android architecture: local AlarmManager reminders), §4.6 / reminder model, §9.2 (Android notifications)
- docs/ARCHITECTURE.md, AGENTS.md project notes (lint, no content logging, touches, conventions)
- Server contract checked against `server/.../core/reminders` (Reminder.kt, ReminderService.kt, ReminderRepository.kt)

Git range:
- Branch: main
- HEAD: dfb97f1032abe60d13a479cf56eaad3651a6c56b
- Base/start: 44de27f6a4147ff3ccabc0ef3acc6c2cd968b92f
- Commits reviewed: 6

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Reminder open request is re-applied when the activity is relaunched from Recents**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/MainActivity.kt:19`
    - Category: Correctness
    - Problem: `onCreate` offers `intent` to `ReminderOpenRequests` whenever `savedInstanceState == null`. If the app was started by tapping a notification (`FLAG_ACTIVITY_NEW_TASK`, no task yet), that reminder intent becomes the task's base intent. On Android 10/11 (minSdk is 29) a back press finishes the root activity. Reopening the app from Recents then calls `onCreate` with no saved state and the same intent, flagged `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`. The shell then navigates to the reminded item's screen again instead of the start screen.
    - Why it matters: Old notification taps are replayed. The effect is small because the request only selects a list screen, but it becomes more visible once detail screens use `ReminderOpenRequests`.
    - Suggested fix: Ignore the intent when `intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0`, and add a test for it in `ReminderActivityIntentTest`.

- [ ] **CR-2 | Changes outside the task's declared `touches`**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt:51`, `android/app/src/main/java/ch/sonensei/cockpit/auth/CockpitRoot.kt:25`, `android/app/src/main/java/ch/sonensei/cockpit/MainActivity.kt:18`, `android/app/src/main/res/values/strings_reminders.xml`, `android/app/src/main/res/drawable/ic_notification_reminder.xml`, `android/app/src/test/java/ch/sonensei/cockpit/auth/AppStartRoutingTest.kt:66`, `android/app/src/test/java/ch/sonensei/cockpit/auth/InAppLoginTest.kt:63`
    - Category: Architecture
    - Problem: The task declares `touches: [.../reminders, test/.../reminders, AndroidManifest.xml]`. The diff also changes the shell (`CockpitShell` gains a `prompts` slot, a `Column` around the `NavHost` and a `LaunchedEffect` that collects reminder open requests), `CockpitRoot`, `MainActivity`, resources and two auth tests. The edits are small and needed to wire the feature in. AGENTS.md says to stay inside the task's `touches` so that the board can keep parallel tasks apart.
    - Why it matters: Other tasks that change the shell or `MainActivity` at the same time get no "same files" warning, so merge conflicts become more likely.
    - Suggested fix: No code change is needed. The parent worker should record the extra touched files, for example with a `tasks.py assume` note, so the board and humans know about them.

## Review conclusion

No material issues found.

What was checked and found correct:
- **Criterion 1:** `ReminderScheduler.apply` sets alarms for new reminders and cancels reminders the server no longer lists (one `PendingIntent` per reminder, kept apart by the data URI). Alarms are exact with `setExactAndAllowWhileIdle` when `canScheduleExactAlarms()` allows it (or below API 31), and fall back to the inexact `setAndAllowWhileIdle`, including when a `SecurityException` occurs. `ReminderRescheduleReceiver` sets the alarms again on `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` and `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`. A sync runs every 15 minutes and once when the shell opens.
- **Criterion 2:**
  - Tapping the notification sends an explicit `FLAG_IMMUTABLE` activity intent to `MainActivity`, which opens the item's list screen (the recorded assumption).
  - POST_NOTIFICATIONS is requested only from the prompt card. The card appears only once there are scheduled reminders, and after one request it falls back to the app notification settings.
  - Exact alarms use `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`.
- **Security and privacy:**
  - `ReminderAlarmReceiver` is not exported and is reached only through an explicit, immutable broadcast `PendingIntent`.
  - The reschedule receiver is not exported. That is correct, because system broadcasts still reach it.
  - Nothing is logged: sync errors are swallowed on purpose.
  - The notification uses `VISIBILITY_PRIVATE`.
  - The store is private SharedPreferences and is cleared on logout through `SessionCache`, which also cancels alarms, dismisses notifications and stops the sync.
- **Concurrency:**
  - `apply`, `fire`, `rescheduleAll` and `clear` are serialized on the scheduler.
  - Logout calls `session.logout` before clearing the caches, and `apply` checks `isLoggedIn` under the same lock, so a sync answer that arrives after a logout cannot set alarms again.
  - An unreadable stored JSON falls back to an empty list.
- **Server contract:** Server reminders are immutable apart from `firedAt` (`Reminder.kt`), and `upcoming` lists only unfired reminders. So keeping notified ids while the server still lists them cannot hide a changed reminder.
- **Criterion 3 test evidence:** The tests use `ShadowAlarmManager` and cover scheduling and cancelling on sync changes, the exact/inexact fallback, boot rescheduling, the receivers, logout, the prompt UI and the activity intents (90 tests in 9 files). The reviewer did not run the suite (Bash was limited to read-only use). The parent worker must record its own run with `tasks.py verify`.
