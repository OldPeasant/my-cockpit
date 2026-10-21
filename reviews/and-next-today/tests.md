# Test Report

Date: 2026-10-21

Task: and-next-today: Android Next and Today

Scope: `git diff 44de27f6a4147ff3ccabc0ef3acc6c2cd968b92f..6f721d1f98c86d6de60b7171d5784d306524b885` (task commits 47a1bca1, 6a264214, 6f721d1f), plus the uncommitted test files listed below (working tree, branch `main`).

Requirements:
- `tasks/and-next-today.md`: acceptance criteria and the 4 recorded assumptions (filter semantics, Today sections only, no next-step prompt, shared UI in `gtd/next`)
- `SPEC.md` §5.3 (Next actions filters, Today), §5.1 (estimate quick picks), §9.3 (offline read-only)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`

## Test files changed

An earlier test-writer run was interrupted and left uncommitted, unreported test edits. I reviewed them all, ran them, and kept them: every one passes and checks behavior from the criteria or assumptions. This run added 4 more tests (marked below).

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/NextFilterTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/NextRouteTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/NextScreenInAppTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/TaskCompleterTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/today/TodayRouteTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/NextScreenTest.kt` (extended; 3 tests added in this run)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/NextViewModelTest.kt` (extended; 1 test added in this run)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/TaskCompletionRepositoryTest.kt` (extended)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/today/TodayScreenTest.kt` (extended)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/today/TodayViewModelTest.kt` (extended)

No existing test line was removed or weakened. `git diff` of the test files only adds lines. No production file was changed.

## Tests added or updated

Count: 59 (39 added to existing classes, 20 in new classes). The focused suite grew from 36 to 95 tests.

Added in this run:
- `NextScreenTest.every energy, creativity and estimate chip is labelled with the value it toggles`
- `NextScreenTest.an undo offer that times out is dismissed, not undone`
- `NextScreenTest.a filter on an empty Next list says the list is empty, not that nothing matches`
- `NextViewModelTest.cached tasks without a refresh time show at once instead of loading`

## Requirement coverage

