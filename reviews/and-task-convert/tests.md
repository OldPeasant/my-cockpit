# Test Report

Date: 2026-11-08

Task: and-task-convert - Android: convert tasks to notes and events ("To reference" / "To calendar" from Inbox processing and task detail)

Scope: base 8e3ba22c1b346ce55c9c59404028e1c3edd068de .. HEAD 5a5b14f0d324569466f63cb22123e524c05edb58 (task commits 333f2da75..5a5b14f0d, supplied by the parent worker), branch `main`, working tree clean before the runs (no uncommitted inputs).

Requirements:
- `tasks/and-task-convert.md` acceptance criteria and recorded assumptions
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- `reviews/and-task-convert/review.md`, `reviews/and-task-convert/fixes.md` (CR-1 ordering fix)

## Test files changed

Test files were added by earlier test-writer/implementation sessions in the task commits; this session ran and validated them and added no new test code (the two ordering tests from commit 5a5b14f0d were run for the first time here and pass).

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/InboxConversionRepositoryTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/InboxConvertScreenTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/InboxConvertViewModelTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/InboxRepositoryTest.kt` (updated)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/ConvertedOpenerShellTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/EventPlanTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/FakeTaskDetailSource.kt` (updated test helper)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskConversionRepositoryTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailConvertRouteTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailConvertScreenTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailConvertViewModelTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailRepositoryTest.kt` (updated)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailStringsTest.kt` (updated)

No `@Ignore` in any test under `gtd/` (grep checked). No existing test was weakened.

## Tests added or updated

Count: 111 task-specific tests in the new suites (InboxConversionRepositoryTest 10, InboxConvertScreenTest 8, InboxConvertViewModelTest 18, ConvertedOpenerShellTest 3, EventPlanTest 11, TaskConversionRepositoryTest 15, TaskDetailConvertRouteTest 3, TaskDetailConvertScreenTest 18, TaskDetailConvertViewModelTest 25), plus additions to InboxRepositoryTest, TaskDetailRepositoryTest and TaskDetailStringsTest. 0 added in this session.

## Requirement coverage

Focused command (F): `cd android && ./gradlew testDebugUnitTest --tests '*Convert*' --tests '*Conversion*' --tests '*EventPlan*' --tests '*TaskDetailStringsTest' --tests '*InboxRepositoryTest' --tests '*TaskDetailRepositoryTest'`
Full command (A): `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Inbox processing has "To reference" (converts to a note, opens it / shows link) and "To calendar" (dialog: calendar, start, duration/all-day) | InboxConvertScreenTest (8), InboxConvertViewModelTest (18, incl. CR-1 ordering: "a conversion answering after a newer plain action started is not shown, as on the web", "an older conversion answering after the newer one was refused shows nothing and keeps the refusal", cancelled calendar loads), InboxConversionRepositoryTest (10), EventPlanTest (11), InboxRepositoryTest | F, A | Pass |
| 2. Task detail has the same two actions in its menu | TaskDetailConvertScreenTest (18), TaskDetailConvertViewModelTest (25), TaskConversionRepositoryTest (15), TaskDetailConvertRouteTest (3), TaskDetailRepositoryTest | F, A | Pass |
| 3. After converting, the user sees where the item went with an "Open" action | InboxConvertScreenTest / TaskDetailConvertScreenTest snackbar + Open cases, TaskDetailConvertRouteTest, ConvertedOpenerShellTest (3: note opens editor via search item route, event opens Calendar tab) | F, A | Pass |
| 4. New strings in English and German | TaskDetailStringsTest (5); `lintDebug` (MissingTranslation) | F, A | Pass |
| 5. Unit/Compose tests cover the new behavior; `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass | all above | A | Android command: Pass. `sh scripts/check.sh`: Not run in this session (time budget; ~5 min) - parent worker must run it |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests '*Convert*' --tests '*Conversion*' --tests '*EventPlan*' --tests '*TaskDetailStringsTest' --tests '*InboxRepositoryTest' --tests '*TaskDetailRepositoryTest'`

Result:

Pass - BUILD SUCCESSFUL in 1m 41s; 183 tests in 14 suites (incl. 6 matched tests from NewScreenTest/NewViewModelTest), 0 failures, 0 errors, 0 skipped (InboxConvertViewModelTest 18/18 incl. the two newest CR-1 ordering tests).

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --console=plain -q` (18:43:39 - 18:51:09 +01:00), immediately repeated without output filtering to confirm exit status 0 (all tasks up-to-date).

Result:

Pass - exit 0; ktlintCheck and lintDebug clean; 4313 unit tests, 0 failures, 0 errors, 0 skipped.

## .taskcheck

Result:

Not run - `sh scripts/check.sh` (~5 min, includes server and web) was not run in this session because of the parent's time budget. The Android part of it passed (above). The release note in the task records a passing `check.sh` at 509d011a2; the parent worker must run it on the final revision.

## Bugs found

None. No production defects found by the tests in this session.

## Coverage

