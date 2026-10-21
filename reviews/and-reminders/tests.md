# Test Report

Date: 2026-10-21

Task: and-reminders - Android reminders

Scope: `git diff ab559a2607242da1a6fab61280c0fe213f2b703e..67037b990d6b52b043df5f630b1853f2c89df4f8` on branch `main`
(base = `git merge-base HEAD origin/main`, supplied by the parent worker; the task-owned commits are the
`and-reminders: ...` commits 749be4f6, 510237ca, 401bb277, 389ee96a, 35af2289, 35227183, 965b5208, 67037b99 and the
merge b2bea52a of `origin/wip/and-reminders`). Plus the uncommitted test change listed below.

Requirements:
- `tasks/and-reminders.md` acceptance criteria and its recorded assumptions (available=false gets no alarm;
  notification opens the item type's list screen; own WorkManager sync, 7-day window, max 100 alarms, SharedPreferences;
  SCHEDULE_EXACT_ALARM with inexact fallback, in-context prompt)
- `SPEC.md` 4.6 and 9.2 (reminders via AlarmManager with the exact alarms permission)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/reminders/ReminderUiTest.kt` (2 tests added)

The 91 existing tests in `android/app/src/test/java/ch/sonensei/cockpit/reminders/` were reviewed against the criteria.
They cover scheduling, exact/inexact fallback, cancelling and moving alarms on sync changes (ShadowAlarmManager), the
alarm limit, boot/update/exact-permission rescheduling, the notification and its open-item intent, MainActivity intent
handling (cold start, onNewIntent, recreate, Recents replay), the shell navigation, the prompt and logout. Two gaps
in the in-context permission flow had no test and were added.

## Tests added or updated

Count: 2

- `granting the permission dialog stops asking for notifications, exact alarms are still asked for`: the POST_NOTIFICATIONS
  dialog result (granted) goes through the real launcher callback; the prompt stops asking for notifications and keeps
  asking for exact alarms. Before, only the denied result was tested and the granted case called `refreshPermissions()`
  directly.
- `coming back from the exact alarm settings page hides the prompt and makes the alarms exact`: the activity goes
  STARTED -> RESUMED after the user granted exact alarms on the system page; the `LifecycleResumeEffect` re-reads the
  permission, the prompt disappears and the existing inexact alarm is set again as exact (window 0).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: synced upcoming reminders are scheduled with AlarmManager | ReminderSchedulerTest (`a sync sets an exact alarm at the reminder time ...`, `only the earliest reminders up to the limit ...`, `fired and unavailable reminders get no alarm`), ReminderSyncTest (`sync asks for the reminders of the next week and sets their alarms`) | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.reminders.*'` | Pass |
| AC1: exact alarm permission flow, fallback to inexact | ReminderSchedulerTest (`without the exact alarm permission the alarm is inexact`), ReminderAlarmsNotifierTest (`an exact alarm permission revoked between the check and the call falls back ...`, `before Android 12 alarms are always exact ...`, `canScheduleExact follows ...`), ReminderUiTest (`allow exact alarms opens the system page for this app`, `granted permissions hide the prompt, and newly granted exact alarms make the alarms exact`, new `coming back from the exact alarm settings page ...`), ReminderReceiversTest (`granting exact alarms sets the alarms again as exact`) | same | Pass |
| AC1: rescheduled after boot | ReminderReceiversTest (`after a reboot the stored reminders get their alarms again`, `after an app update ...`, `the manifest registers the receivers for boot, update and the exact alarm permission`), ReminderStoreTest (`stored reminders are read back by a new store, as after a reboot`), ReminderSchedulerTest (`rescheduling skips reminders already notified`) | same | Pass |
| AC1: rescheduled after sync (scheduling/cancelling on sync changes, ShadowAlarmManager) | ReminderSchedulerTest (`a later sync cancels deleted reminders, moves changed ones and adds new ones`, `... item was trashed meanwhile`, `... lists as fired ...`, `an empty sync cancels every alarm`, `a due reminder the server fired before the local alarm went off keeps its alarm`, `a new earlier reminder takes the place of the latest one at the limit`), ReminderSyncTest (deleted/unavailable/error/offline/logout cases), ReminderSyncSchedulerTest (15-min periodic + immediate sync) | same | Pass |
| AC2: notification opens the item | ReminderReceiversTest (`an alarm shows the reminder, and a tap opens the item in the app`), ReminderAlarmsNotifierTest (`two reminders show two notifications, each opening its own item`), ReminderActivityIntentTest (5 tests incl. Recents replay, CR-1), ReminderUiTest (`a tapped notification opens the item's screen in the shell`, `a request made before the shell shows is opened when it appears`, `two tapped notifications ...`, `every item type opens the screen that lists it`) | same | Pass |
| AC2: POST_NOTIFICATIONS requested in context | ReminderUiTest (`no prompt while there are no reminders ...`, `with upcoming reminders the prompt asks ...`, `allow notifications asks for the runtime permission`, `once asked ...`, `after the user denied the permission dialog ...`, new `granting the permission dialog stops asking for notifications ...`, `before Android 13 ...`, `not now hides the prompt for good`, `the shell shows the prompt above the current screen`) | same | Pass |
| AC3: the reminders test command passes | all 93 tests in `ch.sonensei.cockpit.reminders.*` | same | Pass (93 tests, 0 failures, 0 skipped; ktlintCheck passed) |
| Logout removes alarms and notifications (SPEC privacy, AGENTS) | ReminderLogoutTest (2), ReminderSchedulerTest (`clearing removes every alarm ...`, `an answer that arrives after a logout sets no alarm`) | same | Pass |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.reminders.*'`

