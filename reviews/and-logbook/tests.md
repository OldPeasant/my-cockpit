# Test Report

Date: 2026-11-01

Task: and-logbook - Android: Logbook (completed tasks, reopen)

Scope: task base `b5f069cdf0dd7b8a3808f7b25951e89fcb91f3e1` .. task commit `456b21f817e37cf7ee6d62c4baf621a1169dc212` (HEAD of `main` in the agent-2 clone; task-owned commits `552e31d1e`, `96984c3b1`, `6b1882934` (assumptions only) and `456b21f81` (implementation)), restricted to `android/`. Plus the uncommitted test changes listed below.

Requirements:
- `tasks/and-logbook.md` acceptance criteria and its three recorded assumptions (detail dialog with Reopen; pages of 50, reload of as many pages as shown; Logbook in More right after Someday)
- `SPEC.md` §5 (Logbook), `docs/ARCHITECTURE.md` ("Coverage", "Code conventions": every `values/` string needs its `values-de/` entry)
- `AGENTS.md` project notes; parent instructions (edge cases to cover)
- existing test conventions (`ListViewModelsTest`, `ListScreensTest`, `SettingsStringsTest`, `CockpitShellTest`)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/LogbookViewModelTest.kt` (extended)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/LogbookRepositoryTest.kt` (extended)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/LogbookScreenTest.kt` (extended)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/LogbookStringsTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/ui/CockpitShellTest.kt` (one test added)

No existing test was changed, weakened, skipped or removed. No production file was modified.

## Tests added or updated

Count: 37 added (ViewModel 18, repository 4, screen 11, strings 3, shell 1).

ViewModel (`LogbookViewModelTest`):
- `a task on two pages is listed once when the next page is loaded`
- `a reload over several pages lists a task that moved to the next page once`
- `a reload stops at the last page even when more pages were shown before`
- `a reload that fails on a later page keeps the whole list that was shown`
- `only one next page is requested at a time`
- `no next page is requested while the first page loads`
- `a change while the next page loads cancels it, so its late answer is not appended`
- `a next page answering after a running reload is appended once`
- `a reload answering after a next page leaves a consistent list that can load more again`
- `an older reload answering last does not replace a newer one`
- `retry after a failed first load shows the spinner again, without the error`
- `a reload clears the error of a failed next page`
- `a reload closes the opened task when it left the logbook, and refreshes it when it is still there`
- `a conflicting reopen keeps the task, reports the conflict and reloads`
- `a failed reopen keeps the task and does not reload`
- `reopening one task keeps another task's dialog open`
- `the screen callbacks reach the view model`
- `dismissing an older snackbar keeps the newer one`

Repository (`LogbookRepositoryTest`, MockWebServer):
- `a malformed page is a failed load`
- `reopening a task trashed elsewhere is a conflict, so the logbook reloads` (404 -> CONFLICT)
- `a reopened task is back on its list, so the cached Next and Today views reload`
- `the logbook is told to load at once`

Screen (`LogbookScreenTest`, Robolectric Compose):
- `a reopened task is confirmed in a snackbar, then dismissed` (EN "Reopened File taxes")
- `a conflicting reopen says the list shows the current state`
- `a reopened task is confirmed in German` ("File taxes wieder geöffnet")
- `german day headers, empty state and next page messages`
- `an older day shows its full date`
- `the detail dialog cannot reopen while busy or read-only`
- `a task without project, contexts or notes shows only its completion time`
- `a project or context that is not loaded is left out of the row`
- `a next page that failed offline says so, and there is no footer without more pages`
- `a long list asks for the next page only once scrolled to its end`
- `scrolling to the end after a failed page does not retry by itself`

Strings (`LogbookStringsTest`, parses `res/values{,-de}/strings_projects_lists.xml`):
- `every logbook string exists in German with the same placeholders` (all `logbook_*` + `nav_logbook`, non-blank)
- `the strings with a title or value have exactly one placeholder`
- `the German texts are translated, not copied`

Shell (`CockpitShellTest`):
- `logbookOpensFromTheMoreListAndBackReturnsToIt`

## Requirement coverage

