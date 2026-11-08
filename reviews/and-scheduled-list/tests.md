# Test Report

Date: 2026-11-08

Task: and-scheduled-list (Android: Scheduled list screen)

Scope: base 6a993ed682dd38426131fa2e8eaa4c852ef0d734 .. task commit a8dedeb2c (HEAD of main at invocation; full hash to be recorded by the parent in quality.json). Production files in scope, as given by the parent and confirmed by reading the sources: `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/ScheduledScreen.kt` (new), `.../gtd/lists/ListUi.kt` (ListSection, groupConsecutiveBy, shared DayHeader), `.../gtd/lists/ListViewModels.kt` (ScheduledViewModel, byScheduledDate), `.../gtd/lists/LogbookScreen.kt` (uses shared DayHeader), `.../ui/navigation/CockpitDestination.kt` (Scheduled entry), `.../ui/CockpitShell.kt` (one line), `res/values/strings_projects_lists.xml`, `res/values-de/strings_projects_lists.xml`.

Requirements:
- `tasks/and-scheduled-list.md` acceptance criteria and recorded assumption (More entry after Someday; checkbox completes, tap opens detail, no extra row action; Yesterday/full date for past dates; undated under "No date" last)
- `AGENTS.md` project notes (English + German strings, lint, fictitious data)
- Existing test conventions: `ListViewModelsTest.kt`, `LogbookStringsTest.kt`, `LogbookScreenTest.kt`, `ScheduledListTest.kt`

Environment limitation: the Bash tool was denied in this sub-agent session, so no command (Gradle, git, coverage) could be run by the test-writer. All tests below were written by reading the production code and existing tests; the parent must run them (commands listed below) and fix compile errors, if any.

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/ScheduledViewModelTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/ListSectionTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/ScheduledStringsTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/ScheduledListTest.kt` (extended, existing 6 tests unchanged)

## Tests added or updated

Count: 23 (ScheduledViewModelTest 9, ListSectionTest 6, ScheduledStringsTest 3, ScheduledListTest +5)

- ScheduledViewModelTest: observes the Scheduled list, earliest date first, ties in list order, undated last; ties on the same date keep list position; completing hides the task at once and offers undo; undoing a completion shows the task again in its date order; reloads once a completion got its answer; retry asks for a reload; a reload with new data is shown in date order; nothing is completed while the list is read-only; a first load error is shown, not loading.
- ListSectionTest (groupConsecutiveBy): empty list; single task; consecutive equal keys share a section in order; null key as its own section, also first; non-adjacent equal keys start a new section; after byScheduledDate every date is exactly one section and no task is lost.
- ScheduledStringsTest: every scheduled string (nav_scheduled, scheduled_empty/today/tomorrow/yesterday/no_date) exists in English and German, not blank; German differs from English; no placeholders.
- ScheduledListTest additions: same-date tasks share one header, which is a heading with the full date ("Friday, October 30, 2026"); rows have no extra action button; a busy task's checkbox is disabled while others stay enabled; loading, then first-load error with retry; German day headers (Gestern/Heute/Morgen/Kein Datum) and German empty state via `@Config(qualifiers = "de")`.

Logbook day headers (shared DayHeader refactor): already covered by existing `LogbookScreenTest` (`groups by completion day...`, `an older day shows its full date`, `german day headers, empty state and next page messages`); no new Logbook test was needed, but that class must be re-run against the refactor.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: Scheduled screen reachable like Waiting/Someday (More) | ScheduledInAppTest (parent), CockpitDestinationTest (parent) | cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.lists.ScheduledInAppTest' --tests 'ch.sonensei.cockpit.ui.navigation.*' | Not run by test-writer: Bash denied; parent reported pass |
| AC1: lists scheduled tasks grouped by date, earliest first, undated last | ScheduledListTest (orders..., groups..., same-date one header..., past date yesterday), ScheduledViewModelTest (observes..., ties..., reload with new data...), ListSectionTest (all 6) | cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.lists.*' | Not run: Bash denied in sub-agent; parent must run |
| AC1: complete a task | ScheduledListTest (completes with checkbox, busy task, read-only), ScheduledViewModelTest (completing hides, undo, reload after completion, read-only) | same | Not run: Bash denied in sub-agent; parent must run |
| AC1: open detail | ScheduledListTest (tapping a task opens its detail) | same | Not run: Bash denied in sub-agent; parent must run |
| AC1: loading/empty/error states | ScheduledListTest (empty and read-only, loading then first-load error), ScheduledViewModelTest (first load error, retry) | same | Not run: Bash denied in sub-agent; parent must run |
| AC2: strings in English and German | ScheduledStringsTest (3), ScheduledListTest (german day headers and empty state) | same | Not run: Bash denied in sub-agent; parent must run |
| Regression: Logbook day headers after DayHeader refactor | LogbookScreenTest (existing) | same | Not run: Bash denied in sub-agent; parent must run |
| AC3: ktlintCheck testDebugUnitTest lintDebug and scripts/check.sh pass | whole suite | cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug; sh scripts/check.sh | Not run: Bash denied in sub-agent; parent must run |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.lists.*' --tests 'ch.sonensei.cockpit.ui.navigation.*'`

