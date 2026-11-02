# Test Report

Date: 2026-11-02

Task: and-cal-delete - Android calendar: delete events (incl. repeating series scope)

Scope: `git diff a891e7545ce38c91bc3a680b0ec45cb87aa3361d..b0181e6c8894fe45ca601fc2f063ccab3a4bfa12` on branch main. Production code is in the implementation commit 61c23fd4de3594922600d9e428f1ae656c2737bb. Commit b0181e6c8 adds only the tests from this review (with ktlintFormat applied) and the review reports; there are no production changes between 61c23fd4d and b0181e6c8. Final reviewed revision: b0181e6c8894fe45ca601fc2f063ccab3a4bfa12.

Requirements:
- `tasks/and-cal-delete.md` acceptance criteria and recorded assumptions (Delete as a red text button in the top bar; 400 -> "The event could not be deleted.", 409 -> conflict message)
- `docs/ARCHITECTURE.md` ("Coverage", Android conventions), `AGENTS.md`
- Existing test conventions in `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/` (Robolectric + Compose test rule, MockK, StandardTestDispatcher)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/CalendarWithEditorDeleteTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModelDeleteTest.kt` (4 tests added)
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorDeleteScreenTest.kt` (2 tests added)

Snapshot: sha256 of the three files at b0181e6c8 (the working tree was clean when they were computed):
- CalendarWithEditorDeleteTest.kt: f5873cfad2344e5d836a555d7376651d1466d2fab4e63b3e1660f97de3497005 (ktlintFormat changed only formatting: one `assertTrue` call is now wrapped; I re-read the final file and every assertion is still there)
- EventEditorViewModelDeleteTest.kt: 07acbe02825b05e7f98aece93f9309096b52b28f43ff7155ed8c9265f882d31e
- EventEditorDeleteScreenTest.kt: 5a10b633c3991d7eb242b1657b0c352aa49ce76af5e0752ccaa2b12e650ef097

No production files were modified by the test-writer.

## Tests added or updated

Count: 10 (4 new integration tests, 4 view model tests, 2 Compose tests)

Review of the tests that came with the implementation: the view model tests (single event, each scope, cancelling either prompt, 412 with reload, 404, offline, other refusal, no delete for new or read-only events or while a delete runs), the Compose tests (when the button shows, the confirmation, the delete scope title, the failure message, the German confirmation) and the repository tests (DELETE path, scope, occurrence, If-Match, status mapping) are sound and match the criteria. Gaps found and closed:

- Criterion 2 "the agenda/day/week views no longer show the event (cache updated, no manual refresh)" had no test. The new `CalendarWithEditorDeleteTest` drives the real `CalendarWithEditor` with a real `CalendarViewModel` and `EventEditorViewModel`. The repositories are mocked and the `AgendaCache` is an in-memory one.
- Criterion 4 "nothing is removed locally then" (offline) was checked only as editor state. It is now also checked against the views and the agenda cache.
- 409 (mapped to the conflict message per the recorded assumption) was untested for deletes.
- A generated occurrence that became an override must be deleted as that override (the reread result), not as the stale generated occurrence.
- Guards: Save while the confirmation is open, Close while the delete runs, a scope choice when no prompt is open.
- German texts of the Delete button, the delete scope title and the delete failure message; the scope prompt of a change keeps its own title.

New test names:
- CalendarWithEditorDeleteTest: `a deleted event disappears from the agenda and its cache once the editor closes`, `a deleted event disappears from the week view without a manual refresh`, `a deleted event disappears from the day view without a manual refresh`, `an offline delete keeps the editor open with its message and removes nothing locally`
- EventEditorViewModelDeleteTest: `a generated occurrence that became an override is deleted as that override, with the chosen scope`, `a delete answered with 409 says the event changed elsewhere`, `while the confirmation is shown a save does nothing, and the editor cannot be closed during the delete`, `a scope chosen for a delete that was not offered deletes nothing`
- EventEditorDeleteScreenTest: `the delete button, scope prompt and failure are German in German`, `the scope prompt of a change keeps its own title`

## Requirement coverage