All rows ran in `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` (2026-11-01T18:51:36Z-18:54:21Z, exit 0, 2662 tests, 0 failures/errors/skipped).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Logbook reachable from More, next to Projects/Waiting/Someday (assumed: right after Someday) | `CockpitShellTest.moreListsEverySecondaryDestination`, `CockpitShellTest.logbookOpensFromTheMoreListAndBackReturnsToIt`, `CockpitDestinationTest` (order list) | as above | Pass |
| Lists completed tasks newest first, grouped by completion day (local zone), with project/context | `LogbookViewModelTest` `lists done tasks newest first, grouped by local completion day...`; `LogbookScreenTest` `groups by completion day...`, `an older day shows its full date`, `a project or context that is not loaded is left out of the row`; `LogbookRepositoryTest` `loads a page of done tasks...`, `sends the cursor and skips tasks without a completion time` | as above | Pass |
| Loads more on scroll if the API pages (incl. dedupe, concurrency with reloads) | ViewModel: `loads the next page...`, `a failed next page is reported and can be retried`, `a task on two pages is listed once...`, `a reload over several pages...`, `a reload stops at the last page...`, `a reload that fails on a later page...`, `only one next page...`, `no next page is requested while the first page loads`, `a change while the next page loads cancels it...`, `a next page answering after a running reload...`, `a reload answering after a next page...`, `an older reload answering last...`, `a reload clears the error of a failed next page`; Screen: `a short list with more pages asks...`, `a long list asks for the next page only once scrolled to its end`, `scrolling to the end after a failed page does not retry by itself`, `a next page that failed offline says so...` | as above | Pass |
| Tapping a task opens it (assumed: detail dialog); Reopen uncompletes it and it leaves the Logbook | ViewModel: `reopen removes the task, reports it and reloads`, `a refused reopen keeps the task...`, `a conflicting reopen keeps the task, reports the conflict and reloads`, `a failed reopen keeps the task and does not reload`, `reopening one task keeps another task's dialog open`, `a reload closes the opened task when it left the logbook...`, `the screen callbacks reach the view model`; Repository: `reopen uncompletes the task with its version and maps refusals`, `reopening a task trashed elsewhere is a conflict...`, `a reopened task is back on its list...`; Screen: `the opened task shows its details...`, `the detail dialog cannot reopen while busy or read-only`, `a task without project, contexts or notes...`, `a reopened task is confirmed in a snackbar, then dismissed`, `a conflicting reopen says...` | as above | Pass |
| Loading, empty and error states follow the existing GTD list screens (shared `LiveListTags`, stale banner, read-only) | Screen: `loading, empty and error states`, `reopen is disabled while busy or read-only`; ViewModel: `an empty logbook is loaded and empty`, `a failed first load is an error and retry loads`, `retry after a failed first load shows the spinner again...`, `a failed reload keeps the list read-only`; Repository: `an unreachable server and a refusal are load errors`, `a malformed page is a failed load` | as above | Pass |
| All new UI strings in English and German | `LogbookStringsTest` (3 tests); `LogbookScreenTest` `german strings`, `a reopened task is confirmed in German`, `german day headers, empty state and next page messages`; `lintDebug` (`MissingTranslation`) | as above | Pass |
| Unit + Compose UI tests cover the criteria; `./gradlew ktlintCheck testDebugUnitTest lintDebug` passes | all of the above | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` | Pass |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.lists.Logbook*' --tests 'ch.sonensei.cockpit.ui.CockpitShellTest' --tests 'ch.sonensei.cockpit.ui.navigation.CockpitDestinationTest'`

Result:

