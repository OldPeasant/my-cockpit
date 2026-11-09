# Test Report

Date: 2026-11-09

Task: and-notes-revisions — Note editor: revisions list and restore

Scope: `git diff c6a0de9a1e3722a9893e4d7684216c4a3453cbc7..758b571d04d40d27609441ba41ce60c8534fcad6` (task commit given by the parent). While testing, the parent rebased it: the task commit is now `3e886f651` and HEAD is `9e36b4f44` (adds only `reviews/` and `tasks/` changes). `git diff --stat 758b571d0 HEAD` shows no production change, so the tests ran on the same production code as the task commit.

Requirements:
- `tasks/and-notes-revisions.md` acceptance criteria
- Parent brief (repository revisions/restoreRevision, ViewModel open/retry/select/close/restore, NoteRevisionsDialog, the "Earlier versions" menu item, en/de strings)
- `api/openapi.yaml` (`listNoteRevisions`, `restoreNoteRevision`, `NoteRevisionResponse`)
- `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md`

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteRevisionsRepositoryTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorRevisionsViewModelTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteRevisionsDialogTest.kt` (new, Robolectric Compose, also covers `revisionSummary`)

No existing test was changed.

## Tests added or updated

Count: 39 (8 repository, 20 ViewModel, 11 Compose/unit)

## Requirement coverage

Focused command (F): `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' createDebugUnitTestCoverageReport`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Repository lists a note's revisions: GET `/api/v1/notes/{id}/revisions`, mapped number/title (also null)/body/savedAt | NoteRevisionsRepositoryTest: `the revisions are asked for the note and mapped with their number, title, body and save time` | F | Pass |
| Revisions newest first, also when the server sends them in another order; empty list | `the revisions are newest first, also when the server sends them in another order`, `a note without revisions has an empty list` | F | Pass |
| Failed list: 404 -> Failed, 5xx and no connection -> Offline, bad JSON or bad date -> Failed(null) | `revisions that cannot load are offline for no connection or a server error, failed for a refusal or bad answer` | F | Pass |
| Restore: POST `/revisions/{n}/restore` with If-Match = version, Saved with the returned note | `restore posts the revision number with the note's version as If-Match and returns the restored note` | F | Pass |
| Restore conflict: 412/409 -> Conflict(current) after a reload; 404 -> Conflict(null) | `a restore refused as outdated (412 or 409) ...`, `a restore of a note deleted elsewhere is a conflict without a current note` | F | Pass |
| Restore: other refusal -> Failed, 5xx or no connection -> Offline, unreadable -> Failed(null), reload failure after 412 -> Failed(403) | `other refusals of a restore are failures, a server error or no connection is offline` | F | Pass |
| VM open: loading state, then newest first with the newest selected | NoteEditorRevisionsViewModelTest: `opening the earlier versions shows them loading, then newest first with the newest selected` | F | Pass |
| VM open: empty list with nothing selected; Restore then does nothing | `a note without earlier versions shows an empty list with nothing selected, and Restore does nothing` | F | Pass |
| VM open: offline/failed errors; Retry loads again (loading state in between) | `versions that cannot load show offline or failed, and Retry loads them again`, `Retry does nothing when the list loaded, or when it is closed` | F | Pass |
| VM select: a listed number is selected; unknown number, no list or a loading list changes nothing | `selecting a version shows it, a number not in the list changes nothing`, `selecting while the list loads changes nothing` | F | Pass |
| VM close; a late or older answer never reopens or replaces the list (out-of-order answers) | `closing hides the list, and a load still running does not open it again`, `an older load that ends last does not replace the list opened again`, `opening the list again while a load runs replaces it, so its older answer never shows` | F | Pass |
| VM restore: unsaved text saved first (order checked), then the restored title/body taken, tags kept, SAVED, list closed, changing reset | `restore saves the text written here first, then takes the restored title and body and closes the list`, `restoring a version without a title leaves the title empty, and nothing saved first when nothing changed` | F | Pass |
| VM restore conflict: latest version shown with CHANGE_CONFLICT when nothing is unsaved; deleted elsewhere -> conflict | `a restore refused because the note changed elsewhere shows the latest version when nothing is unsaved`, `a restore of a note deleted elsewhere is a conflict to decide` | F | Pass |
| VM restore offline/refused: message, text kept | `a restore without a connection or refused tells so and keeps the text`, `when the text written here cannot be saved first, nothing is restored` | F | Pass |
| VM: not allowed while not editable or busy (new note, loading note, conflict, a pin running, no list or loading list) | `the earlier versions cannot be opened for a new note not saved yet`, `the earlier versions cannot be opened while the note loads`, `during a conflict the versions cannot be opened, and an open list cannot restore`, `restore is ignored while a pin runs`, `restore does nothing without an open list, or while the list loads` | F | Pass |
| Compose: the menu item "Earlier versions" calls openRevisions and closes the menu | NoteRevisionsDialogTest: `the editor menu offers Earlier versions, which opens them` | F | Pass |
| Compose: list items (count, order, title / first line / "Empty note", selected state) | `the list shows each version newest first with its title, else its first line, else Empty note` | F | Pass |
| Compose: the selected version is previewed; tapping an item calls select | `the selected version is previewed, and tapping another selects it` | F | Pass |
| Compose: Restore and Close callbacks; Restore disabled with no selection | `Restore and Close report to the editor`, `Restore is disabled when no version is selected` | F | Pass |
| Compose: loading text; empty text; offline/failed texts with Retry | `while loading there is a loading text and neither Restore nor Retry`, `a note without earlier versions says so, without Restore`, `versions that could not load tell why and offer Retry` | F | Pass |
| Compose: dialog hidden during a conflict (not editable) | `the list is not shown while a conflict waits` | F | Pass |
| New strings in English and German | English texts asserted in the tests above; `the earlier versions are translated to German` (menu, Empty note, Restore, Close, empty text) | F | Pass |
| `revisionSummary`: trimmed title, else first non-empty body line, else null | `revisionSummary is the trimmed title, else the first non-empty body line, else null` | F | Pass |
| `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass | ktlintCheck + full `testDebugUnitTest` (see below) | `cd android && ./gradlew testDebugUnitTest` | ktlintCheck Pass, testDebugUnitTest Pass; `lintDebug` and `scripts/check.sh` Not run: left to the parent's final `.taskcheck` and `done` |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' createDebugUnitTestCoverageReport`

