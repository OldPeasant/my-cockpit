# Test Report

Date: 2026-10-26

Task: and-gtd-load-error - Android: show first-load errors in GTD lists (TaskRepository reports refresh failure)

Scope: `git diff 381f80f8943934aa98b631b8eef0d9340b8b6df3..4e1aa41481f9aa7fb3223e6b2a6ecf0f1e12c832 -- android` (base and task commit given by the parent). Task commit given as `43ac9611f26c6edfbdbc5bb22a1a49745014c0c6`. After a rebase it is `4e1aa41481f9aa7fb3223e6b2a6ecf0f1e12c832` (HEAD, branch `main`). The two differ only in `tasks/and-gtd-load-error.md` (`git diff --stat 43ac9611f 4e1aa4148`). When this report was written, the working tree held only the test-file changes listed below, uncommitted.

Production files in scope (all under `android/app/src/main/java/ch/sonensei/cockpit/gtd/`):
- `data/GtdModels.kt`, `data/GtdRefresh.kt`, `data/GtdRepositories.kt`
- `next/NextScreen.kt`, `next/NextViewModel.kt`, `next/TaskListUi.kt`
- `today/TodayScreen.kt`, `today/TodayViewModel.kt`

Requirements:
- `tasks/and-gtd-load-error.md` acceptance criteria and the recorded assumption (the error replaces only the never-loaded state; a cached list stays without a banner when a reload fails; a running load clears the failure, so a retry shows Loading; reuse of `lists_error_failed` / `lists_retry`)
- KDoc of `GtdConnectivity.failure` / `refreshing` / `clearing` and `Cached.failure` (they spell out the intended behavior)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`, and the parent's instructions

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/GtdRefreshSupportTest.kt` (7 tests, 2 private helpers)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/TaskRepositoryTest.kt` (6 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/GtdCacheConsistencyTest.kt` (3 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/ProjectContextRepositoryTest.kt` (1 test)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/NextViewModelTest.kt` (6 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/NextScreenTest.kt` (4 tests; the `show()` helper now also passes a `retry` callback that records `"retry"`)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/NextRouteTest.kt` (1 test)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/today/TodayViewModelTest.kt` (6 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/today/TodayScreenTest.kt` (3 tests; the `show()` helper now also passes a `retry` callback)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/today/TodayRouteTest.kt` (1 test)

The tests only add code (`git diff --stat`: 557 insertions, 0 deletions). No existing test was changed, weakened or disabled. Test data is fictitious.

## Tests added or updated

Count: 38 added.

Data layer, `GtdConnectivity` alone (`GtdRefreshSupportTest`, plain JVM, `CompletableDeferred` gates instead of delays):
- `a rejected or unreadable load is kept as the failure of its view`: 403 gives `Failed(403)`, and a `SerializationException` gives `Failed(null)`. Neither sets offline.
- `a successful load clears the failure of its view only`: other views keep their failure. A view that was never loaded has none.
- `an unreachable server is no failure and replaces an earlier one`: an `IOException` or a 503 clears an earlier 400 failure.
- `a running load hides the earlier failure until it fails again`: while a retry runs, `failure` is null, and the retry's 409 is recorded afterwards.
- `clearing drops the failures of every view`
- `a rejected load that outlives a clearing does not record its failure`: a load that starts after the clearing records its failure again.
- `an answer dropped because of a clearing is neither stored nor recorded as a failure`: covers the `SessionEnded` path.

Data layer, through the repositories (Robolectric + MockWebServer + in-memory Room):
- `TaskRepositoryTest`:
  - `a rejected first load is reported as the view's failure, not as offline`: observed `Cached` has `failure = Failed(403)`, empty data, null `refreshedAt`, and is not offline.
  - `an unreadable first load is reported as a failure without a status`
  - `a later successful refresh clears the failure and shows the loaded tasks`: observed view goes from failure to loaded rows, with failure null and a `refreshedAt`.
  - `an unreachable server is reported as offline, not as a failure`: a 502 after a 400 clears the failure, and with the server closed the observed view is offline with no failure.
  - `logout clears the failure an observed view shows`: `GtdDataModule.gtdCache(...).clear()`.
  - `a failure of one view does not show in another`: NEXT failed, TODAY refreshed.
- `GtdCacheConsistencyTest` (reuses the file's `holdFirstAnswer` / `awaitRequests` helpers):
  - `a rejected load still running at logout does not show its failure in the next session`
  - `an answer dropped because of a logout is not shown as a failure`
  - `a failing cache write is reported as the view's failure`: SQLite trigger abort, the existing full-disk simulation.
- `ProjectContextRepositoryTest`:
  - `observed projects and contexts each report their own failed load`: checks the `key` added to `cachedView` for projects and contexts, and that a failure does not leak to other keys.

View models (mockk repositories, `UnconfinedTestDispatcher`):
- `NextViewModelTest`:
  - `a loaded list without a failure is neither failed nor loading`
  - `a failed first load is an error instead of loading`
  - `a failed reload keeps the cached tasks instead of the error`
  - `a failed reload of a list loaded empty before is the empty list, not the error`
  - `retry reloads the Next list, which shows loading again until its answer`: `refresh(NEXT)` is called once. The state is loading while the retry runs and shows the error again when the retry fails.
  - `retry reloads only the Next list`
- `TodayViewModelTest`:
  - `loaded lists without a failure are neither failed nor loading`
  - `a failed first load of the Today view is an error instead of loading`
  - `a failed first load of the Next view is an error too, even when the Today view loaded empty`
  - `a failed reload keeps the sections instead of the error`
  - `a failed reload of lists loaded empty before is the empty day, not the error`
  - `retry reloads both views, and the screen shows loading while they run`: `refresh(TODAY)` and `refresh(NEXT)` are each called once, and `INBOX` is never refreshed.

Screens (Compose UI tests, Robolectric):
- `NextScreenTest`:
  - `a failed first load shows the error with a retry instead of loading`: `TaskListTags.ERROR` and the text "The server could not send this list. Try again." are shown, and the title is "Next". There is no "Loading…", no empty message and no list. Clicking `TaskListTags.RETRY` calls `retry`.
  - `while the retry runs the error gives way to loading, then to the list`
  - `a failed reload keeps the cached list without the error`
  - `a failed reload of a list loaded empty before says the list is empty`
- `TodayScreenTest`:
  - `a failed first load shows the error with a retry instead of loading`: clicking Retry calls `retry`.
  - `a failed reload keeps the sections without the error`
  - `a failed reload of a day loaded empty before says the day is empty`
- `NextRouteTest`:
  - `after a failed first load the retry button reloads the Next list`: checks the `NextCallbacks.of(vm)` wiring with a real `NextViewModel`.
- `TodayRouteTest`:
  - `after a failed first load the retry button reloads both lists`: checks the `TodayRoute` wiring of `viewModel::retry`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1a: gtd/data exposes the last refresh failure (4xx / unparsable / cache write failure set it; success, offline, a new load and logout clear it; a load spanning a logout does not set it; per view) | the 7 `GtdRefreshSupportTest` tests, the 6 `TaskRepositoryTest` tests, the 3 `GtdCacheConsistencyTest` tests and the 1 `ProjectContextRepositoryTest` test listed above | `cd android && ./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*'` (run by the parent on HEAD 4e1aa4148 + these uncommitted tests) | Pass |
| AC1b: Next and Today show an error with a retry button instead of endless loading; retry reloads | the 6 `NextViewModelTest` tests, 6 `TodayViewModelTest` tests, 4 `NextScreenTest` tests, 3 `TodayScreenTest` tests, 1 `NextRouteTest` test and 1 `TodayRouteTest` test listed above | same | Pass |
| AC2: `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*'` passes, with tests for the failure and retry paths | all of the above plus the existing gtd suites | same | Pass (447 gtd tests, 0 failures, 0 skipped) |

## Focused test execution

Command:

`cd android && ./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*'` (run by the parent on HEAD `4e1aa41481f9aa7fb3223e6b2a6ecf0f1e12c832` plus the 10 uncommitted test files; the test-writer cannot run Gradle in its sandbox)

Result:

Pass: BUILD SUCCESSFUL, 447 gtd tests, 0 failures, 0 skipped. ktlintFormat reported nothing (as reported by the parent).

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport` (parent; runs the whole Android unit test suite with coverage)

Result:

Pass on the second attempt. The first run ended BUILD FAILED after 2m25s because a test failed. Its output was not captured, and the second run overwrote the results, so the failing test is unknown. The parent suspects the known clock-flaky test, but that is not confirmed. The immediate re-run on the same inputs ended BUILD SUCCESSFUL. A separate `ktlintCheck testDebugUnitTest lintDebug` was not run by the parent at this point.

## .taskcheck

Result:

Not run yet. The test-writer cannot run it (`sh scripts/check.sh` needs Gradle), and the parent runs it before `done`.

## Bugs found

None. All 38 new tests pass against the unchanged production code.

Observation, not a defect: `firstLoadFailed` does not check `offline`. Suppose a view's first load is rejected (4xx) while the shared offline flag is set by another view's load. The screen then shows the "Offline: nothing loaded yet." banner together with the error and retry. Neither the task nor SPEC defines this case, and the result is reasonable (both facts are true), so no test pins it.

## Coverage

Status: verified. Whole-file JaCoCo counters come from a fresh, successful run by the parent on HEAD `4e1aa4148` plus the 10 uncommitted test files. That run was the second attempt; the first attempt failed (see "Full test execution").

Metric: whole-file coverage of task-changed production files. This includes old code in those files and hits from pre-existing tests.
Changed-line coverage: not measured as a metric. Instead, the test-writer compared the per-line counters in report.xml with the task diff. Every changed executable line has 0 missed instructions and 0 missed branches, except the two `firstLoadFailed` getters (1 missed branch each) and the `LoadFailed` default `modifier` parameter (see the gap table).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-gtd-load-error`; base `381f80f8943934aa98b631b8eef0d9340b8b6df3` (from the parent). Measured HEAD `4e1aa41481f9aa7fb3223e6b2a6ecf0f1e12c832`, which is the rebased task commit `43ac9611f` with the same code. File scope: `git diff --name-only 381f80f8..4e1aa4148 -- android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `4e1aa4148` plus the 10 uncommitted test files listed under "Test files changed". No production file was changed during or after the run. The parent reported that ktlintFormat made no changes.
- Exact included files: the 8 production files listed under Scope, all measured. No exclusions: the task changed no resources or generated files. No file is missing from the report.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `cd android && ./gradlew createDebugUnitTestCoverageReport` (parent, `android/`); all Android debug unit tests.
- Run start/completion time and exit status: the first attempt was BUILD FAILED after 2m25s (a test failed, which one is unknown). The immediate re-run was BUILD SUCCESSFUL, and report.xml was written 2026-10-26 01:49:38 +0100.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 `2c437b3fbc06bf8cb3d9a368b8ec96b684937d1d6ade8485f76149562c51b40a`. The test-writer recomputed this hash, and it matches the parent's.
- Evidence outputs are fresh and inputs stayed unchanged: the report comes from the successful re-run after the tests were added. The production code is unchanged since `4e1aa4148`.
- Final run after latest test additions and production fixes: yes. All 38 tests were included, and there were no production fixes.

Values below use covered/total (percentage). JaCoCo has no statement metric, so instructions are shown instead.

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `data/GtdModels.kt` | 508/520 (97.7%) | N/A (0 branches) | 47/51 (92.2%) | 53/53 (100%) |
| `data/GtdRefresh.kt` | 507/511 (99.2%) | 21/22 (95.5%) | 16/16 (100%) | 81/81 (100%) |
| `data/GtdRepositories.kt` | 687/695 (98.8%) | 5/5 (100%) | 34/34 (100%) | 89/89 (100%) |
| `next/NextScreen.kt` | 1905/1984 (96.0%) | 63/64 (98.4%) | 47/57 (82.5%) | 160/161 (99.4%) |
| `next/NextViewModel.kt` | 645/650 (99.2%) | 49/50 (98.0%) | 37/37 (100%) | 59/59 (100%) |
| `next/TaskListUi.kt` | 1221/1230 (99.3%) | 63/71 (88.7%) | 16/16 (100%) | 94/94 (100%) |
| `today/TodayScreen.kt` | 912/951 (95.9%) | 34/35 (97.1%) | 16/21 (76.2%) | 72/73 (98.6%) |
| `today/TodayViewModel.kt` | 467/472 (98.9%) | 39/40 (97.5%) | 26/26 (100%) | 54/54 (100%) |
| Total (summed counters; all 8 scoped files measured, none missing or excluded) | 6852/7013 (97.7%) | 274/287 (95.5%) | 239/258 (92.6%) | 662/664 (99.7%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `next/NextViewModel.kt:69`, `today/TodayViewModel.kt:48` (`firstLoadFailed`, 1 of 6 branches each) | The `total == 0` / `sections.isEmpty()` short-circuit is never evaluated with `refreshedAt == null` and a non-empty list | Justified gap: that state (cached rows without a refresh time) shows the list in both screens before `firstLoadFailed` is read. The getters' required outcomes (first-load failure, failed reload with data, failed reload of an empty loaded list) are all asserted |
| `next/TaskListUi.kt:81-82` (`LoadFailed` default `modifier`, 3 instructions, 1 Compose skip branch) | None; both screens always pass a modifier | Non-material |
| `data/GtdRefresh.kt:114, 117` (`when (result)`, 1 branch / 4 instructions) | Pre-existing exhaustive `when` over the sealed `RefreshResult` (synthetic no-match path) | Not task code, not reachable |
| `data/GtdRepositories.kt:35, 39` (8 instructions) | Coroutine bookkeeping of the pre-existing `channelFlow` / `launch` in `cachedView`; the new combine line is fully covered | Non-material |
| `next/NextScreen.kt:61-80`, `today/TodayScreen.kt:56-70` (missed methods: default lambdas of `NextCallbacks` / `TodayCallbacks`, `hiltViewModel()` default of the routes) | The new `retry` default `{}` is never called. The real `retry` wiring is covered by the two route tests | Non-material: defaults for tests and previews |
| `data/GtdModels.kt` (4 methods) | Generated data-class members not called by tests | Non-material |
| `next/TaskListUi.kt:62, 102, 172, 195` | Pre-existing code (offline banner, task row) from earlier tasks | Outside this task's change |

## Unable to test

- The first coverage attempt failed on an unidentified test. Its output was overwritten, so the test-writer cannot tell whether it was the known flaky clock test or something else. Follow-up: if `.taskcheck` or a later run fails again, capture the failing test name.
- `.taskcheck` was not run by the test-writer (no Gradle). The parent runs it before `done`.
- A real device's full disk: simulated with an SQLite trigger abort, as in the existing tests.

## Summary

Tests added and all validation passed. There are 38 new tests for the failure and retry paths in gtd/data, Next and Today (view models, screens and routes). The parent's focused run passed: `ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*'`, 447 tests, 0 failures, 0 skipped. The coverage run passed on its second attempt; the first attempt failed on an unidentified test. Task-scoped whole-file coverage totals 662/664 lines (99.7%) and 274/287 branches (95.5%). No production defect was found. `.taskcheck` is still to be run by the parent.
