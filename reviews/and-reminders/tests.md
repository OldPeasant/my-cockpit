# Test Report

Date: 2026-10-21

Task: and-reminders - Android reminders

Scope: `git diff c167a9fa9ecc107793e5865b7e4953e747e0b10c..817a624e9b4197646d0bb0df546bb62ab5f95448` on branch `main`
(base = `git merge-base HEAD origin/main`, supplied by the parent worker; the task-owned commits are the 13
`and-reminders: ...` commits 17b46da1 through 817a624e). The working tree was clean, so no uncommitted inputs are
included. This is the final pass after the fixes for CR-1 and CR-2 (fb77b8b2) and the tests added for them
(fb77b8b2, 817a624e). It replaces the earlier report, which covered 67037b99.

Requirements:
- `tasks/and-reminders.md` acceptance criteria and recorded assumptions (available=false gets no alarm;
  a notification opens the list screen for the item's type; the reminders have their own WorkManager sync with a
  7-day window, at most 100 alarms and SharedPreferences storage; SCHEDULE_EXACT_ALARM with an inexact fallback; an
  in-context prompt)
- `SPEC.md` 9.2 (reminders through AlarmManager with the exact alarm permission)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`
- `reviews/and-reminders/review.md` and `reviews/and-reminders/fixes.md` (CR-1 and CR-2 fixes and their regression tests)

## Test files changed

None in this pass. I checked the final code against the criteria and found no real gap, so I added no test.
The task's test files (all committed) are:

- `android/app/src/test/java/ch/sonensei/cockpit/reminders/` (9 test classes plus `ReminderTestSupport.kt`)
- `android/app/src/test/java/ch/sonensei/cockpit/auth/AppStartRoutingTest.kt`, `InAppLoginTest.kt` (test WorkManager init only)

## Tests added or updated

Count: 0 (this pass)

The tests for the fixes are already committed, and I reviewed them:
- CR-1 (the sync restarts on every login): `ReminderUiTest` has two tests. `a logout and a new login with the same
  ViewModel start the sync again and forget the prompt state` checks the restart after a logout. `the shell shown
  again without a logout keeps the prompt dismissed and the dialog already asked` checks that the prompt state
  survives when the shell comes back without a logout. Together they cover both outcomes of `onShellShown()`
  re-reading the store.
- CR-2 (`dismissAll` on logout): `ReminderLogoutTest` has `logging out also removes a shown notification whose reminder
  a later sync dropped`. `ReminderAlarmsNotifierTest` has `dismissing all removes every reminder notification but keeps
  the notifications of other features`, which includes a foreign notification with the same id and one without a tag,
  and `dismissing all with no notification shown does nothing`. Both conditions of the filter (id and channel) are
  covered (ReminderNotifier branches 16/16).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: synced upcoming reminders are scheduled with AlarmManager | ReminderSchedulerTest (`a sync sets an exact alarm at the reminder time ...`, `only the earliest reminders up to the limit ...`, `fired and unavailable reminders get no alarm`), ReminderSyncTest (`sync asks for the reminders of the next week and sets their alarms`) | `cd android && ./gradlew ktlintCheck testDebugUnitTest --rerun --tests 'ch.sonensei.cockpit.reminders.*'` | Pass |
| AC1: exact alarm permission flow, fallback to inexact | ReminderSchedulerTest (`without the exact alarm permission the alarm is inexact`), ReminderAlarmsNotifierTest (`an exact alarm permission revoked between the check and the call falls back ...`, `before Android 12 alarms are always exact ...`, `canScheduleExact follows ...`), ReminderUiTest (`allow exact alarms opens the system page for this app`, `granted permissions hide the prompt, and newly granted exact alarms make the alarms exact`, `coming back from the exact alarm settings page ...`), ReminderReceiversTest (`granting exact alarms sets the alarms again as exact`) | same | Pass |
| AC1: rescheduled after boot | ReminderReceiversTest (`after a reboot the stored reminders get their alarms again`, `after an app update ...`, `the manifest registers the receivers for boot, update and the exact alarm permission`), ReminderStoreTest (`stored reminders are read back by a new store, as after a reboot`), ReminderSchedulerTest (`rescheduling skips reminders already notified`) | same | Pass |
| AC1: rescheduled after sync (scheduling and cancelling on sync changes, ShadowAlarmManager) | ReminderSchedulerTest (`a later sync cancels deleted reminders, moves changed ones and adds new ones`, `an empty sync cancels every alarm`, `a new earlier reminder takes the place of the latest one at the limit`, ...), ReminderSyncTest (deleted, unavailable, error, offline and logout cases), ReminderSyncSchedulerTest (15-minute periodic sync plus an immediate sync), ReminderUiTest (sync started again after logout and login, CR-1) | same | Pass |
| AC2: notification opens the item | ReminderReceiversTest (`an alarm shows the reminder, and a tap opens the item in the app`), ReminderAlarmsNotifierTest (`two reminders show two notifications, each opening its own item`), ReminderActivityIntentTest (5 tests, including the replay from Recents), ReminderUiTest (`a tapped notification opens the item's screen in the shell`, `a request made before the shell shows is opened when it appears`, `two tapped notifications ...`, `every item type opens the screen that lists it`) | same | Pass |
| AC2: POST_NOTIFICATIONS requested in context | ReminderUiTest (`no prompt while there are no reminders ...`, `with upcoming reminders the prompt asks ...`, `allow notifications asks for the runtime permission`, `after the user denied the permission dialog ...`, `granting the permission dialog stops asking for notifications ...`, `before Android 13 ...`, `not now hides the prompt for good`, `the shell shown again without a logout keeps the prompt dismissed ...`, `the shell shows the prompt above the current screen`) | same | Pass |
| AC3: the reminders test command passes | all 98 tests in `ch.sonensei.cockpit.reminders.*` | same | Pass (9 classes, 98 tests, 0 failures, 0 errors, 0 skipped; ktlintCheck passed) |
| Logout removes alarms and notifications (SPEC privacy, review CR-2) | ReminderLogoutTest (3 tests), ReminderAlarmsNotifierTest (2 `dismissing all ...` tests), ReminderSchedulerTest (`clearing removes every alarm ...`, `an answer that arrives after a logout sets no alarm`) | same | Pass |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --rerun --tests 'ch.sonensei.cockpit.reminders.*'`

Result:

Pass: BUILD SUCCESSFUL, run 2026-10-21T11:45:20+02:00 to 11:45:57+02:00. JUnit XML lists ActivityIntent 5,
AlarmsNotifier 13, Logout 3, Receivers 12, Scheduler 19, Store 7, SyncScheduler 4, Sync 13 and Ui 22 tests: 98 in total,
0 failures, 0 errors, 0 skipped.

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest --rerun createDebugUnitTestCoverageReport`

