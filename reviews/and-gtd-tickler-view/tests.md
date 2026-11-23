# Test Report

Date: 2026-11-23

Task: and-gtd-tickler-view - Android: Tickler list view (what comes back when)

Scope: `git diff ad19e25d7d90d000f8910e2aa566b04e765a09c6..6cd38d1ca10e96f152b95d50b22a837e09fce4c7` (task base ad19e25d7, single task commit 6cd38d1ca supplied by the parent worker; HEAD = 6cd38d1ca at measurement). Working tree: only the four new test files below are uncommitted.

Requirements:
- tasks/and-gtd-tickler-view.md acceptance criteria and recorded assumptions (10-year yearly `from`/`to` window, client filter `ticklerDate` after today, no pull to refresh, `EEEdMMM` date skeleton, Alarm icon)
- SPEC.md §5.1 / §5.3 (Tickler view)
- docs/ARCHITECTURE.md "Coverage"; existing test conventions (ScheduledViewModelTest, ScheduledListTest, ScheduledStringsTest, TaskListsRepositoryTest)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/TicklerRepositoryTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/TicklerViewModelTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/TicklerListTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/TicklerStringsTest.kt` (new)

The implementation commit already extended `ui/navigation/CockpitDestinationTest.kt` (Tickler destination in the More section); not changed here.

## Tests added or updated

Count: 22 (4 repository, 7 view model, 8 Compose/formatting, 3 strings)

## Requirement coverage

Command for every row: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.lists.*' --tests 'ch.sonensei.cockpit.ui.*' createDebugUnitTestCoverageReport` (Robolectric, JDK unit tests).

| Requirement | Tests | Result |
|---|---|---|
| Data from existing `GET /api/v1/tasks?from=&to=`: ten gap-free one-year windows starting tomorrow, no `list` filter | TicklerRepositoryTest: `asks for the next ten years, one gap-free year per request starting tomorrow` | Pass |
| Only tickler date after today shown (today / past / no tickler dropped), date carried, duplicates across windows removed | TicklerRepositoryTest: `keeps only tasks with a tickler date after today, carries the date, and drops duplicates` | Pass |
| All tasks returned reliably despite paging | TicklerRepositoryTest: `follows the next page within a year` | Pass |
| Error state: a failing request is an error, not a partial list | TicklerRepositoryTest: `a failing year makes the whole load an error, not a partial list`; TicklerViewModelTest: `a first load error is shown, not loading` | Pass |
| Sorted by tickler date, then usual order | TicklerViewModelTest: `observes the tickler with the clock's today, earliest return date first, ties in list order`, `tickler order sorts by date, then position, undated last` | Pass |
| Rows show the return date ("Back on Mon, Dec 14", year when not this year), German equivalent | TicklerListTest: `each row shows the day it comes back, with the year when not this year`, `back-on date in English and German`, `german return date and empty state`, `a task without a tickler date has no back-on line` | Pass |
| Tapping a row opens task detail | TicklerListTest: `tapping a task opens its detail` | Pass |
| After returning, the view reflects the change | TicklerViewModelTest: `a reload after the date changed in the detail shows the new order` (repository emission); trigger by `TaskEdits.changes` not tested specifically for Tickler (shared `taskChanges()` already used by other lists) | Pass (partial, see gaps) |
| Rows support completing (checkbox, undo, busy, reload after answer) | TicklerListTest: `completes a task with its checkbox, a busy one is disabled`; TicklerViewModelTest: `completing hides the task at once and offers undo`, `reloads once a completion got its answer, and retry reloads` | Pass |
| Loading, empty "Nothing in the tickler", offline/stale (read-only), error with retry | TicklerListTest: `loading, then a first load error with retry`, `empty, then read-only after a failed reload`; TicklerViewModelTest: `offline after a load keeps the list read-only, nothing is completed` | Pass |
| Pull to refresh | Not tested: by recorded assumption the live lists have no pull to refresh (reload on change events plus Retry) | Not testable as specified |
| All new strings in English and German, translated, one placeholder only in the back-on text | TicklerStringsTest (3 tests) | Pass |
| Destination next to Scheduled, own icon | Existing `CockpitDestinationTest` (updated by the implementation commit), run in the `ch.sonensei.cockpit.ui.*` selection | Pass |
| SPEC.md §5.3 lists the view | Documentation; not a test target | Not testable |
| `lintDebug` part of the criterion | Not run by the test-writer (parent runs it) | Not run: outside requested command |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.lists.Tickler*'`

Result:

First run: 2 of 22 failed because the test looked up the back-on text in the merged row semantics (test defect, fixed with `useUnmergedTree = true`). No production change.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.lists.*' --tests 'ch.sonensei.cockpit.ui.*' createDebugUnitTestCoverageReport`

Result:

Pass (exit 0). 281 tests in the selection, 0 failures, 0 errors (from `app/build/test-results/testDebugUnitTest/*.xml`); the 22 Tickler tests all pass. ktlintCheck passed. The full Android suite and `lintDebug` were not run (parent's request limited the selection).

## .taskcheck

Result:

Not run (parent runs `.taskcheck`; parent asked that Gradle not run concurrently).

## Bugs found

None. No production defect was found for the reviewed scope.

## Coverage

Status: verified for the listed files (fresh JaCoCo run, selection limited to `gtd.lists.*` and `ui.*` tests).

Metric: whole-file coverage of task-changed production files (old code included, hits from pre-existing tests included).
Changed-line coverage: not measured (no changed-line tooling configured); notes on changed lines below come from reading the JaCoCo line data.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-gtd-tickler-view; base ad19e25d7d90d000f8910e2aa566b04e765a09c6; HEAD 6cd38d1ca10e96f152b95d50b22a837e09fce4c7; task commit and base supplied by the parent worker, checked against the diff and task touches.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 6cd38d1ca plus new test files, sha256 TicklerListTest.kt 3873358a720271c2..., TicklerRepositoryTest.kt 6330b395abcb14d3..., TicklerStringsTest.kt c877f9eda95ac44b..., TicklerViewModelTest.kt 79639731309e07b9...
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/TaskLists.kt`, `.../gtd/lists/ListViewModels.kt`, `.../gtd/lists/TicklerScreen.kt`, `.../gtd/data/GtdModels.kt`, `.../ui/navigation/CockpitDestination.kt`, `.../ui/CockpitShell.kt`. Excluded: `SPEC.md` (documentation), `res/values*/strings_projects_lists.xml` (resources, checked by TicklerStringsTest, not instrumentable). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.lists.*' --tests 'ch.sonensei.cockpit.ui.*' createDebugUnitTestCoverageReport -q` in `android/`.
- Run start/completion time and exit status: 2026-11-23T17:59:50Z to 2026-11-23T18:00:44Z, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 b901de5aaa9dfa5f93b0624f817250e081a9b114595b566196505877f859e229, written 18:00:42Z.
- Evidence outputs are fresh and inputs stayed unchanged: report and Tickler test results timestamped inside the run window; test files not edited after the snapshot.
- Final run after latest test additions and production fixes: yes (no production fixes made).

Values below use covered/total (percentage). Statements = JaCoCo INSTRUCTION counters.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `gtd/lists/TicklerScreen.kt` | 242/346 (69.9%) | 7/20 (35.0%) | 6/8 (75.0%) | 26/34 (76.5%) |
| `gtd/lists/TaskLists.kt` | 959/1001 (95.8%) | 32/34 (94.1%) | 34/34 (100%) | 106/106 (100%) |
| `gtd/lists/ListViewModels.kt` | 756/802 (94.3%) | 27/28 (96.4%) | 49/55 (89.1%) | 98/102 (96.1%) |
| `gtd/data/GtdModels.kt` | 482/556 (86.7%) | N/A (0/0) | 44/54 (81.5%) | 53/56 (94.6%) |
| `ui/navigation/CockpitDestination.kt` | 400/400 (100%) | 9/9 (100%) | 13/13 (100%) | 39/39 (100%) |
| `ui/CockpitShell.kt` | 2084/2295 (90.8%) | 116/150 (77.3%) | 46/56 (82.1%) | 177/187 (94.7%) |
| Aggregate (summed counters, all six files) | 4923/5400 (91.2%) | 191/241 (79.3%) | 192/220 (87.3%) | 499/524 (95.2%) |

No scoped file is missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `TicklerScreen.kt:30-45` (Hilt branch of `TicklerScreen`, `TicklerRoute`) | Wiring the view model into the screen inside the Hilt activity | Left uncovered: needs a Hilt in-app test like `ScheduledInAppTest`; the content and the view model are tested separately. Suggested follow-up if the parent wants route-level proof. |
| `TicklerScreen.kt:56` (one branch) | Compose locale lookup | Non-material. |
| `ListViewModels.kt:260` (`TicklerViewModel` `@Inject` constructor with system clock) | DI entry point | Non-material; same pattern untested for other lists. |
| `ListViewModels.kt:51,56,66,70,73,129,160,209` | Pre-existing or synthetic default-argument code | Outside task behavior. |
| `TaskLists.kt:77,222-223` (partial branches) | Pre-existing mapping branches | Outside task behavior; `ticklerDate` mapping (line 221) is covered. |
| `CockpitShell.kt` missed branches | Pre-existing shell code; the added `Tickler -> TicklerScreen` line is covered | Outside task behavior. |

## Unable to test

- Hilt route wiring (`TicklerRoute`) and the in-app round trip "open detail, change tickler date, back, list updated" end to end: not covered by a Hilt in-app test; follow-up optional (unit evidence: row opens the opener, VM re-sorts/drops on repository emission, repository reloads on the shared task-change triggers used by all lists).
- Pull to refresh: none by design (recorded assumption).
- Tickler dates beyond the 10-year horizon are not shown by design (recorded assumption); not a defect.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient for the criteria; no production defects found. Remaining gap: Hilt route wiring of the Tickler screen (optional follow-up).