The test-writer cannot run Gradle in this environment. The parent worker ran every command below on b0181e6c8 and passed on the results.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Delete action for an existing event in a writable calendar; not for new or read-only events, nor while loading or saving | EventEditorDeleteScreenTest `an existing event offers Delete...`, `no Delete for a new event, a read-only calendar, while loading or while saving`; EventEditorViewModelDeleteTest `new events, read-only calendars and a running delete offer no delete` | `cd android && ./gradlew testDebugUnitTest` | Pass |
| 2. Single event: confirmation, DELETE with If-Match, editor closes | EventEditorViewModelDeleteTest `a single event is deleted after a confirmation...`, `a cancelled confirmation...`; EventEditRepositoryTest `a single event is deleted without scope, with its version as If-Match`; EventEditorDeleteScreenTest `an existing event offers Delete, which asks for a confirmation` | `cd android && ./gradlew testDebugUnitTest` | Pass |
| 2. Agenda, day and week views and the agenda cache no longer show the event, with no manual refresh | CalendarWithEditorDeleteTest (the three "disappears" tests) | `cd android && ./gradlew testDebugUnitTest` | Pass (CalendarWithEditorDeleteTest 4/4) |
| 3. Series: scope choice sent as scope + occurrence; cancel deletes nothing | EventEditorViewModelDeleteTest `a series occurrence asks which occurrences to delete and sends each scope`, `a cancelled scope prompt deletes nothing...`; EventEditRepositoryTest `a generated occurrence is deleted at its master with scope and original start, an override directly`; EventEditorDeleteScreenTest `the scope prompt of a delete says so and can be cancelled` | `cd android && ./gradlew testDebugUnitTest` | Pass |
| 3. Override occurrence deleted as the override; stray scope choice ignored | EventEditorViewModelDeleteTest `a generated occurrence that became an override is deleted as that override...`, `a scope chosen for a delete that was not offered deletes nothing` | `cd android && ./gradlew testDebugUnitTest` | Pass (EventEditorViewModelDeleteTest 13/13) |
| 4. 412 gives the conflict message with Reload; 404 gone; offline message; other refusals | EventEditorViewModelDeleteTest 412 / 404 / offline / 400 tests; EventEditRepositoryTest `a refused delete reports its status...` | `cd android && ./gradlew testDebugUnitTest` | Pass (EventEditRepositoryTest 14/14) |
| 4. 409 conflict; offline removes nothing locally (views + cache) | EventEditorViewModelDeleteTest `a delete answered with 409...`; CalendarWithEditorDeleteTest `an offline delete keeps the editor open with its message and removes nothing locally` | `cd android && ./gradlew testDebugUnitTest` | Pass |
| Guards (no save during the confirmation, no close during the delete) | EventEditorViewModelDeleteTest `while the confirmation is shown a save does nothing...` | `cd android && ./gradlew testDebugUnitTest` | Pass |
| 5. Strings in English and German | EventEditorDeleteScreenTest `the delete texts are German in German`, `the delete button, scope prompt and failure are German in German`, `the scope prompt of a change keeps its own title` | `cd android && ./gradlew testDebugUnitTest` | Pass (EventEditorDeleteScreenTest 7/7) |
| 6. ViewModel tests + a Compose test; ktlintCheck testDebugUnitTest lintDebug passes | all of the above | `cd android && ./gradlew ktlintCheck` and `./gradlew testDebugUnitTest createDebugUnitTestCoverageReport` on b0181e6c8; `./gradlew ktlintCheck testDebugUnitTest lintDebug` on 61c23fd4d | Pass. lintDebug last ran on 61c23fd4d; the production code and resources are unchanged since then, and test sources are outside lintDebug's scope |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest` (run by the parent worker on b0181e6c8; per-suite results read from its results)

Result:

Pass. CalendarWithEditorDeleteTest 4/4, EventEditorDeleteScreenTest 7/7, EventEditorViewModelDeleteTest 13/13, EventEditRepositoryTest 14/14. Neighbouring suites: EventEditorScreenTest 17/17, EventEditorViewModelTest 20/20, EventEditorViewModelEdgeCasesTest 20/20, CalendarWithEditorTest 6/6. 0 failures.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck` and `cd android && ./gradlew testDebugUnitTest createDebugUnitTestCoverageReport` (full Android unit suite, run by the parent on b0181e6c8)

Result:

Pass (BUILD SUCCESSFUL for both).

## .taskcheck

Result:

Not run by the test-writer, because sub-agents cannot run Gradle here. The parent runs `sh scripts/check.sh` before `done`.

## Bugs found

None. No test failed, and reading the production code against the criteria turned up no defect.

## Coverage

Status: verified, measured by the parent worker. The test-writer read the provider's report directly and checked it against the parent's numbers.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id: and-cal-delete. Immutable base: a891e7545ce38c91bc3a680b0ec45cb87aa3361d. Measured HEAD: b0181e6c8894fe45ca601fc2f063ccab3a4bfa12. Task-owned commits: 61c23fd4d (implementation) and b0181e6c8 (tests and reports), as given by the parent. They match the task's `touches`.
- Source/test snapshot: b0181e6c8. The working tree was clean when the report was read, and the test file hashes are listed above.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditRepository.kt`, `EventEditorViewModel.kt`, `EventEditorScreen.kt`, `CalendarWithEditor.kt`. Excluded: `res/values/strings_calendar_edit.xml` and `res/values-de/strings_calendar_edit.xml`, which are resources and not instrumented; the string assertions and lint cover them. No renamed or deleted files.
- Provider and command: JaCoCo via AGP `enableUnitTestCoverage`. The parent ran `./gradlew testDebugUnitTest createDebugUnitTestCoverageReport` in `android/`, with the full debug unit suite.
- Run time and exit status: the report was written at 2026-11-02 10:56:08 +0100, and the run ended BUILD SUCCESSFUL (exit 0, as reported by the parent). Commit b0181e6c8 is timestamped 10:56:20 +0100. The parent says the tested files are the ones it committed.
- Generated report: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 d7005b535d71ddb713740a7d0380ee95cb81a531a97c4f9b0f3a9f0c20d5f49e.
- Freshness: the run came after the last test changes. Production code has not changed since 61c23fd4d.
- Final run after the latest test additions and production fixes: yes. No production fixes were needed.