Result:

Pass: BUILD SUCCESSFUL. The whole Android unit suite ran 781 tests with 0 failures, 0 errors and 0 skipped. The count
is higher than the 570 of the earlier report because main has gained other tasks' tests since then.

## .taskcheck

Result:

Pass: `sh scripts/check.sh` (TFP gate tests, plus lint and unit tests for server, web and android) ended with
"all checks passed", exit 0, from 2026-10-21T11:48:23+02:00 to 11:51:15+02:00 (172 s). That is above the 2-minute
warning but well inside the 15-minute limit. It ran on HEAD 817a624e with a clean working tree.

## Bugs found

None. No test failed, and I found no production behavior that contradicts the acceptance criteria or the recorded
assumptions. The CR-1 and CR-2 fixes behave as described in `fixes.md`.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. The JaCoCo report has no changed-line view. The 9 reminders files are all new in
this task, so for them whole-file coverage equals changed-line coverage. MainActivity, CockpitShell and CockpitRoot are
whole-file numbers.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-reminders; base
  c167a9fa9ecc107793e5865b7e4953e747e0b10c; HEAD 817a624e9b4197646d0bb0df546bb62ab5f95448. The parent worker supplied
  the scope, and I checked it against `git log --oneline` and `git diff --stat` of base..HEAD: only `and-reminders:`
  commits.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 817a624e. `git status --porcelain` was empty
  before and after the coverage run and after `.taskcheck`.
- Exact included files: `git diff --name-only c167a9fa..HEAD -- android/app/src/main` gives all 9 files in
  `android/app/src/main/java/ch/sonensei/cockpit/reminders/` plus `MainActivity.kt`, `ui/CockpitShell.kt` and
  `auth/CockpitRoot.kt`. Excluded: `AndroidManifest.xml`, `res/drawable/ic_notification_reminder.xml` and
  `res/values/strings_reminders.xml`, which are resources with no executable code. ReminderReceiversTest asserts the
  manifest registrations. No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo through AGP `enableUnitTestCoverage`
  (ARCHITECTURE.md "Coverage"); `./gradlew testDebugUnitTest --rerun createDebugUnitTestCoverageReport` in `android/`;
  the whole Android unit suite (781 tests), so the hits include tests from other packages.
