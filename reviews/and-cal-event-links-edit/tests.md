# Test Report

Date: 2026-11-28

Task: and-cal-event-links-edit - Android: add and remove links from the calendar event editor (round 2, final revision)

Scope: base 5d4c6a9542f1736f75a1d76e75003732bf882c5a to HEAD 011e2e9d336687d526104d076def3960634b759d, Android only
(`git diff 5d4c6a954 HEAD -- android`). Task commits: ef6a6cf95, 3a198ab64, 01a782ca4, 24c2d4b13, b3df6332a (review report
only), d8c0535e2, 011e2e9d3 (fixer: CR-2 reload on every appearance, CR-3 comment, reopen regression test).
Uncommitted input: one 2-line change to the test file EventEditorLinksInAppTest.kt made in this round (see below).

Requirements:
- tasks/and-cal-event-links-edit.md acceptance criteria and the recorded assumptions (open in place keeps unsaved edits;
  read-only events still show the panel)
- reviews/and-cal-event-links-edit/review.md (CR-1, CR-2, CR-3) and fixes.md
- AGENTS.md, docs/ARCHITECTURE.md "Coverage"

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorLinksInAppTest.kt` (this round: lint fix
  only, not committed)

## Tests added or updated

Count: 0 tests added, 1 test helper updated.

The requested command failed in `lintDebug` on the round-1 test file:
`EventEditorLinksInAppTest.kt:146: Error: Creating a state object during composition without using remember
[UnrememberedMutableState]`. That run, which round 1 did not include, failed only on this test file. Production code
was not affected. Fix in the `show()` helper: `var state by mutableStateOf(initial)` became
`var state by remember { mutableStateOf(initial) }`, plus the `androidx.compose.runtime.remember` import. No assertion or
test behavior changed. Because the test file changed, the parallel code review needs a quick check of this 2-line
change.

The fixer's regression test `reopening the editor of the same event shows the links changed meanwhile` covers CR-2
(the panel reloads when it appears again for the same event). The test fails without the fix because the second
appearance makes no request. No further test was needed.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: saved event, "Add link" opens search, picking an item links it to item EVENT/eventId and shows it right away | `a saved event shows the shared links panel inside its links section, loaded for item EVENT with the event id`; `Add link searches all items, links the picked email to the event and shows it right away`; series id for a recurring event: EventEditorViewModelExtrasTest | `./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*' createDebugUnitTestCoverageReport` | Pass |
| AC2: remove with the cross | `a linked item is removed with its cross from the event editor`; `a failed removal is shown and keeps the link and the editor` | same | Pass |
| AC2: open a linked item, back returns with unsaved edits | `a linked note opens in place of the editor, and back returns to the editor with its unsaved edits`; fake-slot test in EventEditorExtrasScreenTest | same | Pass |
| AC3: create task / note from event still works and the result shows in the panel | `a task created from the event reloads the panel so the task shows`; EventEditorViewModelExtrasTest (refresh increments), EventEditorExtrasScreenTest | same | Pass |
| CR-2: reopening the same event shows links changed meanwhile, without a double load on first open | `reopening the editor of the same event shows the links changed meanwhile` (exactly 2 list calls); first-open tests assert exactly 1 list call | same | Pass |
| AC4: new event shows no panel | `a new event shows no links panel and loads no links`; `a new event has no links panel` | same | Pass |
| AC4: read-only events | EventEditorViewModelExtrasTest / screen tests (panel state independent of calendar access) | same | Pass |
| AC5: offline / server error shows error and retry, editor stays usable | `an offline panel shows its error and retry, and the editor stays usable` | same | Pass |
| AC6: ktlintCheck, lintDebug and unit tests green | calendar package tests, ktlint, Android lint | same | Pass after the test-file lint fix (before it: lintDebug Fail, 1 error in the test file) |
| AC7: real device / emulator check | none | none | Not run: no device or emulator available to this helper |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*' createDebugUnitTestCoverageReport`

Result:

Pass on the second run (2026-11-28 13:05:57 to 13:07:24 UTC, BUILD SUCCESSFUL, exit 0): ktlint clean, Android lint
0 errors, 585 calendar tests, 0 failures, 0 errors, 0 skipped (EventEditorLinksInAppTest: 9 tests, 0 failures).
First run (13:03:33 to 13:05:28 UTC, exit 1): `lintDebug` failed with the UnrememberedMutableState error in the test
file described above.

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest` (full Android unit suite)

Result:

Not run by this helper (the parent asked for the calendar-scoped command); the parent runs it through `.taskcheck`.

## .taskcheck

Result:

Not run (left to the parent worker, which runs it before `done`).

## Bugs found

None in production code. The only failure was the lint error in my own round-1 test file, fixed in this round.

## Coverage

Status: verified (whole-file coverage of the task-changed Android production files, from calendar tests only).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured as a counted metric. Spot check: none of the JaCoCo lines with missed
instructions and no covered instructions (EventEditorScreen.kt 283 and 486, EventEditorSections.kt 595) is inside a
task hunk of `git diff -U0 5d4c6a954 HEAD`. EventLinksPanel.kt, a new file, is fully covered.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-cal-event-links-edit; base
  5d4c6a9542f1736f75a1d76e75003732bf882c5a; HEAD 011e2e9d336687d526104d076def3960634b759d; scope from the parent's
  base and `git diff --stat 5d4c6a954 HEAD -- android` (all task commits listed under Scope).
- Source/test snapshot identifier (including uncommitted inputs): HEAD 011e2e9d3 plus the modified
  EventEditorLinksInAppTest.kt, sha256 checked before and after the run with the same result (`sha256sum -c` OK). The
  hash is in `.tmp/tw2-snap-before.txt`. EventLinksPanel.kt sha256
  d9c7e6f1c2a94fddc9d914902cb736daa2441948a29ea56e515a086f2a88321a. No other uncommitted changes.
- Exact included files (android/app/src/main/java/ch/sonensei/cockpit/): calendar/edit/EventEditorExtrasState.kt,
  EventEditorScreen.kt, EventEditorSections.kt, EventEditorViewModel.kt, EventExtrasRepository.kt, EventLinksPanel.kt.
  Excluded: res/values/strings_calendar_edit.xml and res/values-de/strings_calendar_edit.xml (resources with only
  deletions, not executable). No renames; no deleted source files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP enableUnitTestCoverage (Gradle
  9.8.1); `./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*'
  createDebugUnitTestCoverageReport` in `android/`; selection: all tests in package ch.sonensei.cockpit.calendar.
- Run start/completion time and exit status: 2026-11-28 13:05:57 to 13:07:24 UTC, exit 0, BUILD SUCCESSFUL.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`,
  sha256 62d626d69acab760990ccd4ad3661c2e49662a625bd655163ac956c5c105f354, mtime 2026-11-28 13:07:12 UTC.
- Evidence outputs are fresh and inputs stayed unchanged: the report mtime falls inside this run; Gradle reran the
  tests because the test file changed; the snapshot hash was the same before and after the run.
- Final run after latest test additions and production fixes: yes (includes the fixer's CR-2 change and regression
  test and this round's lint fix).

Values below use covered/total (percentage). Statements = JaCoCo instructions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `calendar/edit/EventLinksPanel.kt` | 105/105 (100.0%) | 2/2 (100.0%) | 2/2 (100.0%) | 7/7 (100.0%) |
| `calendar/edit/EventEditorExtrasState.kt` | 276/276 (100.0%) | 20/20 (100.0%) | 28/28 (100.0%) | 31/31 (100.0%) |
| `calendar/edit/EventEditorScreen.kt` | 4903/5087 (96.4%) | 101/107 (94.4%) | 129/161 (80.1%) | 345/347 (99.4%) |
| `calendar/edit/EventEditorSections.kt` | 5519/5587 (98.8%) | 176/190 (92.6%) | 110/116 (94.8%) | 406/407 (99.8%) |
| `calendar/edit/EventEditorViewModel.kt` | 4709/4818 (97.7%) | 398/458 (86.9%) | 100/101 (99.0%) | 469/469 (100.0%) |
| `calendar/edit/EventExtrasRepository.kt` | 671/676 (99.3%) | 27/28 (96.4%) | 30/31 (96.8%) | 61/61 (100.0%) |
| Aggregate (summed counters, 6 files; 2 string resource files excluded, none unmeasured) | 16183/16549 (97.8%) | 724/805 (89.9%) | 399/439 (90.9%) | 1319/1322 (99.8%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `EventLinksPanel.kt`: none | Panel loads for EVENT/eventId, reloads on every appearance and on refresh | Fully covered. The round-1 partial branch (`refresh > 0`) was removed by the CR-2 fix. |
| `EventEditorScreen.kt`: lines 283, 486; 6 branches; 32 functions | Pre-existing editor code outside the task hunks; Compose-generated lambdas | Not added: the task's linksPanel slot, openLinked (InApp and Web), the in-place SearchItemScreen and BackHandler are covered |
| `EventEditorSections.kt`: line 595; 14 branches | Pre-existing sections outside the task hunks | Not added: the task's `LinksSection(panel = ...)` path is covered |
| `EventEditorViewModel.kt`: 60 of 458 branches | Mostly pre-existing editor logic; the task's createLinked refresh change is covered by EventEditorViewModelExtrasTest | Not added: outside this task's changed behavior |

## Unable to test

- AC7 real device / emulator check: no device available to this helper; the parent must skip it with a reason or run
  it.
- Full Android unit suite: not run here; covered by the parent's `.taskcheck`.
- Opening an EMAIL or TASK linked item in place was not tested with real screens (a NOTE was). Routing per type
  belongs to the shared SearchItemScreen, not to this task.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The final revision passes ktlint, Android
lint and all 585 calendar tests after a 2-line lint fix to the round-1 test file (`remember` around the test's state
holder). The parent must commit that change, and the code review should take a quick look at it.