Status: verified (whole-file and changed-line, Android unit tests).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured - JaCoCo `<line>` entries (source-mapped, `ci > 0` = covered) intersected with added/modified lines from `git diff -U0 <base>..HEAD -- android/app/src/main/java`; removed and non-executable lines not counted. Result: 469/469 executable changed lines covered (100.0%). Branch counters on changed lines: 246/266 (reported for information; not changed-branch coverage in a strict sense).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-task-convert; base 8e3ba22c1b346ce55c9c59404028e1c3edd068de; HEAD 5a5b14f0d324569466f63cb22123e524c05edb58; commits 333f2da75..5a5b14f0d supplied by the parent; file scope from `git diff --name-only <base>..HEAD -- android/app/src/main`, consistent with the task's `touches` plus the assumed `ui/CockpitShell.kt` edit.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 5a5b14f0d, clean working tree (no uncommitted source/test changes; only Git-ignored `.tmp/` scripts and build output).
- Exact included files: the 11 Kotlin files in the table below. Excluded: the 4 string resource XML files (non-executable; validated by TaskDetailStringsTest and lintDebug). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` in `android/`; full debug unit test suite.
- Run start/completion time and exit status: 2026-11-08T18:43:39+01:00 - 18:51:09+01:00, exit 0 (confirmed by an up-to-date re-run).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (mtime 2026-11-08 18:51:08 +01:00, sha256 bec55680aa0bdb8dcd906e49c1cd039f1405907f817f90d96c10b5e8ec7e4218).
- Evidence outputs are fresh and inputs stayed unchanged: report written during this run; no source or test edits during or after it.
- Final run after latest test additions and production fixes: yes (HEAD includes the CR-1 fix b5f69870d and the newest tests 5a5b14f0d).

Values below use covered/total (percentage). Statements = JaCoCo INSTRUCTION counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxActions.kt` | 323/326 (99.1%) | 24/24 (100.0%) | 27/28 (96.4%) | 33/33 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxRepository.kt` | 1225/1232 (99.4%) | 79/80 (98.8%) | 17/18 (94.4%) | 89/91 (97.8%) |
| `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxScreen.kt` | 5229/5316 (98.4%) | 149/166 (89.8%) | 110/129 (85.3%) | 432/432 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxViewModel.kt` | 2012/2037 (98.8%) | 178/184 (96.7%) | 81/82 (98.8%) | 192/192 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskConversion.kt` | 530/532 (99.6%) | 26/28 (92.9%) | 37/37 (100.0%) | 55/55 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailRepository.kt` | 1906/1946 (97.9%) | 122/132 (92.4%) | 108/113 (95.6%) | 182/185 (98.4%) |
| `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailScreen.kt` | 9413/11719 (80.3%) | 298/426 (70.0%) | 193/286 (67.5%) | 743/868 (85.6%) |
| `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailViewModel.kt` | 4899/5043 (97.1%) | 413/477 (86.6%) | 122/126 (96.8%) | 461/468 (98.5%) |
| `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskNavigation.kt` | 117/117 (100.0%) | 4/4 (100.0%) | 17/17 (100.0%) | 19/19 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/ToCalendarDialog.kt` | 2025/2039 (99.3%) | 32/34 (94.1%) | 56/58 (96.6%) | 139/139 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt` | 2027/2118 (95.7%) | 109/129 (84.5%) | 49/53 (92.5%) | 174/176 (98.9%) |
| **Aggregate (summed counters, 11 files; 4 string XML files excluded as non-executable; none missing)** | 29706/32425 (91.6%) | 1434/1684 (85.2%) | 817/947 (86.3%) | 2519/2658 (94.8%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `TaskDetailScreen.kt`: lines 164-197, 371-404, 967-989, 1051-1155, 1218-1279 etc. | Pre-existing task detail UI (date/recurrence editors, other menu dialogs) outside this task; none of the missed lines are task-changed lines (changed-line coverage 81/81) | Justified gap: outside task scope |
| `TaskDetailViewModel.kt`: lines 787-794 | Pre-existing code, not changed by the task (changed lines 74/74 covered) | Justified gap: outside task scope |
| `TaskDetailRepository.kt`: 196, 199-200; `InboxRepository.kt`: 194-195; `CockpitShell.kt`: 260, 335 | Pre-existing code, not task-changed lines | Justified gap: outside task scope |
| `TaskConversion.kt:134` (partial branch) | `startDay()` for a timed event whose `start` is null - defensive against a malformed server response | Non-material defensive branch; no test added |
| `ToCalendarDialog.kt:77` (default parameter `enabled = true`), `:203` (date picker confirmed with no selected date) | Compose default-argument synthetic branch; null selection guard | Non-material; no test added |
| Changed-line branches: 20 of 266 branch counters on changed lines missed (mostly Compose/Kotlin synthetic null-safety and default-argument branches) | - | Not investigated individually within the time budget; all changed executable lines are covered |

## Unable to test

- `sh scripts/check.sh` (criterion 5) was not run in this session (time budget); the parent must run it before `done`.
- Behavior on a real device/emulator (instrumented UI, actual navigation into the note editor / Calendar tab) is covered only by Robolectric Compose tests, not on a device. The criteria ask for unit/Compose tests, so no follow-up required.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. All 183 focused tests (including the two new CR-1 ordering tests) and the full Android `ktlintCheck testDebugUnitTest lintDebug` run pass; `sh scripts/check.sh` still has to be run by the parent worker.
