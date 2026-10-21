# Code Review

Date: 2026-10-21

Task: and-reminders - Android reminders

Scope: `git diff c167a9fa..HEAD` (merge-base with origin/main to HEAD 817a624e), committed changes only. This review covers the final revision: the branch rebased onto newer main, the CR-1/CR-2 fixes from the earlier review, and the test-only commit 817a624e.

Requirements:
- tasks/and-reminders.md (acceptance criteria 1-3, Notes and recorded assumptions)
- SPEC.md §9.2
- docs/ARCHITECTURE.md, AGENTS.md project notes and conventions
- reviews/and-reminders/fixes.md (fixes for the earlier CR-1 and CR-2)

Git range:
- Branch: main
- HEAD: 817a624e9b4197646d0bb0df546bb62ab5f95448
- Base/start: c167a9fa9ecc107793e5865b7e4953e747e0b10c
- Commits reviewed: 13 (17b46da1 through 817a624e). The only uncommitted change is reviews/and-reminders/tests.md, which this review does not cover.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Verified during review:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.reminders.*'` passed on HEAD 817a624e: 98 tests in 9 classes, 0 failures, 0 errors, 0 skipped. The JUnit XML timestamps (2026-10-21T09:53Z) confirm the run was fresh.
- Criterion 1: `ReminderSync` gets `GET /api/v1/reminders/upcoming` for a 7-day window, and `ReminderScheduler.apply` sets, moves and cancels alarms. `AlarmManagerReminderAlarms` uses `setExactAndAllowWhileIdle` when `canScheduleExactAlarms()` is true. Otherwise it uses inexact `setAndAllowWhileIdle`, and it also falls back to that on a `SecurityException`. The prompt opens `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`. Alarms are set again on `BOOT_COMPLETED`, on `MY_PACKAGE_REPLACED`, when exact-alarm permission is granted, and on resume when exact-alarm permission changed. They are also set again on every sync, which runs every 15 minutes through WorkManager and once when the shell opens. Tests use ShadowAlarmManager to check exact and inexact alarms, cancelling, moving, the 100-alarm cap, and the logout race.
- Criterion 2: the notification's content intent opens `MainActivity` with the `ACTION_OPEN_ITEM` action and the item type and id. `ReminderOpenRequests` passes this to `CockpitShell`, which opens the screen that lists that item type, as recorded in an assumption. Intents replayed from Recents and recreated activities are ignored. The app asks for POST_NOTIFICATIONS only from the in-context card, which appears only when there are upcoming reminders. After one request, the card sends the user to the app notification settings instead. Robolectric and Compose tests cover these flows.
- Criterion 3: the reminders test suite passes (see above). It covers scheduling and cancelling on sync changes with ShadowAlarmManager, plus Compose UI tests for the prompt and for opening an item in the shell.
- Earlier CR-1 (restart the sync after a new login) and CR-2 (dismiss every reminder notification on logout) are still fixed in the rebased code: `ReminderPromptViewModel.onShellShown()` and `ReminderNotifier.dismissAll()` are called from `ReminderScheduler.clear()`. The new tests in 817a624e check behavior: `dismissAll` keeps other features' notifications, including one that uses the same notification id. Showing the shell again without a logout keeps the dismissed and requested state. These tests change no production code.
- Security and privacy: both receivers have `exported=false`, and all PendingIntents are `FLAG_IMMUTABLE`. Notifications use `VISIBILITY_PRIVATE`. Response bodies are never logged. The store keeps only the reminder id, item type, item id, title and time in private SharedPreferences, and logout clears it.
- Changes to files outside the declared `touches` (MainActivity, CockpitRoot, CockpitShell, resources, two auth tests) are small and needed to wire the feature in. The task has a recorded assumption for them.
- Criterion evidence: `python tasks.py verify and-reminders` still shows criteria 1-3 as unverified. The parent worker must record the evidence before running `done`. This is not a code finding.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
