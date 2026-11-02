# Test Report

Date: 2026-11-02

Task: and-cal-delete - Android calendar: delete events (incl. repeating series scope)

Scope: `git diff a891e7545ce38c91bc3a680b0ec45cb87aa3361d..61c23fd4de3594922600d9e428f1ae656c2737bb` (one task commit, 61c23fd4d, on branch main; HEAD = 61c23fd4d at review time) plus the uncommitted test additions listed below.

Requirements:
- `tasks/and-cal-delete.md` acceptance criteria and recorded assumptions (Delete as a red text button in the top bar; 400 -> "The event could not be deleted.", 409 -> conflict message)
- `docs/ARCHITECTURE.md` ("Coverage", Android conventions), `AGENTS.md`
- Existing test conventions in `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/` (Robolectric + Compose test rule, MockK, StandardTestDispatcher)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/CalendarWithEditorDeleteTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModelDeleteTest.kt` (4 tests added)
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorDeleteScreenTest.kt` (2 tests added)

Snapshot (sha256 of the changed test files after this run):
- CalendarWithEditorDeleteTest.kt: 1a97d6234f429f9f5017dcf3beef6cbe9253ba545ece8dd038d2c55b8383f392
- EventEditorViewModelDeleteTest.kt: 07acbe02825b05e7f98aece93f9309096b52b28f43ff7155ed8c9265f882d31e
- EventEditorDeleteScreenTest.kt: 5a10b633c3991d7eb242b1657b0c352aa49ce76af5e0752ccaa2b12e650ef097

No production files were modified.

## Tests added or updated

Count: 10 (4 new integration tests, 4 view model tests, 2 Compose tests)

Review of the existing task tests: the view model tests (single, each scope, cancel of both prompts, 412 with reload, 404, offline, other refusal, not offered for new / read-only / running delete), the Compose tests (button visibility rules, confirmation, delete scope title, failure message, German confirmation) and the repository tests (DELETE path, scope, occurrence, If-Match, status mapping) are sound and match the criteria. Gaps found and closed:

- Criterion 2 "the agenda/day/week views no longer show the event (cache updated, no manual refresh)" had no test at all: nothing exercised the editor closing and the views reloading. New `CalendarWithEditorDeleteTest` drives the real `CalendarWithEditor` with a real `CalendarViewModel` and `EventEditorViewModel` (repositories mocked, an in-memory `AgendaCache`).
- Criterion 4 "nothing is removed locally then" (offline) was checked only as editor state; now also checked against the views and the agenda cache.
- 409 (mapped to the conflict message per the recorded assumption) was untested for deletes.
- A generated occurrence that became an override must be deleted as that override (the reread result), not the stale generated occurrence.
- Guards: Save while the confirmation is open, Close while the delete runs, a stray scope choice without a prompt.
- German texts of the Delete button, the delete scope title and the delete failure message; the change scope prompt keeps its own title.

New test names:
- CalendarWithEditorDeleteTest: `a deleted event disappears from the agenda and its cache once the editor closes`, `a deleted event disappears from the week view without a manual refresh`, `a deleted event disappears from the day view without a manual refresh`, `an offline delete keeps the editor open with its message and removes nothing locally`
- EventEditorViewModelDeleteTest: `a generated occurrence that became an override is deleted as that override, with the chosen scope`, `a delete answered with 409 says the event changed elsewhere`, `while the confirmation is shown a save does nothing, and the editor cannot be closed during the delete`, `a scope chosen for a delete that was not offered deletes nothing`
- EventEditorDeleteScreenTest: `the delete button, scope prompt and failure are German in German`, `the scope prompt of a change keeps its own title`

## Requirement coverage

Results marked "Pass (parent)" were run by the parent worker before this review; the tests added here have not been run yet (sub-agents cannot run Gradle here) and must be run by the parent.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Delete action for an existing event in a writable calendar; not for new, read-only, loading, saving | EventEditorDeleteScreenTest `an existing event offers Delete...`, `no Delete for a new event, a read-only calendar, while loading or while saving`; EventEditorViewModelDeleteTest `new events, read-only calendars and a running delete offer no delete` | `cd android && ./gradlew testDebugUnitTest` | Pass (parent) |
| 2. Single event: confirmation, DELETE with If-Match, editor closes | EventEditorViewModelDeleteTest `a single event is deleted after a confirmation...`, `a cancelled confirmation...`; EventEditRepositoryTest `a single event is deleted without scope, with its version as If-Match`; EventEditorDeleteScreenTest `an existing event offers Delete, which asks for a confirmation` | `cd android && ./gradlew testDebugUnitTest` | Pass (parent) |
| 2. Agenda/day/week views and cache no longer show the event, no manual refresh | CalendarWithEditorDeleteTest (three "disappears" tests) | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.CalendarWithEditorDeleteTest'` | Not run: added by this review, parent must run |
| 3. Series: scope choice sent as scope + occurrence; cancel deletes nothing | EventEditorViewModelDeleteTest `a series occurrence asks which occurrences to delete and sends each scope`, `a cancelled scope prompt deletes nothing...`; EventEditRepositoryTest `a generated occurrence is deleted at its master with scope and original start, an override directly`; EventEditorDeleteScreenTest `the scope prompt of a delete says so and can be cancelled` | `cd android && ./gradlew testDebugUnitTest` | Pass (parent) |
| 3. Override occurrence deleted as the override; stray scope choice ignored | EventEditorViewModelDeleteTest `a generated occurrence that became an override is deleted as that override...`, `a scope chosen for a delete that was not offered deletes nothing` | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.EventEditorViewModelDeleteTest'` | Not run: added by this review, parent must run |
| 4. 412 conflict with Reload, 404 gone, offline message, other refusal | EventEditorViewModelDeleteTest 412 / 404 / offline / 400 tests; EventEditRepositoryTest `a refused delete reports its status...` | `cd android && ./gradlew testDebugUnitTest` | Pass (parent) |
| 4. 409 conflict; offline removes nothing locally (views + cache) | EventEditorViewModelDeleteTest `a delete answered with 409...`; CalendarWithEditorDeleteTest `an offline delete keeps the editor open with its message and removes nothing locally` | see the two focused commands above | Not run: added by this review, parent must run |
| Guards (no save during confirmation, no close during delete) | EventEditorViewModelDeleteTest `while the confirmation is shown a save does nothing...` | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.EventEditorViewModelDeleteTest'` | Not run: added by this review, parent must run |
| 5. Strings in English and German | EventEditorDeleteScreenTest `the delete texts are German in German` (Pass, parent), `the delete button, scope prompt and failure are German in German`, `the scope prompt of a change keeps its own title` (new); lintDebug checks missing translations | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.EventEditorDeleteScreenTest' lintDebug` | Partly Pass (parent); new tests Not run: parent must run |
| 6. VM tests + Compose test; ktlintCheck testDebugUnitTest lintDebug passes | all of the above | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` | Pass (parent, before these additions); must be rerun with the new tests |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.*Delete*'` (covers the three changed files)

