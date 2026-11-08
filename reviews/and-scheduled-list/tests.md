# Test Report

Date: 2026-11-08

Task: and-scheduled-list (Android: Scheduled list screen), second round on the final revision

Scope: base 6a993ed682dd38426131fa2e8eaa4c852ef0d734 .. HEAD 161f8080365a52dfa3733708d356b8e43eb5d10e (`git diff 6a993ed68..161f80803`; task commits a8dedeb2c implementation, 85880e8dc first-round tests and review, 161f80803 fixer CR-2 generic ListSection). Working tree clean at measurement; no uncommitted inputs. Task ownership: base and commits supplied by the parent, confirmed against the diff and the task's `touches`.

Production files in scope (from the diff):
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/ScheduledScreen.kt` (new)
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/ListUi.kt` (generic `ListSection` with internal `items(scope, tasks, row)`, generic `groupConsecutiveBy`, shared `DayHeader`)
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/ListViewModels.kt` (ScheduledViewModel, byScheduledDate)
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/LogbookScreen.kt` (uses shared DayHeader)
- `android/app/src/main/java/ch/sonensei/cockpit/ui/navigation/CockpitDestination.kt` (Scheduled entry)
- `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt` (one `when` branch)
- `android/app/src/main/res/values/strings_projects_lists.xml`, `android/app/src/main/res/values-de/strings_projects_lists.xml` (resources)

Requirements:
- `tasks/and-scheduled-list.md` acceptance criteria and recorded assumption (More entry after Someday; checkbox completes, tap opens detail, no extra row action; Yesterday/full date for past dates; undated under "No date" last)
- `AGENTS.md` project notes (English + German strings, lint, fictitious data)
- `reviews/and-scheduled-list/fixes.md` (CR-2 refactor must keep rendering, lazy keys and order unchanged)
- Existing test conventions in `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/`

## Second-round assessment of the CR-2 refactor

No additional test is needed for the generic refactor:
- `ListSection.items` is the only path that renders sections, and it is exercised end to end through `ScheduledContent` by ScheduledListTest (date headers in order, same-date tasks under one header, "No date" header last, Yesterday/Today/Tomorrow/full-date labels, German labels). The section header lazy key `section-{key}` with a `null` key is exercised by the "No date" cases.
- The `section == null` path of `LiveTaskListContent` (Waiting, Someday, project lists) is exercised by ListScreensTest 14/14 and LogbookScreenTest is unaffected.
- `groupConsecutiveBy` (now generic) is unit-tested directly by ListSectionTest 6/6, which compiles unchanged.
- Changed-line coverage of ListUi.kt is 37/37 lines and 6/6 branches (see Coverage), so every new or rewritten line of the refactor ran.
- The removed cast in ScheduledScreen.kt is a compile-time improvement; a wrong key type no longer compiles, which a runtime test cannot add to.

## Test files changed

No test files were changed in this round. Task test files (added in the first round or by the parent, all at HEAD):
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/ScheduledViewModelTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/ListSectionTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/ScheduledStringsTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/ScheduledListTest.kt` (new; 11 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/ScheduledInAppTest.kt` (new, by the parent)
- `android/app/src/test/java/ch/sonensei/cockpit/ui/navigation/CockpitDestinationTest.kt` (one line: More order includes Scheduled)

## Tests added or updated

Count: 0 in this round. Task total: 30 (ScheduledViewModelTest 9, ScheduledListTest 11, ScheduledStringsTest 3, ListSectionTest 6, ScheduledInAppTest 1) plus the updated CockpitDestinationTest order assertion.

## Requirement coverage