- Run start/completion time and exit status: 2026-10-21T11:46:11+02:00 to 11:47:48+02:00, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`,
  sha256 b5456ba516a0223931c9bbc99d8e0690c624041a6acbeebea68bed10cb570b16, JaCoCo session id dell26-437ba17f.
- Evidence outputs are fresh and inputs stayed unchanged: yes. I deleted the old `report.xml` before the run,
  `--rerun` forced the test task, the run wrote a new report, and the tree stayed clean.
- Final run after latest test additions and production fixes: yes. The measured code is HEAD 817a624e, which includes
  the CR-1 and CR-2 fixes and their tests. This pass added no tests.

Values below are covered/total (percentage). Zero-denominator or unsupported metrics are N/A. Statements = the JaCoCo
INSTRUCTION counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `reminders/ReminderAlarms.kt` | 124/126 (98.4%) | 17/18 (94.4%) | 7/7 (100%) | 23/23 (100%) |
| `reminders/ReminderNotifier.kt` | 218/219 (99.5%) | 16/16 (100%) | 9/9 (100%) | 50/51 (98.0%) |
| `reminders/ReminderOpenRequests.kt` | 90/96 (93.8%) | 19/19 (100%) | 6/8 (75.0%) | 24/24 (100%) |
| `reminders/ReminderPermissionPrompt.kt` | 989/996 (99.3%) | 36/40 (90.0%) | 31/31 (100%) | 108/108 (100%) |
| `reminders/ReminderReceivers.kt` | 79/79 (100%) | 8/8 (100%) | 7/7 (100%) | 16/16 (100%) |
| `reminders/ReminderScheduler.kt` | 262/264 (99.2%) | 16/16 (100%) | 10/11 (90.9%) | 45/45 (100%) |
| `reminders/ReminderStore.kt` | 244/246 (99.2%) | 9/10 (90.0%) | 21/21 (100%) | 45/45 (100%) |
| `reminders/ReminderSync.kt` | 223/223 (100%) | 10/10 (100%) | 11/11 (100%) | 52/52 (100%) |
| `reminders/RemindersModule.kt` | 23/26 (88.5%) | N/A (0/0) | 3/4 (75.0%) | 5/6 (83.3%) |
| `MainActivity.kt` | 73/73 (100%) | 6/6 (100%) | 6/6 (100%) | 11/11 (100%) |
| `ui/CockpitShell.kt` | 1013/1027 (98.6%) | 46/48 (95.8%) | 27/27 (100%) | 85/86 (98.8%) |
| `auth/CockpitRoot.kt` | 229/242 (94.6%) | 9/14 (64.3%) | 10/10 (100%) | 14/14 (100%) |
| **All 12 files (summed counters)** | 3567/3617 (98.6%) | 192/205 (93.7%) | 148/152 (97.4%) | 478/481 (99.4%) |
| reminders package + MainActivity only (10 files, summed) | 2325/2348 (99.0%) | 137/143 (95.8%) | 111/115 (96.5%) | 379/381 (99.5%) |

The report contains all 12 scoped files; none is missing or unmeasured. The 3 resource files are excluded, as listed
above. The CockpitShell and CockpitRoot numbers are whole-file and include code older than this task.

The fix code is fully covered: `ReminderNotifier.dismissAll()` (all lines, all filter branches),
`ReminderPromptViewModel.onShellShown()` with its `LaunchedEffect`, and the `notifier.dismissAll()` call in
`ReminderScheduler.clear()`.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ReminderAlarms.kt:39` (`?: return` when `PendingIntent.getBroadcast` returns null) | none: with FLAG_UPDATE_CURRENT the call always creates the intent | Defensive and not reachable; no test |
| `ReminderNotifier.kt:53` (catch SecurityException on notify) | the permission is revoked between the check and the call | Robolectric's NotificationManager never throws, so this race cannot be reproduced without mocking the platform. Low risk: the notification is silently dropped, which is the intended behavior |
| `ReminderOpenRequests.kt:11-12` | generated data class members (`copy`/`componentN`) | Not behavior; no test |
| `ReminderPermissionPrompt.kt:133,136,177-178` | Compose default-parameter branches (`Modifier`, `hiltViewModel()`) | Tests pass a view model explicitly; the real Hilt view model runs in the app. Not behavior |
| `ReminderPermissionPrompt.kt:160` (exact-alarm button below Android 12) | none: the exact alarm button is never shown before Android 12, because `canScheduleExact` is true there. `before Android 13 the prompt asks only for notifications ...` (SDK 30) asserts this | Unreachable branch; no test |
| `ReminderScheduler.kt:101` | synthetic constructor of the companion object | Not behavior |
| `ReminderStore.kt:69` (`orEmpty()` when `getStringSet` returns null) | none: the call passes a default value | Defensive; no test |
| `RemindersModule.kt:13` | constructor of the abstract Hilt `@Binds` module, which is never instantiated | Not behavior |
| `CockpitShell.kt:66-67` (false branch of `take(request)`) | a second collector takes the request first | Only one shell collects. `a tapped notification opens the item's screen in the shell` asserts that a request is taken only once. No test |
| `CockpitShell.kt:148,166`, `CockpitRoot.kt:22,35` | pre-existing `when` default and Compose default-parameter branches | Outside this task's changed behavior |

## Unable to test

- These need a device or emulator: real AlarmManager behavior (doze, actual exact vs inexact delivery, the platform's
  alarm limit), the real BOOT_COMPLETED broadcast, the system exact-alarm page and the real POST_NOTIFICATIONS dialog.
  The unit tests use the Robolectric shadows that the acceptance criterion names (ShadowAlarmManager,
  ShadowNotificationManager, permission result intents). No follow-up is required for this task; an instrumented
  smoke test would be the follow-up if one is wanted.
- The SecurityException race paths (alarm and notification) cannot be produced with Robolectric without mocking the
  platform. The alarm path is tested through the shadow's exact-permission switch (ReminderAlarmsNotifierTest).

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The final code (HEAD 817a624e, with the
CR-1 and CR-2 fixes) passes the reminders suite (98 tests), ktlintCheck, the whole Android unit suite (781 tests) and
`.taskcheck`.
