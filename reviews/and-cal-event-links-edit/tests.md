# Test Report

Date: 2026-11-28

Task: and-cal-event-links-edit - Android: add and remove links from the calendar event editor

Scope: base 2d77642c3aeb57d9965920d058d27b90de0d5487 to HEAD b3df6332adfd5061c98cc0c59c7cd40636a3d932, Android only
(`git diff 2d77642c3 HEAD -- android`). The task commits named by the parent (7b67ee70b, f62e2cd08, c7e7312ce,
cc166d8b0) were rebased while this helper ran (now ef6a6cf95, 3a198ab64, 01a782ca4, 24c2d4b13, plus b3df6332a with only
the review report). `git diff cc166d8b0 HEAD -- android` is empty, so the measured Android code is the same.
Uncommitted input: the new test file only (untracked).

Requirements:
- tasks/and-cal-event-links-edit.md acceptance criteria and the two recorded assumptions (open in place keeps unsaved
  edits; read-only events still show the panel)
- AGENTS.md, docs/ARCHITECTURE.md "Coverage"
- Existing test patterns: notes/NoteEditorLinksRouteInAppTest (Hilt activity with BindValue repositories),
  core/links/LinkedItemsPanelTest

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorLinksInAppTest.kt` (new)

## Tests added or updated

Count: 8 (new). The test renders `EventEditorContent` with its default links panel, so the real `EventLinksPanel`,
`LinkedItemsSection` and the Hilt `LinkedItemsViewModel` run inside `MainActivity` (Hilt), with a mocked
`LinksRepository` and `NotesRepository` (BindValue). Data is fictitious.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: saved event, "Add link" opens search, picking an item links it to item EVENT/eventId and shows it right away | `a saved event shows the shared links panel inside its links section, loaded for item EVENT with the event id`; `Add link searches all items, links the picked email to the event and shows it right away` (new); series id for a recurring event: existing EventEditorViewModelExtrasTest | `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*'` | Pass |
| AC2: remove with the cross | `a linked item is removed with its cross from the event editor`; `a failed removal is shown and keeps the link and the editor` (new) | same | Pass |
| AC2: open a linked item, back returns with unsaved edits | `a linked note opens in place of the editor, and back returns to the editor with its unsaved edits` (new, real panel and real NoteEditorRoute); existing fake-slot test in EventEditorExtrasScreenTest | same | Pass |
| AC3: create task / note from event still works and the result shows in the panel | `a task created from the event reloads the panel so the task shows` (new: refresh 0 to 1 reloads the real view model); existing EventEditorViewModelExtrasTest (refresh increments) and EventEditorExtrasScreenTest | same | Pass |
| AC4: new event shows no panel | `a new event shows no links panel and loads no links` (new); existing `a new event has no links panel` | same | Pass |
| AC4: read-only events | Existing EventEditorViewModelExtrasTest / screen tests (panel state set independent of calendar access); not re-tested here | same | Pass (existing tests) |
| AC5: offline / server error shows error and retry, editor stays usable | `an offline panel shows its error and retry, and the editor stays usable` (new) | same | Pass |
| AC6: ktlintCheck and unit tests green | whole calendar package | `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*' createDebugUnitTestCoverageReport` | Pass (ktlint, 584 calendar tests); lintDebug and the full suite not run here |
| AC7: real device / emulator check | none | none | Not run: no device or emulator in this helper |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.EventEditorLinksInAppTest'`

Result:

Pass (8 tests, 0 failures, 0 skipped).

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*' createDebugUnitTestCoverageReport`

Result:

Pass: ktlint clean (after wrapping one long line in the new test), 584 calendar tests, 0 failures, 0 errors, 0 skipped.
The full Android unit suite and `lintDebug` were not run by this helper (time budget); the parent runs them via `.taskcheck`
and the AC6 command.

## .taskcheck

Result:

Not run (left to the parent worker, which runs it before `done`).

## Bugs found

None. All new tests passed against the task implementation.

## Coverage