All results below come from the parent's run on HEAD 161f80803 of `cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.lists.*' --tests 'ch.sonensei.cockpit.ui.navigation.*'` (BUILD SUCCESSFUL, 0 failures). The Gradle command is abbreviated as "focused run" in the table.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: Scheduled screen reachable like Waiting/Someday (More, after Someday) | ScheduledInAppTest 1/1, CockpitDestinationTest 11/11 | focused run | Pass |
| AC1: scheduled tasks grouped by date, earliest first, undated last | ScheduledListTest (ordering, grouping, same-date one header, past date Yesterday/full date), ScheduledViewModelTest (observes SCHEDULED in date order, ties in list order, reload in date order), ListSectionTest 6/6 | focused run | Pass |
| AC1: complete a task | ScheduledListTest (checkbox completes, busy task disabled, read-only), ScheduledViewModelTest (completing hides and offers undo, undo restores in date order, reload after completion, read-only completes nothing) | focused run | Pass |
| AC1: open detail | ScheduledListTest (tapping a task opens its detail), ScheduledInAppTest | focused run | Pass |
| AC1: loading, empty, error states | ScheduledListTest (empty and read-only, loading then first-load error with retry), ScheduledViewModelTest (first-load error, retry reloads) | focused run | Pass |
| AC2: strings in English and German | ScheduledStringsTest 3/3, ScheduledListTest (German day headers and empty state) | focused run | Pass |
| Regression: Logbook day headers after shared DayHeader | LogbookScreenTest 17/17 | focused run | Pass |
| Regression: CR-2 generic ListSection keeps rendering, keys and order | ScheduledListTest 11/11, ListScreensTest 14/14, ListSectionTest 6/6 | focused run | Pass |
| AC3: `ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass | whole Android suite | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`; `sh scripts/check.sh` | Not run by the test-writer: ktlintCheck and lintDebug passed in the focused run; the unfiltered unit suite and `.taskcheck` are run by the parent |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.lists.*' --tests 'ch.sonensei.cockpit.ui.navigation.*'` (run by the parent on HEAD 161f80803)

Result:

Pass. BUILD SUCCESSFUL. ScheduledViewModelTest 9/9, ScheduledListTest 11/11, ScheduledStringsTest 3/3, ListSectionTest 6/6, ScheduledInAppTest 1/1, LogbookScreenTest 17/17, ListScreensTest 14/14, ListViewModelsTest 13/13, CockpitDestinationTest 11/11, all other gtd.lists classes pass, 0 failures. ktlintCheck and lintDebug clean.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run by the test-writer (sub-agents cannot run Gradle reliably here); the parent runs it for AC3.

## .taskcheck

Result:

Not run by the test-writer; the parent runs `sh scripts/check.sh` before `done`.

## Bugs found

None. All task tests pass on the final revision.

## Coverage

Status: verified (filtered run: only gtd.lists and ui.navigation tests; whole-file numbers of files with code outside the task can be lower than with the full suite).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured by the test-writer from the same JaCoCo report: added/modified lines of `git diff -U0 6a993ed68 161f80803 -- android/app/src/main/java` intersected with JaCoCo executable lines (lines with instructions). Result 82/83 lines, branches on those lines 23/32. Per file: ListUi.kt 37/37 lines, 6/6 branches; ScheduledScreen.kt 37/38 lines, 17/26 branches; ListViewModels.kt 5/5 lines, 0/0 branches; LogbookScreen.kt 1/1; CockpitDestination.kt 1/1; CockpitShell.kt 1/1.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-scheduled-list; base 6a993ed682dd38426131fa2e8eaa4c852ef0d734; HEAD 161f8080365a52dfa3733708d356b8e43eb5d10e; commits a8dedeb2c, 85880e8dc, 161f80803 supplied by the parent and matched against `git diff --stat 6a993ed68 161f80803`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 161f8080365a52dfa3733708d356b8e43eb5d10e, clean working tree (`git status --short` empty); no uncommitted inputs.
- Exact included files; renamed/deleted/excluded files and reasons: ScheduledScreen.kt, ListUi.kt, ListViewModels.kt, LogbookScreen.kt, CockpitDestination.kt, CockpitShell.kt. Excluded: the two `strings_projects_lists.xml` files (resources, not instrumented; covered by ScheduledStringsTest). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via the Android Gradle plugin; `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.lists.*' --tests 'ch.sonensei.cockpit.ui.navigation.*'` then `./gradlew createDebugUnitTestCoverageReport`, in `android/`, run by the parent.
- Run start/completion time and exit status: JaCoCo session 2026-11-08 13:37:25 +0100 to 13:38:12 +0100; report written 13:39:17 +0100; exit status success (reported by the parent).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 590f0bfa0888566e7e11bd86fc39915d270ed0446db96e8c65566bc5335883fa, session id dell26-88c8b7cc.
- Evidence outputs are fresh and inputs stayed unchanged: the report postdates commit 161f80803, its counters match the numbers the parent reported for this run, and the tree is clean at HEAD.
- Final run after latest test additions and production fixes: yes (CR-2 fix is in 161f80803; no tests were added afterwards).