Result:

Not run by the test-writer: Gradle is denied to sub-agents in this environment. Before this review the parent ran EventEditorViewModelDeleteTest 9/9, EventEditorDeleteScreenTest 5/5, EventEditRepositoryTest 14/14 -> Pass. The parent must run the command above and then `./gradlew ktlintCheck` for the new code.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Pass (run by the parent on 61c23fd4d before the tests of this review were added). Not rerun with the additions.

## .taskcheck

Result:

Not run by the test-writer (Gradle denied to sub-agents); the parent runs `sh scripts/check.sh`.

## Bugs found

None. Reading the production code against the criteria found no defect; the new tests encode the required behavior and are expected to pass. If one fails, check first whether the test or the production code is wrong before changing either.

## Coverage

Status: unavailable to the test-writer: the Android coverage command (`cd android && ./gradlew createDebugUnitTestCoverageReport`, docs/ARCHITECTURE.md "Coverage") needs Gradle, which sub-agents cannot run here. The parent measures it.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-cal-delete; base a891e7545ce38c91bc3a680b0ec45cb87aa3361d; HEAD 61c23fd4de3594922600d9e428f1ae656c2737bb; single task commit 61c23fd4d, given by the parent and matching the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 61c23fd4d plus the three test files with the sha256 values above.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditRepository.kt`, `EventEditorViewModel.kt`, `EventEditorScreen.kt`, `CalendarWithEditor.kt`. Excluded: `res/values/strings_calendar_edit.xml`, `res/values-de/strings_calendar_edit.xml` (resources, not instrumented; covered by the string assertions and lint).
- Provider/version, command, working directory, and test selection: JaCoCo via AGP, `./gradlew createDebugUnitTestCoverageReport` in `android/`, all debug unit tests. Not run by the test-writer.
- Run start/completion time and exit status: none (not run).
- Generated report paths and report hashes/run identifier: would be `android/app/build/reports/coverage/test/debug/report.xml`; none generated by this review.
- Evidence outputs are fresh and inputs stayed unchanged: not applicable, no run.
- Final run after latest test additions and production fixes: no; the parent must run it on the snapshot above.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `calendar/edit/EventEditRepository.kt` | N/A (not measured) | N/A (not measured) | N/A (not measured) | N/A (not measured) |
| `calendar/edit/EventEditorViewModel.kt` | N/A (not measured) | N/A (not measured) | N/A (not measured) | N/A (not measured) |
| `calendar/edit/EventEditorScreen.kt` | N/A (not measured) | N/A (not measured) | N/A (not measured) | N/A (not measured) |
| `calendar/edit/CalendarWithEditor.kt` | N/A (not measured) | N/A (not measured) | N/A (not measured) | N/A (not measured) |

Follow-up for the parent: run `cd android && ./gradlew createDebugUnitTestCoverageReport` and read the `sourcefile` counters for the four files above from `report.xml` (package `ch/sonensei/cockpit/calendar/edit`).

Manual branch review of the changed code (instead of measured gaps):

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CalendarWithEditor.kt` delete callbacks wiring | Criterion 2 (views refresh after a delete) | CalendarWithEditorDeleteTest added |
| `EventEditorViewModel.kt` send(): `result.status in CONFLICTS` with 409 | Assumption: 409 -> conflict | 409 test added |
| `EventEditorViewModel.kt` confirmDelete(): `canDelete` false after the confirmation was shown | Defensive (the confirmation is cleared on reload; canDelete cannot turn false while it is shown) | Left untested: not reachable through the public API |
| `EventEditorViewModel.kt` send(): `creating -> failed` for a delete | Defensive (delete needs an existing event) | Left untested: not reachable |
| `EventEditRepository.kt` delete(): non-series event with a scope passed | Scope dropped for a single event | Covered by the existing repository test (passes ALL, asserts no scope) |

## Unable to test

- Running any test or coverage: Gradle is denied to sub-agents here; the parent runs the commands above.
- Deletion at a synced provider is server behavior (server unchanged by this task), out of scope for Android tests.
- The red color of the Delete button is visual only; not asserted (no color assertions in the existing tests).

## Summary

Tests added; no production defects found. Validation of the added tests (focused run, ktlintCheck, coverage) is pending and must be run by the parent worker.