Pass (Repository 8, Screen 17, Strings 3, ViewModel 26 at that time, Shell 8, Destination 11; 0 failures). After that one more ViewModel test was added (27 total), which passed in the full run below.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport`

Result:

Pass (exit 0, 2662 tests, 0 failures, 0 errors, 0 skipped; ktlint and lint clean).

## .taskcheck

Result:

Pass (`sh scripts/check.sh` -> "== all checks passed", run after the final test changes).

## Bugs found

None. No test exposed behavior that violates the acceptance criteria or the recorded assumptions.

Observation (not a defect against the criteria, no fix required): when a "load more" page answers before a reload that had already started, the reload keeps only the number of pages it computed at its start, so the just-appended page disappears again and the footer reappears (the next scroll loads it again). Pinned as consistent behavior by `a reload answering after a next page leaves a consistent list that can load more again`.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured: 320/332 executable added/modified lines (96.4%), by intersecting the added/modified lines of `git diff -U0 b5f069cdf..456b21f81 -- android/app/src/main/java` with JaCoCo `<line>` entries (`mi+ci > 0`) of the same report. Changed-branch coverage not measured. For the three new files all lines are task lines, so their changed-line numbers equal the whole-file line numbers below; the single changed line in each of `ListUi.kt`, `ListViewModels.kt`, `CockpitShell.kt`, `CockpitDestination.kt` is covered.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-logbook`; base `b5f069cdf0dd7b8a3808f7b25951e89fcb91f3e1` (given by the parent); measured HEAD `456b21f817e37cf7ee6d62c4baf621a1169dc212`; task commits from `git log b5f069cdf..HEAD` (all authored for and-logbook; no other task's commits in the range).
- Source/test snapshot identifier (including uncommitted inputs): production = HEAD `456b21f81` (no uncommitted production changes). Uncommitted test files (sha256): `LogbookRepositoryTest.kt` 39da9d23590f018d286819d0da5e5ecb35a15fbe261097493c02ca70dabc6132, `LogbookScreenTest.kt` 7d997452e6e04ac13efcc0d9f184bf34f289d2fb3012c316b0730226cd13ccc5, `LogbookStringsTest.kt` 907c0d64687c463835b91b915e17e971bf5594f48a55adcfc9f6d2022860b674, `LogbookViewModelTest.kt` 7991e0671b15c89d232a769455b2d8768222999ee73772eea9fd4afc8eb7ae32, `CockpitShellTest.kt` adfb5ad7e56dce3cdace86220fce1ae2d857432393a209b3c131cc0241b37ba4.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/{LogbookRepository,LogbookViewModel,LogbookScreen,ListUi,ListViewModels}.kt`, `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt`, `android/app/src/main/java/ch/sonensei/cockpit/ui/navigation/CockpitDestination.kt`. Excluded: `res/values{,-de}/strings_projects_lists.xml` (resources, not instrumentable; covered by `LogbookStringsTest` and lint); `tasks/and-logbook.md` (task metadata); test files. No renames or deletions. No missing/unmeasured files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (Gradle `jacoco` plugin, default tool version of Gradle 9.8.1), JDK 21.0.12; `./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` in `android/`; full debug unit test suite.
- Run start/completion time and exit status: 2026-11-01T18:51:36Z - 2026-11-01T18:54:21Z, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 986b75cde1c627701293672d57a3bed1e70752701290779886a8424904df399c, written 2026-11-01T18:54:21Z (end of this run).
- Evidence outputs are fresh and inputs stayed unchanged: yes. The test inputs changed after the earlier run, so Gradle re-executed `testDebugUnitTest` and regenerated the report; test-file hashes were taken before the run and compared after it (identical).
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage).

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `gtd/lists/LogbookRepository.kt` | 259/268 (96.6%) | 13/15 (86.7%) | 15/15 (100.0%) | 40/40 (100.0%) |
| `gtd/lists/LogbookViewModel.kt` | 1116/1133 (98.5%) | 53/58 (91.4%) | 38/39 (97.4%) | 118/120 (98.3%) |
| `gtd/lists/LogbookScreen.kt` | 2166/2332 (92.9%) | 98/120 (81.7%) | 45/54 (83.3%) | 158/168 (94.0%) |
| `gtd/lists/ListUi.kt` (1 line changed) | 1363/1384 (98.5%) | 50/59 (84.7%) | 30/35 (85.7%) | 122/122 (100.0%) |
| `gtd/lists/ListViewModels.kt` (1 line changed) | 592/601 (98.5%) | 13/14 (92.9%) | 37/38 (97.4%) | 77/77 (100.0%) |
| `ui/CockpitShell.kt` (2 lines changed) | 1580/1615 (97.8%) | 80/93 (86.0%) | 42/42 (100.0%) | 132/134 (98.5%) |
| `ui/navigation/CockpitDestination.kt` (2 lines changed) | 363/363 (100.0%) | 9/9 (100.0%) | 13/13 (100.0%) | 36/36 (100.0%) |
| Aggregate (summed counters, all 7 files measured, none excluded) | 7439/7696 (96.7%) | 316/368 (85.9%) | 220/236 (93.2%) | 683/697 (98.0%) |

JaCoCo's "Instructions" counter is reported in the statements column's place; the old files' numbers include pre-existing code and tests.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `LogbookScreen.kt:86-101` (`LogbookScreen` Hilt branch, `LogbookRoute`) | Wiring the screen to the Hilt view model | Justified gap: runs only inside a Hilt activity; unit tests (like the other GTD list screens) use the placeholder path. The callback mapping it uses (`LogbookCallbacks.of`, line 80) is now covered by `the screen callbacks reach the view model`. Device/emulator smoke test not run. |
| `LogbookViewModel.kt:91` (`@Inject` constructor with the system clock) | Production construction | Justified gap: trivial delegation, only used by Hilt. |
| `LogbookViewModel.kt:112` | closing brace of the never-ending `init` collector | Not meaningful. |
| `LogbookViewModel.kt:123` (3 of 8 branches) | `loadMore` guard combinations | Main paths covered (no cursor, first load running, page running); remaining combinations are equivalent early returns. |
| `LogbookScreen.kt:121` (partial), `165-175` (partial), `293`, `296` (partial) | impossible state (not loaded, no error, not loading); null `layoutInfo` items; null contexts/notes in the dialog | Low risk; the meaningful cases (blank notes, unknown contexts, scroll-to-end, short first page) are tested. |
| `LogbookRepository.kt:72`, `:81` (1 branch each) | `checkNotNull` failure path; `CompletionResult.Completed` from `undo` (not returned by `undo`) | Defensive, not reachable. |

## Unable to test

- The Hilt-wired `LogbookRoute` on a real device/emulator (actual scrolling against a server with more than 50 completed tasks): no emulator in this environment; covered at unit level only. Follow-up only if the parent wants manual device evidence.
- The real server (`GET /api/v1/tasks?completed=true`, `POST /tasks/{id}/uncomplete`) is simulated with MockWebServer; the server is unchanged by this task.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.