Result:

Pass: BUILD SUCCESSFUL, 9 test classes, 93 tests, 0 failures, 0 errors, 0 skipped (91 existing + 2 new).

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest --rerun createDebugUnitTestCoverageReport`

Result:

Pass: BUILD SUCCESSFUL, whole Android unit suite 570 tests, 0 failures, 0 errors, 0 skipped.

## .taskcheck

Result:

Pass: `sh scripts/check.sh` (server, web, android lint + unit tests) ended with "all checks passed", exit 0,
2026-10-21T11:22:30+02:00 to 11:23:51+02:00 (81 s). Run with the new tests in the working tree.

## Bugs found

None. No test failed, and no production behavior contradicting the acceptance criteria or recorded assumptions was found.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (the JaCoCo report has no changed-line view; the reminders files are all new in
this task, so for them whole-file equals changed-file coverage).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-reminders; base
  ab559a2607242da1a6fab61280c0fe213f2b703e; HEAD 67037b990d6b52b043df5f630b1853f2c89df4f8; scope from the parent
  worker, checked against `git log`/`git diff --stat` of base..HEAD (only `and-reminders:` commits and the merge of
  its own wip branch).
- Source/test snapshot identifier (including uncommitted inputs): HEAD 67037b99 plus uncommitted
  `android/app/src/test/java/ch/sonensei/cockpit/reminders/ReminderUiTest.kt` sha256
  4ea4d8fad1563be593135d0e27f7806f1a05de71e2886e7b76c09860c7cc6f8d (same hash before and after the run). No production
  file is modified in the working tree.
- Exact included files: all 9 files in `android/app/src/main/java/ch/sonensei/cockpit/reminders/`, plus
  `MainActivity.kt`, `ui/CockpitShell.kt`, `auth/CockpitRoot.kt` (modified for the wiring). Excluded:
  `AndroidManifest.xml`, `res/drawable/ic_notification_reminder.xml`, `res/values/strings_reminders.xml` (resources,
  no executable code; the manifest registrations are asserted by ReminderReceiversTest). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`
  (ARCHITECTURE.md "Coverage"); `./gradlew testDebugUnitTest --rerun createDebugUnitTestCoverageReport` in `android/`;
  whole Android unit suite (570 tests), so hits include other packages' tests.