Command F = `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.next.*' --tests 'ch.sonensei.cockpit.gtd.today.*'` (95 tests, 0 failures, 0 skipped).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Next: context filter (one context, matches any of the task's contexts, toggles off, dropped when the context is deleted) | `NextFilterTest.a context filter matches any of the task's contexts`; `NextViewModelTest.filters by context`, `drops the filter of a context deleted elsewhere`, `a context filter stays while its context still exists`; `NextScreenTest.each filter chip calls its toggle`; `NextRouteTest.each filter chip filters the list and clearing shows everything again` | F | Pass |
| Next: energy and creativity filters (exact match, tasks without the attribute do not match) | `NextFilterTest.energy and creativity must match exactly and are not ranges`; `NextViewModelTest.filters by energy and creativity`, `toggling a chosen value again clears only that filter`; `NextScreenTest.every energy, creativity and estimate chip is labelled with the value it toggles`; `NextRouteTest.each filter chip ...` | F | Pass |
| Next: estimate filter (max minutes, inclusive, needs an estimate; quick picks 5/15/30/60/120) | `NextFilterTest.the estimate filter is inclusive and needs an estimate`, `the estimate quick picks are those of the spec`; `NextViewModelTest.filters by maximum estimate, only matching estimated tasks`; `NextScreenTest.every ... chip is labelled ...`; `NextRouteTest.each filter chip ...` | F | Pass |
| Next: filters combine, show their count, and clear | `NextFilterTest.combined filters need every one to match`; `NextViewModelTest.combines filters and clears them`, `the filter stays when the list reloads, ...`; `NextScreenTest.shows the active filter and a way to clear it`, `no match under a filter offers to clear it`, `a filter on an empty Next list says the list is empty, ...`, `the filters open and close, ...` | F | Pass |
| Next: list states (loading, empty, offline read-only with banner) | `NextViewModelTest.is loading until ...`, `offline with nothing cached is not loading`, `cached tasks without a refresh time show at once ...`, `offline the list is read-only ...`; `NextScreenTest.shows loading ...`, `an empty Next list says so ...`, `offline ...` (3 tests) | F | Pass |
| Today: overdue / due today / scheduled sections (order, each task once, empty sections left out, date rules, clock's day) | `TodayViewModelTest.groups overdue, due today and scheduled tasks, each task once`, `leaves out empty sections`, `sections follow the date rules`, `due today wins over scheduled earlier, ...`, `the date rules follow the clock's day`, `an overdue Next task missing from the cached Today view ...`, `a task in both views keeps the Today version`; `TodayScreenTest.shows the overdue, due and scheduled sections with their tasks`, `section headers count their tasks ...`, `a scheduled task due later shows its due date` | F | Pass |
| Today: loading, empty, offline states | `TodayViewModelTest.is loading until both views were loaded`, `refreshedAt is ...` (2 tests), `offline in either view ...`, `offline in the Next view also makes Today read-only`, `offline with nothing cached is not loading`; `TodayScreenTest.empty and loading states`, `shows loading before anything was loaded`, `offline ...` (2 tests) | F | Pass |
| Complete with undo snackbar (task hidden at once, undo offer, undo calls /uncomplete with the returned version, refused completion comes back with a reason, timeout dismisses) | `TaskCompleterTest` (9 tests, incl. double submit, reverse-order answers, refused undo, retry); `NextViewModelTest.completing hides the task and offers undo`, `the task is hidden while the server answers ...`, `a failed undo ...`, `dismissing an older snackbar keeps the newer one`; `TodayViewModelTest.completing hides the task and offers undo`, `a completed task leaves its section and a refused one comes back`; `NextScreenTest.the undo snackbar reopens the task`, `an undo offer that times out is dismissed, not undone`, `a failure shows in the snackbar`, `the offline and refused messages show, ...`; `TodayScreenTest.the undo snackbar reopens the task`, `a refused completion shows why`; `NextRouteTest.completing hides the task, and undo in the snackbar brings it back`; `TodayRouteTest.completing empties the section, and undo brings the task back` | F | Pass |
| Completion against the server (If-Match with the cached version, reload Next+Today, 404/409/412 conflict, other 4xx failed, 5xx offline without reload, unreadable answer failed) | `TaskCompletionRepositoryTest` (9 tests, MockWebServer + real Room-backed `TaskRepository` from `GtdHarness`) | F | Pass |
| Screens are wired into the app (Robolectric + Hilt, real activity) | `NextScreenInAppTest.the start screen Today is the real Today view`, `the Next tab is the real Next view`; placeholder fallback outside Hilt: `NextScreenTest/TodayScreenTest.outside a Hilt activity ...` | F | Pass |
| AC2: `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.next.*'` passes covering filters with Robolectric/Compose UI tests; same for `gtd.today` | all of the above | F | Pass |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.next.*' --tests 'ch.sonensei.cockpit.gtd.today.*'` (run from the repository root as `android/gradlew -p android ...`)

Result:

Pass. BUILD SUCCESSFUL. 95 tests, 0 failures, 0 errors, 0 skipped. Fresh JUnit XML timestamps 2026-10-21T08:45:43Z to 08:46:14Z: NextFilterTest 6, NextRouteTest 2, NextScreenInAppTest 2, NextScreenTest 22, NextViewModelTest 18, TaskCompleterTest 9, TaskCompletionRepositoryTest 9, TodayRouteTest 1, TodayScreenTest 11, TodayViewModelTest 15. `ktlintTestSourceSetCheck` ran and passed. `ktlintMainSourceSetCheck` was up to date and passed.

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport` (runs the whole `testDebugUnitTest` suite)

Result:

Pass. 572 tests, 0 failures, 0 skipped.

## .taskcheck

Result:

Fail. `sh scripts/check.sh` failed at the last step. TFP gate tests passed. Server `./gradlew check` passed. Web check passed (106 files, 2095 tests). Android `ktlintCheck testDebugUnitTest` passed. Android `lintDebug` FAILED with 2 errors in production files this task added (TW-1, TW-2). The test edits do not cause this: lint reports only `src/main` code and resources, which this run did not change.

## Bugs found

### Snackbar reads resources through LocalContext (lint error, fails .taskcheck)

Finding ID:

`TW-1`

Test:

`.taskcheck` step `android: ktlint + unit tests + lint` (`./gradlew lintDebug`, check `LocalContextResourcesRead`)

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/gtd/next/TaskListUi.kt:148`

Requirement:

AGENTS.md: "Lint is part of the contract: fix warnings, don't suppress them". `tasks.py done` runs `.taskcheck`, which must pass.

Expected behavior:

`lintDebug` passes. `CompletionSnackbarEffect` gets its `Resources` in a configuration-aware way (lint suggests `LocalResources.current`).

Actual behavior:

`TaskListUi.kt:148: Error: Reading Resources using LocalContext.current.resources is not configuration-aware and may return stale values if the Configuration changes [LocalContextResourcesRead]`

Severity:

High (blocks `.taskcheck` and so `tasks.py done`)

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

### Filtered count string should be a plural (lint error, fails .taskcheck)

Finding ID:

`TW-2`

Test:

`.taskcheck` step `android: ktlint + unit tests + lint` (`./gradlew lintDebug`, check `PluralsCandidate`)

Production source:

`android/app/src/main/res/values/strings_next_today.xml:27` (`next_count_filtered`, used in `NextScreen.kt:127`)

Requirement:

AGENTS.md lint contract; ARCHITECTURE.md "All user-facing strings are externalized (i18n-ready)".

Expected behavior:

`lintDebug` passes. "%1$d of %2$d next actions" is a `<plurals>` resource, read with `pluralStringResource` and the total as its quantity.

Actual behavior:

`strings_next_today.xml:27: Error: Formatting %d followed by words ("next"): This should probably be a plural rather than a string [PluralsCandidate]`

Severity:

High (blocks `.taskcheck`)

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

After the fix, `NextScreenTest.shows the active filter and a way to clear it` ("1 of 2 next actions") and `a filter on an empty Next list ...` ("0 of 0 next actions") should still pass with the same English text.

## Coverage

Status: verified (whole-file and changed-line, for the 6 Kotlin files in scope). The test command passed. The report was freshly generated on a stable snapshot.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 477/481 executable added lines (99.2%). I measured it by intersecting JaCoCo `<line>` entries (`ci > 0`) with the `+` hunks of `git diff -U0 44de27f6..HEAD` per file (script `.tmp/tw/changed_lines.py`, Git-ignored). Each of the 6 files was written or replaced wholesale by the task, so changed-line coverage equals whole-file line coverage. Changed-branch coverage is not inferred.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-next-today`. Base `44de27f6a4147ff3ccabc0ef3acc6c2cd968b92f`, HEAD `6f721d1f98c86d6de60b7171d5784d306524b885`. The parent supplied the task commits 47a1bca1, 6a264214, 6f721d1f, and they match `git log 44de27f6..HEAD`. File scope is `git diff --name-only 44de27f6..HEAD -- android/app/src/main`, consistent with the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): the sha256 of the per-file sha256 list of the 10 test files and 6 production files is `fafeff662cfb232cb784d73e894c107114c048667607838cd397c4450919d50f`. The list was taken before and after the coverage run and is identical. Per-file test hashes: NextFilterTest `b713fb2c…`, NextRouteTest `3afa9468…`, NextScreenInAppTest `0175bd9f…`, NextScreenTest `ac5cf5a2…`, NextViewModelTest `01b35696…`, TaskCompleterTest `18889172…`, TaskCompletionRepositoryTest `9c27b150…`, TodayRouteTest `2ee00263…`, TodayScreenTest `4fbb6bab…`, TodayViewModelTest `1087469f…`. Production files are unchanged from HEAD.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/{NextScreen,NextViewModel,TaskCompletion,TaskListUi}.kt`, `android/app/src/main/java/ch/sonensei/cockpit/gtd/today/{TodayScreen,TodayViewModel}.kt`. Excluded: `android/app/src/main/res/values/strings_next_today.xml` (resource, not instrumented by JaCoCo; covered indirectly by the screen tests asserting its texts). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo through AGP 9.4.1 `enableUnitTestCoverage` (Gradle `jacoco` plugin, Gradle 9.8.1; the JaCoCo version is not pinned in the build). Command `android/gradlew -p android createDebugUnitTestCoverageReport` from the repository root (same as `cd android && ./gradlew createDebugUnitTestCoverageReport`). Test selection: the whole debug unit suite (572 tests).
- Run start/completion time and exit status: 2026-10-21T08:46:22Z to 08:47:51Z, exit 0 (BUILD SUCCESSFUL; `:app:testDebugUnitTest` and `:app:createDebugUnitTestCoverageReport` both executed).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 `2debd58a9269e673696bd1b52a640e7ca9a9870820de60993d1566742516622d`, JaCoCo session `dell26-cf0ae10e` (start 1792572384034, dump 1792572468977), mtime 2026-10-21 10:47:51 +0200.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The session id, report hash and mtime differ from the previous run in this session (`dell26-918edf9c`, `f165852c…`), and the snapshot is identical before and after the run.
- Final run after latest test additions and production fixes: yes for the test additions. No production fixes have been made yet: TW-1 and TW-2 are open, so coverage must be re-measured after the fixer changes `TaskListUi.kt` / `NextScreen.kt`.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. "Statements" is the JaCoCo INSTRUCTION counter (JaCoCo has no statement counter).

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `gtd/next/NextScreen.kt` | 1815/1886 (96.2%) | 59/60 (98.3%) | 46/55 (83.6%) | 154/155 (99.4%) |
| `gtd/next/NextViewModel.kt` | 582/587 (99.1%) | 42/42 (100%) | 33/33 (100%) | 60/60 (100%) |
| `gtd/next/TaskCompletion.kt` | 609/626 (97.3%) | 18/24 (75.0%) | 34/35 (97.1%) | 62/64 (96.9%) |
| `gtd/next/TaskListUi.kt` | 1084/1090 (99.4%) | 62/69 (89.9%) | 13/13 (100%) | 84/84 (100%) |
| `gtd/today/TodayScreen.kt` | 851/882 (96.5%) | 32/33 (97.0%) | 15/19 (78.9%) | 70/71 (98.6%) |
| `gtd/today/TodayViewModel.kt` | 370/375 (98.7%) | 30/30 (100%) | 21/21 (100%) | 47/47 (100%) |
| **Total (summed counters, 6 files)** | 5311/5446 (97.5%) | 243/258 (94.2%) | 162/176 (92.0%) | 477/481 (99.2%) |

The total covers all 6 Kotlin files in scope. Excluded: `strings_next_today.xml` (not instrumentable). No scoped file is missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `NextScreen.kt:241` (filter active and total 0) | Empty state when a filter is set but the Next list itself is empty | Covered now: `NextScreenTest.a filter on an empty Next list says the list is empty, ...` (was a missed branch in the first run) |
| `NextViewModel.kt:63` (refreshedAt null but cached tasks) | Cached list without a refresh time must show, not "Loading" | Covered now: `NextViewModelTest.cached tasks without a refresh time show at once ...` |
| `NextScreen.kt:61`, `TodayScreen.kt:54` (`viewModel = hiltViewModel()` default-argument branch) | Hilt wiring of the route | Left: the real Hilt path is exercised by `NextScreenInAppTest` (both tabs render their real screens). The missed instructions are the synthetic `$default` mask branch. Not material |
| `NextScreen.kt:70-79`, `TodayScreen.kt:63-67` (default no-op lambdas of `NextCallbacks` / `TodayCallbacks`) | None: test/preview convenience defaults | Left: never invoked by design. Not material |
| `TaskCompletion.kt:120,123,125,134,136,137` (`CompletionResult.Reopened -> Unit` after complete, `Completed -> Unit` after undo) | None: exhaustive-`when` arms the repository never returns | Left: unreachable through the repository contract. Not material |
| `TaskCompletion.kt:81` (2 instructions of the `CONFLICTS` companion) | Conflict status mapping | The behavior is covered (404/409/412 tests). The missed instructions are synthetic companion init. Not material |
| `TaskListUi.kt:55,79` (modifier default branches) | None | Left. Not material |
| `TaskListUi.kt:149` (LaunchedEffect coroutine state-machine branches), `:172` (ActionPerformed on a `Failed` snackbar) | Snackbar action handling | Undo action, undo timeout, and failure timeout are all tested. A `Failed` snackbar has no action label, so ActionPerformed cannot occur. Not material |
| `TodayViewModel.kt:40`, `NextViewModel.kt:61` (`today = LocalDate.MIN` default) | None | Left. Not material |

## Unable to test

- Behavior on a real device or emulator (instrumented `androidTest`) was not run. The criterion names Robolectric/Compose UI tests, and those ran. No follow-up is needed for this task.
- Production fixes for TW-1/TW-2 cannot be validated here, because the test-writer may not change production code. After the fix, re-run `.taskcheck`, the focused command and the coverage command.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion.

The acceptance criteria themselves are well covered (95 passing focused tests, whole-file line coverage 99.2%). `.taskcheck` fails on 2 Android lint errors in task-added production code (TW-1 `TaskListUi.kt:148`, TW-2 `strings_next_today.xml:27`). They must be fixed before `tasks.py done`.