Status: verified (whole-file coverage of the task-changed Android production files, from calendar tests only).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-cal-event-links-edit; base
  2d77642c3aeb57d9965920d058d27b90de0d5487; HEAD b3df6332adfd5061c98cc0c59c7cd40636a3d932; scope from the parent's
  commit list and `git diff --name-only 2d77642c3 HEAD -- android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD b3df6332a plus untracked
  EventEditorLinksInAppTest.kt sha256 4911eabb45ceadb25e742998770dc230b737854cb973c8826a83991e60c213d6.
- Exact included files: calendar/edit/EventEditorExtrasState.kt, EventEditorScreen.kt, EventEditorSections.kt,
  EventEditorViewModel.kt, EventExtrasRepository.kt, EventLinksPanel.kt. Excluded: res/values/strings_calendar_edit.xml
  and res/values-de/strings_calendar_edit.xml (resources, only deletions, not executable). No renames; no deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP enableUnitTestCoverage; `./gradlew
  ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*' createDebugUnitTestCoverageReport` in
  `android/`; selection: all tests in package ch.sonensei.cockpit.calendar.
- Run start/completion time and exit status: completed about 2026-11-28 12:59 UTC; build succeeded (no FAILURE output).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`,
  sha256 prefix a7fae87b99204418, mtime 2026-11-28 12:59 UTC.
- Evidence outputs are fresh and inputs stayed unchanged: report regenerated by this run (Gradle reran the tests because
  the new test file changed); Android sources identical between cc166d8b0 and the measured HEAD.
- Final run after latest test additions and production fixes: yes (no production fixes so far).

Values below use covered/total (percentage). Statements = JaCoCo instructions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `calendar/edit/EventLinksPanel.kt` | 117/118 (99.2%) | 5/6 (83.3%) | 2/2 (100%) | 7/7 (100%) |
| `calendar/edit/EventEditorExtrasState.kt` | 276/276 (100%) | 20/20 (100%) | 28/28 (100%) | 31/31 (100%) |
| `calendar/edit/EventEditorScreen.kt` | 4903/5087 (96.4%) | 101/107 (94.4%) | 129/161 (80.1%) | 345/347 (99.4%) |
| `calendar/edit/EventEditorSections.kt` | 5519/5587 (98.8%) | 176/190 (92.6%) | 110/116 (94.8%) | 406/407 (99.8%) |
| `calendar/edit/EventEditorViewModel.kt` | 4709/4818 (97.7%) | 398/458 (86.9%) | 100/101 (99.0%) | 469/469 (100%) |
| `calendar/edit/EventExtrasRepository.kt` | 671/676 (99.3%) | 27/28 (96.4%) | 30/31 (96.8%) | 61/61 (100%) |
| Aggregate (summed counters, 6 files; 2 string resource files excluded) | 16195/16562 (97.8%) | 727/809 (89.9%) | 399/439 (90.9%) | 1319/1322 (99.8%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `EventLinksPanel.kt`: 1 of 6 branches | Compose-generated branch around the LaunchedEffect/refresh; both the Hilt and non-Hilt paths and refresh 0 and 1 run | Justified gap: no line uncovered, remaining branch is compiler-generated |
| `EventEditorViewModel.kt`: 60 of 458 branches | Mostly pre-existing editor logic; the task's createLinked refresh change is covered by EventEditorViewModelExtrasTest | Not added: outside this task's changed behavior |
| `EventEditorScreen.kt` / `EventEditorSections.kt`: missed functions and branches | Compose-generated lambdas and pre-existing sections; the new linksPanel slot, openLinked (InApp and Web) and BackHandler are covered | Not added: no task behavior left untested |

## Unable to test

- AC7 real device / emulator check: no device available to this helper; the parent must skip it with a reason or run it.
- `lintDebug` and the full Android unit suite: not run here for time; covered by the parent's AC6 run and `.taskcheck`.
- Opening an EMAIL or TASK linked item in place was not exercised with real screens (a NOTE was); routing per type
  belongs to the shared SearchItemScreen, not to this task.

## Summary

Tests added and all validation passed (8 new Robolectric/Hilt tests of the real event-editor links panel; calendar
package 584 tests and ktlint green). No production defects found. Device check and lintDebug remain for the parent.