Values below use covered/total (percentage); JaCoCo has no separate statement counter, so instructions are given instead.

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `android/.../gtd/lists/ScheduledScreen.kt` | 346/385 (89.9%) | 17/26 (65.4%) | 9/9 (100%) | 37/38 (97.4%) |
| `android/.../gtd/lists/ListUi.kt` | 1620/1660 (97.6%) | 59/75 (78.7%) | 38/45 (84.4%) | 153/155 (98.7%) |
| `android/.../gtd/lists/ListViewModels.kt` | 593/622 (95.3%) | 13/14 (92.9%) | 38/41 (92.7%) | 80/82 (97.6%) |
| `android/.../gtd/lists/LogbookScreen.kt` | 2080/2300 (90.4%) | 104/134 (77.6%) | 42/52 (80.8%) | 147/162 (90.7%) |
| `android/.../ui/navigation/CockpitDestination.kt` | 385/385 (100%) | 9/9 (100%) | 13/13 (100%) | 38/38 (100%) |
| `android/.../ui/CockpitShell.kt` | 1117/2025 (55.2%) | 43/123 (35.0%) | 35/51 (68.6%) | 110/165 (66.7%) |
| Aggregate (summed counters, all 6 files) | 6141/7377 (83.2%) | 245/381 (64.3%) | 175/211 (82.9%) | 565/640 (88.3%) |

Aggregate excludes only the two string resource files (not instrumented). CockpitShell.kt is mostly pre-existing shell code exercised by tests outside the filter; its one task line is covered.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ScheduledScreen.kt:32` (placeholder outside a Hilt activity) | Previews and non-Hilt navigation tests show a placeholder | Justified gap: no requirement; the same pattern in LogbookScreen.kt (lines 84-88) is also untested; the real route is covered by ScheduledInAppTest |
| `ScheduledScreen.kt:28-29, 37-40` partial branches | Compose-generated default-argument and recomposition-skip branches of `ScheduledScreen`/`ScheduledRoute` | Justified gap: compiler-generated, not product behavior |
| `ListUi.kt:292-293` and partial branches in snackbar/`LiveTaskListContent` | Action snackbar kinds COMPLETED/NEXT_STEP, used by project screens | Pre-existing code outside the task; covered by tests outside this filter |
| `ListViewModels.kt:153, 175` | `@Inject` constructors of Waiting/Someday view models | Pre-existing code outside the task |
| `LogbookScreen.kt:76, 84-99, 168` | Logbook route/placeholder and load-ahead effect | Pre-existing code outside the task; the task's only changed line (shared DayHeader call) is covered |

## Unable to test

- Full date header under the German locale (German full date text): not asserted, because the formatter uses the JVM default locale, which the Robolectric qualifier may not change; German resource labels are asserted. No follow-up needed for the criteria.
- Unfiltered Android unit suite and `sh scripts/check.sh`: left to the parent (sub-agents cannot run Gradle reliably here).

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The CR-2 generic `ListSection` refactor needs no additional test: its changed lines are fully covered (ListUi.kt changed lines 37/37, branches 6/6) by ScheduledListTest, ListScreensTest and ListSectionTest, all passing on HEAD 161f80803.