Result:

Not run: the Bash tool was denied for this sub-agent, so Gradle could not be started. The parent must run it.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run (Bash denied in sub-agent).

## .taskcheck

Result:

Not run (Bash denied in sub-agent); parent runs `sh scripts/check.sh`.

## Bugs found

None found by code reading. No test could be executed, so this is not evidence of absence of defects.

## Coverage

Status: unavailable. The Bash tool was denied for the test-writer, so neither the tests nor the coverage task from docs/ARCHITECTURE.md "Coverage" could be run. No numbers are reported; nothing from earlier runs or repository-wide reports is substituted.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-scheduled-list; base 6a993ed682dd38426131fa2e8eaa4c852ef0d734; task commit a8dedeb2c; file scope supplied by the parent and matched against the sources read.
- Source/test snapshot identifier (including uncommitted inputs): not taken (no shell); uncommitted inputs are the four test files listed above.
- Exact included files; renamed/deleted/excluded files and reasons: in scope ScheduledScreen.kt, ListUi.kt, ListViewModels.kt, LogbookScreen.kt, CockpitDestination.kt, CockpitShell.kt; string XML files are resources (not instrumented, covered by ScheduledStringsTest).
- Provider/version, command, working directory, and test selection: not run.
- Run start/completion time and exit status: not run.
- Generated report paths and report hashes/run identifier: none.
- Evidence outputs are fresh and inputs stayed unchanged: not applicable, no run.
- Final run after latest test additions and production fixes: no, no run was possible.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/.../gtd/lists/ScheduledScreen.kt` | N/A (not measured) | N/A | N/A | N/A |
| `android/.../gtd/lists/ListUi.kt` | N/A (not measured) | N/A | N/A | N/A |
| `android/.../gtd/lists/ListViewModels.kt` | N/A (not measured) | N/A | N/A | N/A |
| `android/.../gtd/lists/LogbookScreen.kt` | N/A (not measured) | N/A | N/A | N/A |
| `android/.../ui/navigation/CockpitDestination.kt` | N/A (not measured) | N/A | N/A | N/A |
| `android/.../ui/CockpitShell.kt` | N/A (not measured) | N/A | N/A | N/A |

Follow-up: the parent should run the Android coverage command from docs/ARCHITECTURE.md "Coverage" after the tests pass, or record that coverage was not measured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ScheduledScreen.kt` ScheduledScreen placeholder branch (non-Hilt activity) | Navigation tests outside Hilt show the placeholder | Expected to be hit by CockpitDestinationTest/navigation tests; not verified (no run) |
| `ScheduledScreen.kt` ScheduledRoute | Real screen inside the Hilt activity | ScheduledInAppTest (parent); not verified here |

## Unable to test

- Running any test, lint or coverage: Bash denied to the test-writer. Needs follow-up by the parent (commands above).
- Date-header format under the German locale (full date in German): not asserted, because the formatter uses the JVM default locale, which the Robolectric qualifier may not change; only the German resource labels are asserted.

## Summary

Meaningful task behavior remains untested and requires follow-up: 23 tests were written for the gaps (ScheduledViewModel, groupConsecutiveBy, German strings, more Scheduled screen states), but none could be run because Bash was denied in this sub-agent. The parent must run them and the full Android check. No production defects were found by reading the code.
