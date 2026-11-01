# Test Report

Date: 2026-11-01

Task: and-logbook - Android: Logbook (completed tasks, reopen)

Scope: task base `8d50027a3173f2e01426abf6611a01bc943dd06c` .. HEAD `df97a5273ddfa610316b23eca4e2fea32159a7cb` (`main` in the agent-2 clone), restricted to `android/`. Task commits: `eb6d01264` (implementation), `5cced38c5` (earlier test-writer tests and report), `70b9a904d` (review report), `df97a5273` (fix for CR-1: the reload pipeline runs only while `uiState` is subscribed). The report also covers the uncommitted test change listed below. This is a re-run on the final code after the CR-1 fix. It replaces the earlier report, which was measured before the fix (and before a rebase that changed the commit hashes).

Requirements:
- `tasks/and-logbook.md`: acceptance criteria and the three recorded assumptions (a detail dialog with Reopen; pages of 50, and a reload loads as many pages as are shown; Logbook in More right after Someday)
- `SPEC.md` §5 (Logbook); `docs/ARCHITECTURE.md` ("Coverage", "Code conventions")
- `reviews/and-logbook/fixes.md` (CR-1 fix and its intended behavior)
- `AGENTS.md` project notes; the parent's instructions; existing test conventions (`ListViewModelsTest`, `ListScreensTest`, `CockpitShellTest`)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/LogbookViewModelTest.kt` (extended, uncommitted)

No existing test was changed, weakened, skipped or removed. No production file was modified.

## Tests added or updated

Count: 3 added in this run. All three cover the lifecycle behavior that the CR-1 fix introduced. The earlier run added 37 tests, and the CR-1 fixer added 2.

- `coming back within the stop timeout, e g after a rotation, does not reload`: the screen resubscribes within the 5 s `WhileSubscribed` timeout, for example after a configuration change. The upstream keeps running, so the first page is requested only once. A later change still reloads.
- `a first load cut off by leaving the screen loads again when shown again, and its late answer is ignored`: the first load is still pending when the screen goes away past the timeout, so it is cancelled. Its late answer does not change the state. On the next subscription the first page loads again, and the state is `loaded` with no error and no spinner.
- `a reopen that finishes after the screen is gone removes the task, and the logbook reloads when shown again`: the reopen runs on `viewModelScope` and still completes. The task leaves the list and the snackbar is set. The reload that the reopen requests is dropped while nobody subscribes. When the screen is shown again it reloads once.

## Requirement coverage

All rows ran in `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` (2026-11-01, about 19:12:30Z to 19:15:30Z). Result: BUILD SUCCESSFUL, 2667 tests, 0 failures, 0 errors, 0 skipped.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Logbook reachable from More, next to Projects/Waiting/Someday (assumed: right after Someday) | `CockpitShellTest.moreListsEverySecondaryDestination`, `CockpitShellTest.logbookOpensFromTheMoreListAndBackReturnsToIt`, `CockpitDestinationTest` (order list) | as above | Pass |
| Lists completed tasks newest first, grouped by completion day (local zone), with project/context | `LogbookViewModelTest` `lists done tasks newest first, grouped by local completion day...`; `LogbookScreenTest` `groups by completion day...`, `an older day shows its full date`, `a project or context that is not loaded is left out of the row`; `LogbookRepositoryTest` `loads a page of done tasks...`, `sends the cursor and skips tasks without a completion time` | as above | Pass |
| Loads more on scroll if the API pages (deduplication, concurrency with reloads) | ViewModel: `loads the next page...`, `a failed next page is reported and can be retried`, `a task on two pages is listed once...`, `a reload over several pages...`, `a reload stops at the last page...`, `a reload that fails on a later page...`, `only one next page...`, `no next page is requested while the first page loads`, `a change while the next page loads cancels it...`, `a next page answering after a running reload...`, `a reload answering after a next page...`, `an older reload answering last...`, `a reload clears the error of a failed next page`; Screen: `a short list with more pages asks...`, `a long list asks for the next page only once scrolled to its end`, `scrolling to the end after a failed page does not retry by itself`, `a next page that failed offline says so...` | as above | Pass |
| The list stays current with task changes while shown, and does no work while not shown (CR-1 fix) | ViewModel: `a reload after a change keeps as many tasks as were loaded`, `nothing is loaded before the screen subscribes`, `changes are ignored once the screen is gone past the stop timeout, and it reloads when shown again`, `coming back within the stop timeout, e g after a rotation, does not reload` (new), `a first load cut off by leaving the screen loads again when shown again...` (new), `a reopen that finishes after the screen is gone...` (new); Repository: `the logbook is told to load at once` | as above | Pass |
| Tapping a task opens it (assumed: detail dialog); Reopen uncompletes it and it leaves the Logbook | ViewModel: `reopen removes the task, reports it and reloads`, `a refused reopen keeps the task...`, `a conflicting reopen keeps the task, reports the conflict and reloads`, `a failed reopen keeps the task and does not reload`, `reopening one task keeps another task's dialog open`, `a reload closes the opened task when it left the logbook...`, `the screen callbacks reach the view model`; Repository: `reopen uncompletes the task with its version and maps refusals`, `reopening a task trashed elsewhere is a conflict...`, `a reopened task is back on its list...`; Screen: `the opened task shows its details...`, `the detail dialog cannot reopen while busy or read-only`, `a task without project, contexts or notes...`, `a reopened task is confirmed in a snackbar, then dismissed`, `a conflicting reopen says...` | as above | Pass |
| Loading, empty and error states follow the existing GTD list screens (shared `LiveListTags`, stale banner, read-only) | Screen: `loading, empty and error states`, `reopen is disabled while busy or read-only`; ViewModel: `an empty logbook is loaded and empty`, `a failed first load is an error and retry loads`, `retry after a failed first load shows the spinner again...`, `a failed reload keeps the list read-only`; Repository: `an unreachable server and a refusal are load errors`, `a malformed page is a failed load` | as above | Pass |
| All new UI strings in English and German | `LogbookStringsTest` (3 tests); `LogbookScreenTest` `german strings`, `a reopened task is confirmed in German`, `german day headers, empty state and next page messages`; `lintDebug` (`MissingTranslation`) | as above | Pass |
| Unit and Compose UI tests cover the criteria; `./gradlew ktlintCheck testDebugUnitTest lintDebug` passes | all of the above | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` (run together with `createDebugUnitTestCoverageReport`) | Pass |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.lists.LogbookViewModelTest'`