Result:

Pass: 21 suites in the `notes` package, 316 tests, 0 failures, 0 errors, 0 skipped (the 39 new tests among them). ktlintCheck passes (an earlier run flagged two closing-quote indents in the new repository test, fixed before this run).

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest`

Result:

Pass: 432 suites, 5333 tests, 0 failures, 0 errors, 0 skipped (from `android/app/build/test-results/testDebugUnitTest/*.xml`, after the final test edit).

## .taskcheck

Result:

Not run: `sh scripts/check.sh` (lint + unit tests of all parts) is left to the parent, which runs it before `tasks.py done`. Its Android unit tests and ktlintCheck passed above.

## Bugs found

No failing test, so no production defect is confirmed. One behavior should be reviewed by the parent because the requirements do not define it:

### Text typed while a restore is in flight is dropped without being saved

Finding ID:

`TW-1`

Test:

None. The requirement is ambiguous, so no failing test was added.

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt:592-604` (`restoreRevision`, the `onSaved` lambda)

Requirement:

The KDoc of `restoreRevision` says "The text written here is saved first, so it becomes a revision too and the restore can be undone."

Expected behavior:

Unclear. Either text typed after Restore is tapped is saved too, or the editor blocks typing while `changing` is true.

Actual behavior:

The list closes at once and the editor stays editable (`editable` ignores `changing`). If the user types while `notes.restoreRevision` runs, `onSaved` overwrites `title`/`body` with the restored note and sets the status to SAVED. The pending autosave then finds nothing to save. The typed text is lost and does not become a revision. This follows from reading the code; no test reproduces it.

Severity:

Low (a narrow time window, only while the request runs).

Fix required:

`Parent decides. This may need a production change or an assumption.`

## Coverage

Status: verified (task-scoped, whole-file, from a fresh JaCoCo run of the `notes` test package)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. I intersected the added/modified lines from the `git diff -U0 c6a0de9a1e..758b571d0` hunk headers with the per-line entries in the JaCoCo XML, counting only lines JaCoCo reports as executable. Result: 179/179 changed executable lines covered (100%), and 118/122 branches on changed lines (96.7%).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-notes-revisions`; base `c6a0de9a1e3722a9893e4d7684216c4a3453cbc7`; task commit `758b571d04d40d27609441ba41ce60c8534fcad6` (given by the parent), rebased to `3e886f651` with identical production content. HEAD at measurement: `9e36b4f44432a372266c8de7131df8b5d8414840`.
- Source/test snapshot identifier (including uncommitted inputs): production = HEAD (clean). Uncommitted, untracked test files with their sha256: `NoteRevisionsDialogTest.kt` 4b43710cea14e960358818627faeb1297e5666ad17a0a0f0d72313ec4350a502, `NoteRevisionsRepositoryTest.kt` 1af2ad14ded9831c9014bbef825686d62b212fd2be01d2aa5b9d470816e23b9e, `NoteEditorRevisionsViewModelTest.kt` 57d201297c4d575340925076479aaa65ea79277e43aff1141c6428e1fd08bddb. The hashes matched before and after the run.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/notes/{NoteEditorScreen.kt, NoteEditorViewModel.kt, NoteRevisionsDialog.kt, NotesModels.kt, NotesRepository.kt}`. Excluded: `res/values/strings_notes.xml` and `res/values-de/strings_notes.xml` (resources, not instrumented; covered by the en/de Compose assertions). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo through AGP `enableUnitTestCoverage` plus the Gradle `jacoco` plugin (version not pinned in the build files). Command: `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' createDebugUnitTestCoverageReport`, run in `android/`. Only the `notes` test package ran.
- Run start/completion time and exit status: 2026-11-09T06:41:21Z to 06:41:53Z, BUILD SUCCESSFUL (no failure output). Test-result timestamps are 06:41:29Z to 06:41:48Z.
- Generated report paths and report hashes/run identifier: `android/app/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec` (written 06:41:48Z). `android/app/build/reports/coverage/test/debug/report.xml` (written 06:41:50Z) has sha256 18c6d56318d21f70a25159e3ce6b65bd7aaba089684271ccc60f1beec1cf43f8.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The exec and XML files were written during this run, test results include the newly added 20th ViewModel test, and the test-file hashes matched before and after the run.
- Final run after latest test additions and production fixes: yes. This run came after the last test edit. No production fix happened during testing. A later unfiltered `testDebugUnitTest` rewrote the `.exec`, but the numbers below come from the XML report above.

Values below use covered/total (percentage). Statements are JaCoCo instructions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `notes/NoteEditorViewModel.kt` | 4024/4054 (99.3%) | 291/312 (93.3%) | 109/111 (98.2%) | 353/353 (100.0%) |
| `notes/NoteEditorScreen.kt` | 5029/5137 (97.9%) | 169/193 (87.6%) | 86/106 (81.1%) | 352/358 (98.3%) |
| `notes/NoteRevisionsDialog.kt` | 1113/1121 (99.3%) | 58/58 (100.0%) | 21/25 (84.0%) | 95/95 (100.0%) |
| `notes/NotesModels.kt` | 148/148 (100.0%) | N/A (0 branches) | 23/23 (100.0%) | 33/33 (100.0%) |
| `notes/NotesRepository.kt` | 1217/1235 (98.5%) | 63/66 (95.5%) | 52/54 (96.3%) | 127/131 (96.9%) |
| Aggregate (summed counters, all 5 files measured, none missing) | 11531/11695 (98.6%) | 581/629 (92.4%) | 291/319 (91.2%) | 960/970 (99.0%) |

Uncovered spots on the task's changed lines (gaps in the old code of these files predate this task and are outside its scope):

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `NoteEditorViewModel.kt:75` (`selectedRevision` with a null list) | Restore while the list loads | Covered by the added test `restore does nothing without an open list, or while the list loads` |
| `NoteEditorViewModel.kt:595` (`restoreRevision` without a list) | Restore without an open list | Covered by the same added test |
| `NoteEditorViewModel.kt:554` (`retryRevisions` when `note == null`) | Retry for an unsaved new note | Not materially reachable: no list opens without a note. Left uncovered |
| `NoteEditorViewModel.kt:570` (`it.revisions == null` in the load result) | List closed before the answer arrived | Defensive: `closeRevisions` cancels the job first. The observable behavior is tested (`closing hides the list, and a load still running does not open it again`) |
| `NoteEditorViewModel.kt:582` (`revisionsJob?.cancel()` with no job) | Close without a load | Trivial null-safe branch |
| `NoteEditorViewModel.kt:597` (`state.value.note == null` in restore) | Restore without a note | Unreachable: a list only opens with a note |
| `NoteRevisionsDialog.kt:42-45`, `NoteEditorScreen.kt:95` | The default no-op lambdas of `NoteRevisionsCallbacks` / `NoteEditorCallbacks.openRevisions` | Non-material defaults. The real wiring in `NoteEditorRoute` (lines 209-216) is covered |

## Unable to test

- The date/time label of a revision (`formatSavedAt`) is executed but its text is not asserted, because it depends on the JVM time zone and locale. No follow-up needed.
- `lintDebug` and `sh scripts/check.sh` were not run by me (the parent runs `.taskcheck`).
- On-device behavior (a real AlertDialog on an emulator or device) was not tested. Robolectric Compose tests only.

## Summary

Tests added and all validation I ran passed: 39 new tests, the `notes` package (316 tests), the full Android unit suite (5333 tests) and ktlintCheck. Task-scoped changed-line coverage is 179/179 lines. No production defect is confirmed. TW-1 (text typed during an in-flight restore is dropped) is a Low ambiguity for the parent to decide.
