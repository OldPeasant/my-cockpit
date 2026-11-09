# Test Report

Date: 2026-11-09

Task: task-reminders - Reminders on tasks (web + Android)

Scope: the integration parent of two subtasks that are already done. `web-task-reminders` (commits 5b4986f21..acde3e1fc: `web/src/app/features/gtd/task-detail`, `web/src/locale`) and `and-task-reminders` (commits 783bab0a0..b377610c3: `android/.../cockpit/gtd/task`, `ui/CockpitShell.kt`, `reminders/ReminderOpenRequests.kt`, `strings_task_detail.xml` en+de). The parent task has no code of its own. Measured HEAD is 66e86ae8d9d1ad1f46d942477911967eec32db8a, which also equals `origin/main` and `git merge-base HEAD origin/main`. Branch main, clean working tree. This helper added no tests.

Requirements:
- `tasks/task-reminders.md` acceptance criteria
- `reviews/web-task-reminders/tests.md` and `reviews/and-task-reminders/tests.md`
- AGENTS.md, `docs/ARCHITECTURE.md` "Coverage", the parent's instructions

## Test files changed

None. No real gap was found, so this helper changed no test files.

## Tests added or updated

Count: 0

The gap check covered three points:
- **Web/Android preset parity.** I compared `web/.../task-detail/task-reminders.ts` with `android/.../gtd/task/TaskReminders.kt`. Both have the same four presets in the same order: AT_DUE, HOUR_BEFORE_DUE, DAY_BEFORE_DUE, AT_SCHEDULED. Both use the same 09:00 default (`DEFAULT_REMINDER_TIME`) for a due or scheduled date without a time, and both offer HOUR_BEFORE_DUE only when the task has a due time. DAY_BEFORE_DUE is a calendar day on both clients, so the time of day stays the same across a DST change. Both offer only presets strictly in the future, so a preset exactly now is not offered. Each client tests these rules on its own side: web `presetTime`/`availablePresets` specs in `task-reminders.spec.ts`, Android `TaskReminderPresetsTest` (nine o'clock default, no hour-before without a time, scheduled at nine, DST, no dates, exactly now). A cross-platform test is not possible because the clients have no shared code.
- **German strings on Android.** The Android subtask report said "German not asserted". However, the existing `TaskDetailStringsTest` checks every `task_detail_*` key in `values/strings_task_detail.xml`. It checks that the key exists in `values-de`, is not blank, keeps its placeholders and is translated rather than copied. All new reminder strings (`task_detail_reminders*`, `task_detail_reminder_*`) use that prefix, so the existing test covers them. On the web, `src/locale/messages-de.spec.ts` covers the German units.
- **ReminderSync and opening the notification on Android.** `TaskReminderRepositoryTest` checks that `ReminderSync.sync()` runs once after a successful POST or DELETE (404 included) and never after a list call or a failed call. `OpenItemRequestTaskTest` and the `ReminderUiTest` test "a tapped task notification opens the task's detail on top of Next" cover the TASK notification opening the task detail.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 web: add/remove reminders (presets relative to due/scheduled date, exact date/time), existing reminders listed | `task-reminders.spec.ts` (preset functions, add from preset, exact time, past time refused, remove with rollback, list, stale-response guards), `task-detail.service.spec.ts` (GET/POST/DELETE) | `npm --prefix web run test:ci -- --include='src/app/features/gtd/task-detail/**/*.spec.ts' --include='src/locale/**/*.spec.ts'` | Pass |
| AC2 Android: the same, plus local alarms through the existing reminder sync | `TaskReminderPresetsTest`, `TaskRemindersViewModelTest`, `TaskReminderRepositoryTest` (sync after create/delete, none after failure), `TaskRemindersContentTest`, `TaskDetailConvertRouteTest` | `android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.*' --tests 'ch.sonensei.cockpit.reminders.*'` | Pass |
| Preset parity web/Android (same presets, 09:00 default for date-only) | web preset specs; Android `TaskReminderPresetsTest`; source comparison above | both commands above | Pass on each client; parity checked by reading the source |
| AC3: a reminder fires as a notification and opens the task | Android unit level: `OpenItemRequestTaskTest`, `ReminderUiTest` "a tapped task notification opens the task's detail on top of Next" | Android command above | Pass at unit level. A real notification firing: Not run, because there is no device, emulator, or browser with notification permission and the server scheduler. The parent must verify it manually or skip it with a reason |
| AC4: English and German strings on both clients | web `src/locale/messages-de.spec.ts`; Android `TaskDetailStringsTest` | both commands above | Pass |
| AC5: unit tests cover the new behavior; `sh scripts/check.sh` passes | everything above | focused suites | Unit tests Pass. `scripts/check.sh` Not run by this helper (the parent runs it) |

## Focused test execution

Command:

`npm --prefix web run test:ci -- --include='src/app/features/gtd/task-detail/**/*.spec.ts' --include='src/locale/**/*.spec.ts'`

Result:

Pass. 9 files and 382 tests passed (Vitest 5.0.5, run 2026-11-09 01:00:51 local time).

Command:

`android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.*' --tests 'ch.sonensei.cockpit.reminders.*'`

Result:

Pass. BUILD SUCCESSFUL in 33s, and `testDebugUnitTest` really ran. The JUnit XML in `android/app/build/test-results/testDebugUnitTest/` has 33 suites and 449 tests, with 0 failures, 0 errors and 0 skipped.

## Full test execution

Command:

Full web and Android suites.

Result:

Not run by this helper, because of the short session. The subtask reports have the full web run (194 files, 4630 tests, Pass). The parent runs the whole project with `sh scripts/check.sh`.

## .taskcheck

Result:

Not run by this helper. The parent worker runs it before `done`.

## Bugs found

None.

## Coverage

Status: partial. I did not measure coverage again for this integration task because of the session budget. The numbers below are reused from the subtask reports. Both were fresh, verified runs on the subtasks' final production code. Since those revisions, no production file in scope has changed up to HEAD 66e86ae8d. I checked this with `git diff --stat 2274f57a7 HEAD` on the web scope, which shows only `task-reminders.spec.ts` (+45 lines, the web test-writer's last 2 tests, which its run included). `git diff --stat 4be818e65 HEAD` on the Android scope is empty. So the numbers still describe the current code.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: task-reminders. Base = HEAD = 66e86ae8d9d1ad1f46d942477911967eec32db8a. Scope = the union of the subtask scopes (web 5b4986f21..acde3e1fc, Android 783bab0a0..b377610c3), as given by the parent.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 66e86ae8d with a clean tree. There are no uncommitted inputs.
- Exact included files: web `task-reminders.ts`, `task-detail.service.ts`, `task-detail.ts`; Android `gtd/task/TaskReminders.kt`, `gtd/task/TaskRemindersSection.kt`, `gtd/task/TaskDetailScreen.kt`, `reminders/ReminderOpenRequests.kt`, `ui/CockpitShell.kt`. Excluded: templates and styles, `web/src/locale/*.xlf` and the Android string resources. These are not instrumented; the string tests above check them.
- Provider/version, command, working directory, and test selection: web Vitest 5.0.5 with coverage-v8 (`npm --prefix web run coverage`, all specs); Android JaCoCo via AGP (`createDebugUnitTestCoverageReport` filtered to `gtd.task`, `reminders`, `ui`). Both were run by the subtask test-writers.
- Run start/completion time and exit status: web 2026-11-09 00:53:18 to 00:54:04, exit 0. Android report written 2026-11-09T00:15:52, exit 0. Both come from the subtask reports.
- Generated report paths and report hashes/run identifier: web `coverage-summary.json` sha256 d769386b6bbc23a3c29089229fbb94daa82d8521c5a1da2a3815017c0d84e8a0; Android `report.xml` sha256 prefix 2fef176a0340ff2d. Both come from the subtask reports.
- Evidence outputs are fresh and inputs stayed unchanged: the production inputs are unchanged since those runs, as the git diffs above show. The reports were not regenerated in this session.
- Final run after latest test additions and production fixes: yes for production code. This helper made no changes.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/.../task-detail/task-reminders.ts` | 118/119 (99.15%) | 68/71 (95.77%) | 30/30 (100%) | 98/98 (100%) |
| `web/.../task-detail/task-detail.service.ts` | 20/20 (100%) | 8/8 (100%) | 16/16 (100%) | 18/18 (100%) |
| `web/.../task-detail/task-detail.ts` | 303/307 (98.69%) | 202/215 (93.95%) | 64/64 (100%) | 248/248 (100%) |
| `android/.../gtd/task/TaskReminders.kt` | 1151/1175 (98.0%) | 76/82 (92.7%) | 52/54 (96.3%) | 127/130 (97.7%) |
| `android/.../gtd/task/TaskRemindersSection.kt` | 1967/2029 (96.9%) | 66/88 (75.0%) | 36/39 (92.3%) | 130/131 (99.2%) |
| `android/.../gtd/task/TaskDetailScreen.kt` | 9184/11430 (80.3%) | 292/416 (70.2%) | 193/284 (68.0%) | 720/838 (85.9%) |
| `android/.../reminders/ReminderOpenRequests.kt` | 128/134 (95.5%) | 23/23 (100%) | 7/9 (77.8%) | 25/25 (100%) |
| `android/.../ui/CockpitShell.kt` | 2053/2171 (94.6%) | 110/133 (82.7%) | 47/54 (87.0%) | 176/178 (98.9%) |

The web and Android numbers are not summed together. Their statement counters measure different units (V8 statements and JaCoCo instructions). Per-client aggregates from summed counters: web statements 441/446 (98.88%), branches 278/294 (94.56%), functions 110/110 (100%), lines 364/364 (100%). Android statements 14483/16939 (85.5%), branches 567/742 (76.4%), functions 335/440 (76.1%), lines 1178/1302 (90.5%). Exclusions are listed above. No file in scope is unmeasured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `task-reminders.ts:231`, `:46`, `:103` | A defensive guard behind a disabled Retry button, an invalid-date arm that cannot be reached, and a compiler-generated branch | Justified gap (see `reviews/web-task-reminders/tests.md`) |
| `TaskRemindersSection.kt:71-72` | The `hiltViewModel()` fallback inside a Hilt activity | Needs a Hilt test activity. The other branches are covered. Justified gap |
| `TaskReminders.kt:110-111`, `:128` | Rethrowing a CancellationException, and the Hilt `@Binds` wiring | Defensive or generated code. Justified gap |
| `TaskDetailScreen.kt`, `CockpitShell.kt`, `task-detail.ts` remaining gaps | Code from before the subtasks | Out of scope |

## Unable to test

- A reminder really firing as a notification and opening the task, in a browser and on a device (AC3). This environment has no browser with notification permission, no device or emulator, and no server scheduler. Unit tests cover how a tapped notification is routed on Android. Follow-up: the parent verifies it manually or skips the criterion with a reason.
- Fresh task-scoped coverage for this integration task was not measured again (session budget). I reused the subtask numbers; the production code they describe is unchanged.

## Summary

Tests and task-scoped coverage are sufficient, and no production defects were found. Both focused suites pass (web 382, Android 449). Presets match between web and Android, including the 09:00 default. The existing tests already cover German strings, ReminderSync and opening the task from a notification on both clients. AC3 (a real notification) still needs a manual check or a skip with a reason.
