# Test Report

Date: 2026-10-26

Task: and-gtd-load-error - Android: show first-load errors in GTD lists (TaskRepository reports refresh failure)

Scope: `git diff 381f80f8943934aa98b631b8eef0d9340b8b6df3..eb5e289d0 -- android` (base and final revision given by the parent; HEAD `eb5e289d0`, branch `main`). The task commits are `4e1aa4148` (implementation), `d97f0f2c8` (tests and the first test/review reports) and `eb5e289d0` (the fixer's fix for review CR-1, see `reviews/and-gtd-load-error/fixes.md`). This report re-checks the final code. The fix diff `git diff d97f0f2c8..eb5e289d0 -- android` changes `next/TaskListUi.kt` (new `LoadFailedBanner`), `today/TodayScreen.kt` (shows the banner above the sections), `today/TodayViewModel.kt` (`firstLoadFailed = refreshedAt == null && loadFailed`), and adds 3 tests in `TodayViewModelTest` / `TodayScreenTest`. When this report was written, the working tree had no source or test changes. The only uncommitted file was `reviews/and-gtd-load-error/review.md`, which is not a test input.

Production files in scope (all under `android/app/src/main/java/ch/sonensei/cockpit/gtd/`):
- `data/GtdModels.kt`, `data/GtdRefresh.kt`, `data/GtdRepositories.kt`
- `next/NextScreen.kt`, `next/NextViewModel.kt`, `next/TaskListUi.kt`
- `today/TodayScreen.kt`, `today/TodayViewModel.kt`

Requirements:
- `tasks/and-gtd-load-error.md` acceptance criteria and the recorded assumption (the error replaces only the never-loaded state; a cached list stays without a banner when a reload fails; a running load clears the failure, so a retry shows Loading; reuse of `lists_error_failed` / `lists_retry`)
- Review finding CR-1 (`reviews/and-gtd-load-error/review.md`) and the fix description in `reviews/and-gtd-load-error/fixes.md`
- KDoc of `GtdConnectivity.failure` / `refreshing` / `clearing`, `Cached.failure` and `TodayUiState.firstLoadFailed`
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`, and the parent's instructions

## Test files changed

The test-writer added no tests in this pass and changed no files except this report. The task's test files at `eb5e289d0` are:

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/GtdRefreshSupportTest.kt` (7 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/TaskRepositoryTest.kt` (6 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/GtdCacheConsistencyTest.kt` (3 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/ProjectContextRepositoryTest.kt` (1 test)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/NextViewModelTest.kt` (6 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/NextScreenTest.kt` (4 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/NextRouteTest.kt` (1 test)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/today/TodayViewModelTest.kt` (6 tests by the test-writer + 2 by the fixer)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/today/TodayScreenTest.kt` (3 tests by the test-writer + 1 by the fixer)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/today/TodayRouteTest.kt` (1 test)

The fixer's test diff only adds code (37 insertions, 0 deletions). No existing test was changed, weakened or disabled. Test data is fictitious.

## Tests added or updated

Count: 0 added in this pass. The task has 41 tests in total: 38 from the first test-writer pass (listed in that report, commit `d97f0f2c8`) and 3 from the fixer.

The fixer's tests for CR-1, reviewed here:
- `TodayViewModelTest`:
  - `a failed first load of the Today view is an error even when the Next view has tasks for today`: TODAY has `Failed(403)` and no `refreshedAt`, and NEXT has a scheduled task. The SCHEDULED section is kept, `loadFailed` and `firstLoadFailed` are true, and `loading` is false. This is the CR-1 regression case.
  - `a failed first load of the Next view is an error even when the Today view has tasks`: the mirror case (NEXT `Failed(400)`, TODAY has overdue and due tasks).
- `TodayScreenTest`:
  - `a failed first load of one view shows a banner with a retry above the other view's sections`: `TaskListTags.ERROR` holds the text "The server could not send this list. Try again." The list, the SCHEDULED section and the task are shown, and there is no Loading. Clicking `TaskListTags.RETRY` calls `retry`.

Before the fix, both view-model tests would fail: the old getter needed `sections.isEmpty()`, so `firstLoadFailed` was false with sections. The screen test would fail too, because no `ERROR` node existed while sections were shown. The existing tests still hold under the new rule. In the failed-reload tests `refreshedAt` is set, and the full-error tests have no sections. `retry` wiring through `TodayRoute` (`TodayRouteTest`) is the same callback the banner uses.

Assessment: the fix is tested enough. No important behavior is missing, so the test-writer added no tests.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1a: gtd/data exposes the last refresh failure (4xx / unparsable / cache write failure set it; success, offline, a new load and logout clear it; a load spanning a logout does not set it; per view) | the 7 `GtdRefreshSupportTest` tests, 6 `TaskRepositoryTest` tests, 3 `GtdCacheConsistencyTest` tests and 1 `ProjectContextRepositoryTest` test | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*' lintDebug` (run by the parent on `eb5e289d0`) | Pass |
| AC1b: Next and Today show an error with a retry button instead of endless loading; retry reloads | 6 `NextViewModelTest`, 8 `TodayViewModelTest`, 4 `NextScreenTest`, 4 `TodayScreenTest`, 1 `NextRouteTest` and 1 `TodayRouteTest` tests | same | Pass |
| CR-1: Today shows the error with a retry when one of its two lists never loaded because its load failed, also when the other list has sections | the 2 new `TodayViewModelTest` tests and the new `TodayScreenTest` test above | same | Pass |
| AC2: `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*'` passes, with tests for the failure and retry paths | all of the above plus the existing gtd suites | same | Pass (450 gtd tests, 0 failures) |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*' lintDebug` (run by the parent on HEAD `eb5e289d0`; the test-writer cannot run Gradle in its sandbox)

Result:

Pass: BUILD SUCCESSFUL, 450 gtd tests, 0 failures. ktlintCheck and lintDebug passed (as reported by the parent). That is 447 from the first pass plus the fixer's 3.

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport` (parent; runs the whole Android debug unit test suite with coverage)

Result:

Pass: BUILD SUCCESSFUL in 1m47s on `eb5e289d0` (as reported by the parent).

## .taskcheck

Result:

Not run by the test-writer: `sh scripts/check.sh` needs Gradle, which this sandbox cannot run. The parent runs it before `done`.

## Bugs found

None.

Observations, not defects (neither the task nor SPEC defines these cases, and the result is reasonable, so no test pins them):
- `firstLoadFailed` (Next and Today) does not check `offline`. A view's first load can be rejected (4xx) while the shared offline flag is set by another view's load. The screen then shows the "Offline: nothing loaded yet." banner together with the error and retry.
- Today combines the two views: `refreshedAt` is null while either view was never loaded, and `loadFailed` is true when either view has a failure. Take the case where TODAY was loaded and its reload just failed, while NEXT's first load is still running (no failure yet). Then `firstLoadFailed` is true, and the banner shows above the TODAY sections until NEXT finishes loading. The banner then belongs to the reload of a cached list, which the assumption says should show no error. This lasts only while NEXT's first load runs, and the retry it offers reloads both lists, so it is minor. It is reported to the parent as a possible follow-up.

## Coverage

Status: verified. Whole-file JaCoCo counters come from a fresh, successful run by the parent on HEAD `eb5e289d0`, which has no uncommitted source or test changes.

Metric: whole-file coverage of task-changed production files. This includes old code in those files and hits from pre-existing tests.
Changed-line coverage: not measured as a metric. Instead, the test-writer read the per-line counters in report.xml for the fix's changed lines. `TodayViewModel.kt:51` (`firstLoadFailed`) has 0 missed of 10 instructions and 4/4 branches. `TodayScreen.kt:93-95` (banner condition and call) has 0 missed instructions and 6/6 branches. `TaskListUi.kt:96-107` (`LoadFailedBanner`) has 0 missed, except the default `modifier` on lines 97-98 (3 instructions, 1 Compose skip branch). The previous pass's gap on `TodayViewModel.kt:48` (the `sections.isEmpty()` short-circuit) is gone because the fix removed that condition.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-gtd-load-error`. Base `381f80f8943934aa98b631b8eef0d9340b8b6df3` (from the parent). Measured HEAD `eb5e289d0` (from the parent). Task-owned commits are `4e1aa4148`, `d97f0f2c8` and `eb5e289d0`, listed in the git log on top of the base `381f80f89` heartbeat. File scope: `git diff --stat 381f80f89..eb5e289d0` under `android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `eb5e289d0`. No uncommitted source or test files (`git status --short` lists only `reviews/and-gtd-load-error/review.md`).
- Exact included files: the 8 production files listed under Scope, all measured. No renames, deletions or exclusions, and no file is missing from the report.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `./gradlew createDebugUnitTestCoverageReport` in `android/` (parent); all Android debug unit tests.
- Run start/completion time and exit status: BUILD SUCCESSFUL in 1m47s. report.xml was written 2026-10-26 01:56:59 +0100.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 `aaefef7abae61e17c5e66267bceac7ce58f52e2066438abcf23b091045e44208`. The test-writer recomputed this hash, and it matches the parent's.
- Evidence outputs are fresh and inputs stayed unchanged: the hash differs from the previous pass's report (`2c437b3f...`). The report covers `LoadFailedBanner`, which exists only at `eb5e289d0`, so it was generated from the final code. No source or test file changed after the run.
- Final run after latest test additions and production fixes: yes. It includes the CR-1 fix and its 3 tests, and the test-writer added nothing afterwards.

Values below use covered/total (percentage). JaCoCo has no statement metric, so instructions are shown instead.

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `data/GtdModels.kt` | 508/520 (97.7%) | N/A (0 branches) | 47/51 (92.2%) | 53/53 (100%) |
| `data/GtdRefresh.kt` | 507/511 (99.2%) | 21/22 (95.5%) | 16/16 (100%) | 81/81 (100%) |
| `data/GtdRepositories.kt` | 687/695 (98.8%) | 5/5 (100%) | 34/34 (100%) | 89/89 (100%) |
| `next/NextScreen.kt` | 1905/1984 (96.0%) | 63/64 (98.4%) | 47/57 (82.5%) | 160/161 (99.4%) |
| `next/NextViewModel.kt` | 645/650 (99.2%) | 49/50 (98.0%) | 37/37 (100%) | 59/59 (100%) |
| `next/TaskListUi.kt` | 1389/1401 (99.1%) | 64/73 (87.7%) | 19/19 (100%) | 105/105 (100%) |
| `today/TodayScreen.kt` | 948/987 (96.0%) | 40/41 (97.6%) | 16/21 (76.2%) | 75/76 (98.7%) |
| `today/TodayViewModel.kt` | 463/468 (98.9%) | 38/38 (100%) | 26/26 (100%) | 54/54 (100%) |
| Total (summed counters; all 8 scoped files measured, none missing or excluded) | 7052/7216 (97.7%) | 280/293 (95.6%) | 242/261 (92.7%) | 676/678 (99.7%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `next/NextViewModel.kt:69` (`firstLoadFailed`, 1 of 6 branches) | The `total == 0` short-circuit is never evaluated with `refreshedAt == null` and a non-empty list | Justified gap: that state (cached rows without a refresh time) shows the list before `firstLoadFailed` is read. The getter's required outcomes are all asserted |
| `next/TaskListUi.kt:81-82`, `97-98` (default `modifier` of `LoadFailed` and `LoadFailedBanner`, 3 instructions and 1 Compose skip branch each) | None; both screens always pass a modifier | Non-material |
| `next/TaskListUi.kt:62, 118, 188, 211` | Pre-existing code (offline banner, task row) from earlier tasks, moved down by the new banner | Outside this task's change |
| `data/GtdRefresh.kt:114, 117` (`when (result)`, 1 branch / 4 instructions) | Pre-existing exhaustive `when` over the sealed `RefreshResult` (synthetic no-match path) | Not task code, not reachable |
| `data/GtdRepositories.kt:35, 39` (8 instructions) | Coroutine bookkeeping of the pre-existing `channelFlow` / `launch` in `cachedView` | Non-material |
| `next/NextScreen.kt:61-80`, `today/TodayScreen.kt:56-70` (missed methods: default lambdas of `NextCallbacks` / `TodayCallbacks`, `hiltViewModel()` default of the routes) | The `retry` default `{}` is never called. The real `retry` wiring is covered by the two route tests | Non-material: defaults for tests and previews |
| `data/GtdModels.kt` (4 methods) | Generated data-class members not called by tests | Non-material |

## Unable to test

- `.taskcheck` and all Gradle runs: the test-writer cannot run Gradle here. All results above were run and reported by the parent.
- A real device's full disk: simulated with an SQLite trigger abort, as in the existing tests.
- The previous pass's unidentified failure on the first coverage attempt did not recur. The parent's runs on `eb5e289d0` both passed on the first attempt.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The CR-1 fix is covered by the fixer's 3 regression tests, which would fail without the fix. All changed lines of the fix are covered except the non-material default `modifier` of `LoadFailedBanner`. The test-writer added no tests in this pass. Parent results on `eb5e289d0`: `ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*' lintDebug` passed with 450 gtd tests and 0 failures, and `createDebugUnitTestCoverageReport` passed. Task-scoped whole-file coverage totals 676/678 lines (99.7%) and 280/293 branches (95.6%). One minor observation (a transient banner on Today) is reported for the parent to decide on. `.taskcheck` is still to be run by the parent.
