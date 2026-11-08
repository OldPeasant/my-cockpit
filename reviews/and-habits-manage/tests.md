# Test Report

Date: 2026-11-08

Task: and-habits-manage - Android habits: create, edit, archive, incremental check

Scope: task base 05a81302e9745e259183803d9de0c775f11d56d1 .. 9194c59d4c48ca59febbd8f0e103506896974485 (all commits after the base, on main; clean tree). The coordinator gave the task ownership and base.

Requirements:
- `tasks/and-habits-manage.md` acceptance criteria and recorded assumptions
- `reviews/and-habits-manage/review.md` (second round CR-1/CR-3), `reviews/and-habits-manage/fixes.md` (second round)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`

## Test files changed

None in this round. The test files were added or updated by earlier test-writer and fixer rounds in this task:

- `android/app/src/test/java/ch/sonensei/cockpit/habits/HabitsViewModelManageTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/habits/HabitDraftTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/habits/HabitsManageScreenTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/habits/HabitsRepositoryWriteTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/habits/HabitsRepositoryTest.kt`, `HabitsRouteInAppTest.kt`, `HabitsScreenTest.kt`, `HabitsViewModelTest.kt` (adapted)

## Tests added or updated

Count: 0 in this round. As the coordinator instructed, no test was added for the known gap below, because this helper could not run Gradle.

I reviewed the second-round CR-1 change in `HabitsViewModel.startLoad`/`reloadAfterConflict`/`reloadIfPending`. Each new behaviour has an existing test in `HabitsViewModelManageTest`:

| Changed behaviour (fixes.md round 2, CR-1) | Existing test |
|---|---|
| A quiet conflict reload does not increase `loadSeq`, so it never cancels a view load still on its way | `a conflict reload after a view switch does not cancel the view load, so the view still gets its list` |
| A quiet reload is not dropped while the view has no list yet (`loaded == false`) | same test, plus `a conflict reload answered after the view load and a later change is dropped, runs again, and the view stays loaded` |
| A dropped quiet reload runs again right away when no change is in flight any more | `a conflict reload read before a later archive finished does not bring the archived habit back, and runs again`, `a conflict reload read before a later check-off finished does not undo the check-off` |
| A dropped quiet reload waits while a change is still in flight (`reloadPending`), then runs once it finishes | `a conflict reload answer is dropped while a later change is still on its way and runs again after it` |

## Requirement coverage

The coordinator ran the command below on 9194c59d4 (clean tree): exit 0. This helper read the results from `android/app/build/test-results/testDebugUnitTest` and `report.xml`.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 create/edit (name, schedule, target, reminder) | `HabitDraftTest`, `HabitsManageScreenTest`, `HabitsRepositoryWriteTest`, `HabitsViewModelManageTest` (form, save, conflict tests) | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport -q` | Pass |
| AC2 archive/unarchive, Archived view | `HabitsViewModelManageTest` (archive/unarchive/view-switch tests), `HabitsManageScreenTest` | same | Pass |
| AC3 incremental check up to target then reset; best streak shown | `HabitsViewModelManageTest` (`taps on a habit with a target of three ...`), `HabitsRepositoryWriteTest`, `HabitsScreenTest`/`HabitsManageScreenTest` | same | Pass |
| AC4 strings in English and German | `lintDebug` (`MissingTranslation`) | same | Pass |
| AC5 unit/Compose tests; ktlintCheck testDebugUnitTest lintDebug pass | all of the above | same | Pass. `sh scripts/check.sh`: Not run by this helper (the parent runs it) |
| CR-1 round 2 quiet conflict reload | the 5 conflict-reload tests listed above | same | Pass |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport -q` (run by the coordinator; Gradle was denied to this helper)

Result:

Pass. Exit 0, BUILD SUCCESSFUL. `HabitsViewModelManageTest`: 36 tests, 0 failures, 0 errors.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` (part of the command above: the whole Android unit suite, ktlint and lint)

Result:

Pass

## .taskcheck

Result:

Not run by this helper (`sh scripts/check.sh` is left to the parent worker).

## Bugs found

None.

## Coverage