Result:

Pass (32 tests, 0 failures, 0 errors). The first full run then failed in ktlint on one of the new lines (`chain-method-continuation` in a test assertion). I rewrote that assertion without changing what it checks. The final full run below passed.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport`

Result:

Pass: BUILD SUCCESSFUL, 2667 tests, 0 failures, 0 errors, 0 skipped. Logbook suites: ViewModel 32, Repository 8, Screen 17, Strings 3. ktlint and lint are clean.

## .taskcheck

Result:

Not run in this pass. Its Android part (`ktlintCheck testDebugUnitTest lintDebug`) is the full command above, and this change does not touch the server or web parts. The parent runs `sh scripts/check.sh` before `done`.

## Bugs found

None. No test showed behavior that violates the acceptance criteria, the recorded assumptions or the intended CR-1 behavior.

Observation (not a defect against the criteria, no fix required): a reload only loads as many pages as were shown when it started. So if a "load more" page answers while that reload is still running, the reload's result drops the newly appended page again. The footer comes back, and the next scroll loads the page again. The test `a reload answering after a next page leaves a consistent list that can load more again` pins this as consistent behavior. The CR-1 fix does not change it.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 320/332 executable added or modified lines (96.4%). Method: intersect the added/modified lines of `git diff -U0 8d50027a3..HEAD -- android/app/src/main/java` with the JaCoCo XML `line` elements of the same report (covered = `ci > 0`). Changed-branch coverage was not measured. The 12 uncovered changed lines are `LogbookViewModel.kt:93,112` and `LogbookScreen.kt:78,88,95-101,170`, explained in the table below.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-logbook`. Base `8d50027a3173f2e01426abf6611a01bc943dd06c` and the task commits come from the parent. Measured HEAD is `df97a5273ddfa610316b23eca4e2fea32159a7cb`. `git log 8d50027a3..HEAD` lists exactly the 4 and-logbook commits and nothing from another task.
- Source/test snapshot identifier (including uncommitted inputs): production code is HEAD `df97a5273`, with no uncommitted production changes. The task production files' sha256 values were identical before and after the run: `ListUi.kt` 92679c87..., `ListViewModels.kt` d834fc06..., `LogbookRepository.kt` 6b694bd1..., `LogbookScreen.kt` c5396f49..., `LogbookViewModel.kt` fccd033e..., `CockpitShell.kt` 64b4f10d..., `CockpitDestination.kt` 97b0d7bf.... The only uncommitted test input is `LogbookViewModelTest.kt`, sha256 2c8f451cad1fcbbe99869270d94bed3d3b3afb614a664fd3b8b733181be330cb (after the ktlint fix, unchanged after the final run). The other task test files are committed at HEAD.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/{LogbookRepository,LogbookViewModel,LogbookScreen,ListUi,ListViewModels}.kt`, `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt`, `android/app/src/main/java/ch/sonensei/cockpit/ui/navigation/CockpitDestination.kt`. Excluded: `res/values{,-de}/strings_projects_lists.xml` (resources, which cannot be instrumented; covered by `LogbookStringsTest` and lint), the `reviews/` reports and test files. No renames or deletions. No missing or unmeasured files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (the Gradle `jacoco` plugin's default tool version, Gradle 9.8.1). Command: `./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport`, run in `android/` on the full debug unit test suite.
- Run start/completion time and exit status: about 2026-11-01T19:12:30Z to 19:15:30Z (Gradle reported 2m 52s). BUILD SUCCESSFUL, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 61b8aee205f22bcf01407916cea04ed4c7dbb1a565ebe835cbaa602bafc55f62, written 2026-11-01T19:15:28Z (during this run).
- Evidence outputs are fresh and inputs stayed unchanged: yes. The test file changed after the previous run, so Gradle re-executed `testDebugUnitTest` (11 tasks executed) and regenerated the report. The report's write time is inside the run window, and its hash differs from the earlier report (986b75cd...). The production hashes are the same before and after the run.
- Final run after latest test additions and production fixes: yes. The run comes after the CR-1 fix (`df97a5273`) and after the last test edit.