Values are covered/total (percentage) from the JaCoCo counters. Statements are JaCoCo INSTRUCTION counters (JaCoCo has no statement counter) and functions are METHOD counters.

| Changed file | Statements (instructions) | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `calendar/edit/EventEditRepository.kt` | 597/631 (94.6%) | 43/48 (89.6%) | 34/35 (97.1%) | 90/95 (94.7%) |
| `calendar/edit/EventEditorViewModel.kt` | 1857/1930 (96.2%) | 186/214 (86.9%) | 51/52 (98.1%) | 211/211 (100%) |
| `calendar/edit/EventEditorScreen.kt` | 4319/4479 (96.4%) | 95/100 (95.0%) | 99/121 (81.8%) | 286/288 (99.3%) |
| `calendar/edit/CalendarWithEditor.kt` | 751/788 (95.3%) | 22/32 (68.8%) | 10/11 (90.9%) | 46/46 (100%) |
| Aggregate (summed counters, all 4 files; the 2 string resources are excluded) | 7524/7828 (96.1%) | 346/394 (87.8%) | 194/219 (88.6%) | 633/640 (98.9%) |

These are whole-file numbers. They include code from earlier tasks in the same files and hits from earlier tests.

Uncovered or partly covered locations, read from report.xml:

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `EventEditRepository.kt:172-173, 180-183` (CancellationException, IllegalArgumentException and DateTimeException catches in `call`) | Shared error mapping from earlier tasks; delete uses it unchanged | Not part of this task's change. The delete outcomes (204, 412, 404, 5xx) are tested in EventEditRepositoryTest |
| `EventEditRepository.kt:92, 113, 198, 226, 227` (one branch each) | Earlier-task code (`inSeries`, reread, body mapping) | Outside this task's change; no test added |
| `EventEditorViewModel.kt:220` (2 of 6 branches: `delete()` called again while the scope prompt or the confirmation is already open) | Guard against a second tap on Delete | Justified gap. The open dialog is modal and covers the button, so this cannot be reached from the UI. The equivalent guard for a running delete is tested |
| `EventEditorViewModel.kt:199-200, 232-233` (null `occurrence`/`form` guards, and `canDelete` turning false after the confirmation was shown) | Defensive | Not reachable through the public API: reload clears the confirmation and prompt, and close resets the state |
| `EventEditorViewModel.kt:72, 82, 89, 92, 142, 168, 174, 182, 185, 206, 285, 310, 434-439` | Earlier-task code (validation, save, create retry id, load) | Outside this task's change |
| `EventEditorScreen.kt:174` (LOAD_FAILED message), `:376`, `:112` (BackHandler while saving), `:369`, `:398`, and the 22 missed methods (mostly Compose-generated lambdas such as date and time picker callbacks) | Earlier-task UI | Outside this task's change. The new Delete button, confirmation dialog, delete scope title and DELETE_FAILED message have no missed lines or branches |
| `CalendarWithEditor.kt:34, 67, 75` (default-parameter `hiltViewModel()` branches and Kotlin null-check branches in earlier lambdas) | Hilt defaults, which the tests replace with explicit view models | Not meaningfully testable in a unit test. The new delete wiring is covered by CalendarWithEditorDeleteTest |

## Unable to test

- Deletion at a synced provider is server behavior. The server is unchanged by this task, so it is outside the Android tests.
- The red color of the Delete button is visual only and is not asserted; the existing tests make no color assertions.
- The test-writer could not run tests or coverage itself, because Gradle is denied to sub-agents here. The parent ran them, and the coverage numbers above are read from the provider's report.

## Summary

Tests added and all validation passed. Task-scoped coverage is sufficient and no production defects were found.