- Run start/completion time and exit status: 2026-10-21T11:20:34+02:00 to 11:22:14+02:00, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`
  sha256 e215fe6d636ddc05c7fa2b1f7e9d644129d6e027c5dc411ca5e0ef5061af7870, JaCoCo session id dell26-3a4e34e5.
- Evidence outputs are fresh and inputs stayed unchanged: yes; `--rerun` forced the test task, the report file was
  rewritten by this run, and the test file hash was unchanged after it.
- Final run after latest test additions and production fixes: yes (after both new tests; no production change was made
  during this test pass; the measured production code is HEAD 67037b99, which includes the CR-1 fix).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Statements = JaCoCo
INSTRUCTION counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `reminders/ReminderAlarms.kt` | 124/126 (98.4%) | 17/18 (94.4%) | 7/7 (100%) | 23/23 (100%) |
| `reminders/ReminderNotifier.kt` | 180/181 (99.4%) | 12/12 (100%) | 8/8 (100%) | 44/45 (97.8%) |
| `reminders/ReminderOpenRequests.kt` | 90/96 (93.8%) | 19/19 (100%) | 6/8 (75.0%) | 24/24 (100%) |
| `reminders/ReminderPermissionPrompt.kt` | 934/941 (99.3%) | 36/40 (90.0%) | 29/29 (100%) | 105/105 (100%) |
| `reminders/ReminderReceivers.kt` | 79/79 (100%) | 8/8 (100%) | 7/7 (100%) | 16/16 (100%) |
| `reminders/ReminderScheduler.kt` | 259/261 (99.2%) | 16/16 (100%) | 10/11 (90.9%) | 44/44 (100%) |
| `reminders/ReminderStore.kt` | 244/246 (99.2%) | 9/10 (90.0%) | 21/21 (100%) | 45/45 (100%) |
| `reminders/ReminderSync.kt` | 223/223 (100%) | 10/10 (100%) | 11/11 (100%) | 52/52 (100%) |
| `reminders/RemindersModule.kt` | 23/26 (88.5%) | N/A (0/0) | 3/4 (75.0%) | 5/6 (83.3%) |
| `MainActivity.kt` | 73/73 (100%) | 6/6 (100%) | 6/6 (100%) | 11/11 (100%) |
| `ui/CockpitShell.kt` | 1013/1027 (98.6%) | 46/48 (95.8%) | 27/27 (100%) | 85/86 (98.8%) |
| `auth/CockpitRoot.kt` | 229/242 (94.6%) | 9/14 (64.3%) | 10/10 (100%) | 14/14 (100%) |
| **All 12 files (summed counters)** | 3471/3521 (98.6%) | 188/201 (93.5%) | 145/149 (97.3%) | 468/471 (99.4%) |
| reminders package + MainActivity only (10 files, summed) | 2229/2252 (99.0%) | 133/139 (95.7%) | 108/112 (96.4%) | 369/371 (99.5%) |

All 12 scoped files were found in the report; none is missing or unmeasured. The 3 resource files are excluded (above).
The CockpitShell and CockpitRoot numbers are whole-file and include code older than this task.

The previous run (before the 2 new tests, same command, report sha256
c417a338264d85d4f0db35e0b351908b8a3de400737a8e46b95246772eb317ae) differed only in ReminderPermissionPrompt branches
(35/40): the new dialog-granted test covers one more branch of the permission request path.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ReminderAlarms.kt:39` (`?: return` when `PendingIntent.getBroadcast` returns null) | none: with FLAG_UPDATE_CURRENT the call always creates the intent | Defensive, not reachable; no test |
| `ReminderNotifier.kt:53` (catch SecurityException on notify) | permission revoked between check and call | Robolectric's NotificationManager never throws; the race is not reproducible without mocking the platform. Low risk (the notification is dropped silently, which is the intended behavior) |
| `ReminderOpenRequests.kt:11-12` | generated data class members (`copy`/`componentN`) | Not behavior; no test |
| `ReminderPermissionPrompt.kt:128,131,170-171` | Compose default-parameter branches (`hiltViewModel()`, `Modifier`) | The prompt with the real Hilt view model runs in the app; tests pass a view model. Not behavior |
| `ReminderPermissionPrompt.kt:153` (exact-alarm button below Android 12) | none: the exact button is never shown before Android 12 (`canScheduleExact` is true there), asserted by `before Android 13 the prompt asks only for notifications ...` (SDK 30) | Unreachable branch; no test |
| `ReminderScheduler.kt:99` | companion object synthetic constructor | Not behavior |
| `ReminderStore.kt:69` (`orEmpty()` when `getStringSet` returns null) | none: a default value is given | Defensive; no test |
| `RemindersModule.kt:13` | constructor of the abstract Hilt `@Binds` module, never instantiated | Not behavior |
| `CockpitShell.kt:66-67` (`take(request)` false branch) | a second collector taking the request first | Only one shell collects; the "taken once" property is asserted in `a tapped notification opens the item's screen in the shell`. No test |
| `CockpitShell.kt:148,166`, `CockpitRoot.kt:22,35` | pre-existing `when` default and Compose default-parameter branches | Outside this task's changed behavior |

## Unable to test

- Real device behavior of AlarmManager (doze, actual exact vs inexact delivery, the 500-alarm limit), the real
  BOOT_COMPLETED broadcast, the system exact-alarm page and the real POST_NOTIFICATIONS dialog: only testable on a
  device/emulator. The unit tests use Robolectric shadows (ShadowAlarmManager, ShadowNotificationManager, permission
  result intents), which the acceptance criterion names. No follow-up required for this task; an instrumented smoke
  test would be the follow-up if one is wanted.
- The SecurityException race paths (alarm and notification) cannot be produced with Robolectric without mocking the
  platform; the alarm path is tested via the shadow's exact-permission switch (ReminderAlarmsNotifierTest).

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.