Values below use covered/total (percentage). JaCoCo's INSTRUCTION counter stands in for statements. For the 4 files the task changed only slightly, the numbers include old code and pre-existing tests.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `gtd/lists/LogbookRepository.kt` | 259/268 (96.6%) | 13/15 (86.7%) | 15/15 (100.0%) | 40/40 (100.0%) |
| `gtd/lists/LogbookViewModel.kt` | 1129/1144 (98.7%) | 55/58 (94.8%) | 38/39 (97.4%) | 118/120 (98.3%) |
| `gtd/lists/LogbookScreen.kt` | 2166/2332 (92.9%) | 98/120 (81.7%) | 45/54 (83.3%) | 158/168 (94.0%) |
| `gtd/lists/ListUi.kt` (1 line changed) | 1363/1384 (98.5%) | 50/59 (84.7%) | 30/35 (85.7%) | 122/122 (100.0%) |
| `gtd/lists/ListViewModels.kt` (1 line changed) | 592/601 (98.5%) | 13/14 (92.9%) | 37/38 (97.4%) | 77/77 (100.0%) |
| `ui/CockpitShell.kt` (2 lines changed) | 1580/1615 (97.8%) | 80/93 (86.0%) | 42/42 (100.0%) | 132/134 (98.5%) |
| `ui/navigation/CockpitDestination.kt` (2 lines changed) | 363/363 (100.0%) | 9/9 (100.0%) | 13/13 (100.0%) | 36/36 (100.0%) |
| Aggregate (summed counters; all 7 files measured, none excluded) | 7452/7707 (96.7%) | 318/368 (86.4%) | 220/236 (93.2%) | 683/697 (98.0%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `LogbookViewModel.kt:108-121` (CR-1 `reloading` flow and its `combine`) | Reload only while shown; reload once on resubscription | Covered. Lines 108-111 and 114-121 are hit, and the 3 new tests plus the fixer's 2 tests cover the lifecycle cases: first subscription, within the timeout, past the timeout, a cancelled first load, and a reopen while gone. |
| `LogbookViewModel.kt:112` | Closing brace of the `flow {}` builder: `collectLatest` never returns while subscribed, so this line cannot be reached | Not meaningful. |
| `LogbookViewModel.kt:93` (`@Inject` constructor with the system clock) | Production construction | Justified gap: trivial delegation that only Hilt uses. |
| `LogbookViewModel.kt:131` (3 of 8 branches) | Combinations of the `loadMore` guards | The main paths are covered (no cursor, first load running, next page running). The remaining combinations are equivalent early returns. |
| `LogbookScreen.kt:78, 88, 95-101` (`LogbookScreen` Hilt branch, `LogbookRoute`) | Wiring the screen to the Hilt view model (`collectAsStateWithLifecycle`) | Justified gap: this code runs only inside a Hilt activity. Unit tests (as for the other GTD list screens) take the placeholder path. `LogbookCallbacks.of` (line 80) is covered by `the screen callbacks reach the view model`. A device or emulator smoke test was not run. |
| `LogbookScreen.kt:170` and partial branches at 121, 165-175, 293, 296 | Closing brace of a never-ending `LaunchedEffect` collector; an impossible state (not loaded, no error, not loading); null layout items; null contexts or notes in the dialog | Low risk. The meaningful cases (blank notes, unknown contexts, scroll to the end, short first page) are tested. |
| `LogbookRepository.kt:72`, `:81` (1 branch each) | The `checkNotNull` failure path; `CompletionResult.Completed` from `undo` (which `undo` never returns) | Defensive code, not reachable. |

## Unable to test

- The Hilt-wired `LogbookRoute` on a real device or emulator: the real lifecycle (`collectAsStateWithLifecycle` stopping the subscription in the background, which is what makes CR-1 effective) and scrolling against a server with more than 50 completed tasks. There is no emulator in this environment. The `WhileSubscribed` behavior is covered at unit level with virtual time. Follow-up only if the parent wants manual device evidence.
- The real server (`GET /api/v1/tasks?completed=true`, `POST /tasks/{id}/uncomplete`) is simulated with MockWebServer. This task does not change the server.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.