Status: verified. The numbers come from the coordinator's fresh run on 9194c59d4 with a clean tree. This helper only parsed the report.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-habits-manage; base 05a81302e9745e259183803d9de0c775f11d56d1; HEAD 9194c59d4c48ca59febbd8f0e103506896974485; scope = `git diff --stat <base>..HEAD` restricted to `android/app/src/main`, given by the coordinator.
- Source/test snapshot identifier (including uncommitted inputs): commit 9194c59d4, clean tree (no uncommitted inputs).
- Exact included files: `HabitEditorDialog.kt`, `HabitsRepository.kt`, `HabitsScreen.kt`, `HabitsViewModel.kt` (all in `android/app/src/main/java/ch/sonensei/cockpit/habits/`). Excluded: `res/values/strings_habits.xml` and `res/values-de/strings_habits.xml` (resources, not instrumented; checked by lint). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport -q` in `android/`; all debug unit tests.
- Run start/completion time and exit status: completed by 2026-11-08 20:54:44 +0100 (report mtime); exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 `fc71d112f7429edf9731809d157e42cea9edf7bc3d9eacae153d5555247df0e2`.
- Evidence outputs are fresh and inputs stayed unchanged: the coordinator ran it on clean 9194c59d4; HEAD was still 9194c59d4 when the report was parsed, and no test was added afterwards.
- Final run after latest test additions and production fixes: yes (9194c59d4 includes the second-round fixes).

Values below use covered/total (percentage).

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `HabitEditorDialog.kt` | 2314/2350 (98.5%) | 70/85 (82.4%) | 49/51 (96.1%) | 173/176 (98.3%) |
| `HabitsRepository.kt` | 994/1013 (98.1%) | 44/45 (97.8%) | 60/60 (100%) | 112/118 (94.9%) |
| `HabitsScreen.kt` | 3080/3141 (98.1%) | 108/123 (87.8%) | 56/67 (83.6%) | 241/244 (98.8%) |
| `HabitsViewModel.kt` | 1459/1471 (99.2%) | 117/126 (92.9%) | 45/45 (100%) | 149/149 (100%) |
| **Aggregate (4 files, summed counters)** | 7847/7975 (98.4%) | 339/379 (89.4%) | 210/223 (94.2%) | 675/687 (98.3%) |

The aggregate excludes only the two strings XML files (not instrumented). Every scoped Kotlin file is present in the report. JaCoCo has no separate statement counter; instructions are shown instead.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `HabitsViewModel.kt:131,145,149,219,242,269` (partial branches) | Kotlin `when`/null-safe synthetic branches, and combinations of the `changing()`/`saving` guards | All lines covered; the conflict-reload branches (`seq`, `quiet && changes != changeSeq && loaded`, `changing()`) are exercised by the 5 conflict-reload tests. Justified gap. |
| `HabitsViewModel.kt` `startLoad`: `if (!quiet) reloadPending = false` | A user reload or view switch while a dropped conflict reload is pending must clear the pending flag, so finishing the change does not start an extra quiet reload | Known gap: the coordinator decided not to add the test this round. Low risk: a regression would only cause one extra list request for the shown view, not wrong data. Follow-up test suggested. |
| `HabitsRepository.kt:243-244,249-252` | `CancellationException` rethrow; `SerializationException`/`IllegalArgumentException` mapped to `Failed(null)` | Defensive error mapping; offline/HTTP failure mapping is tested in `HabitsRepositoryWriteTest`. Justified gap. |
| `HabitsRepository.kt:273` (1 of 4 branches) | 2xx answer with a null body | Defensive. Justified gap. |
| `HabitEditorDialog.kt:115,166-167` and partial branches at 86,104,110,162,221,244,255,281,287 | Exhaustive `when` synthetic branch; `TIMES_PER_WEEK`/`TARGET` problem texts (the steppers clamp to valid ranges, so the UI cannot reach these problems); enabled/disabled combinations | `HabitDraftTest` covers the validation that produces these problems. Justified gap. |
| `HabitsScreen.kt:367,393,395` and partial branches at 106-118,260,319,347,363-368,391,400; 11 missed methods (composable lambdas) | Exhaustive `when` branches; `LOAD_FAILED`/`CHECK_FAILED` banner texts; some subtitle combinations | Banner mapping is a plain resource lookup; the other message paths are tested in Screen tests. Low risk. Justified gap. |

## Unable to test

- This helper could not run Gradle (permission denied), so it relied on the coordinator's run on 9194c59d4. No follow-up needed beyond the known gap below.
- Known gap: no test checks that a non-quiet load clears `reloadPending` (see the coverage table). Follow-up: add a `HabitsViewModelManageTest` case: conflict reload dropped while a check is in flight, then `showArchived(true)`, then the check finishes -> expect no extra `list(true)` call.
- `sh scripts/check.sh` (`.taskcheck`) was left to the parent worker.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. One low-risk known gap (pending conflict reload cleared by a user/view load) is recorded for follow-up.
