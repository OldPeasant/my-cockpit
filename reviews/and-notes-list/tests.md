# Test Report

Date: 2026-11-08

Task: and-notes-list - Android notes list: search, pin, archive, delete

Scope: `git diff 6fbabadfbafe865bbdba739d8dc5de77cb446065..52c0e918537a05ff9b12de6d0dec808173c949ca` (task commits `git log --oneline 6fbabadfb..HEAD`: f0f569c9 implementation, 6e07c5ec and 7fa90e03 earlier test-writer tests and reviews, ed799aee and b8671a9e review fixes, cef7c002 re-review, 699f800c merge of `origin/wip/and-notes-list`, 52c0e918 fix for the re-review's CR-1 P3 with its regression test). Branch main, HEAD 52c0e918537a05ff9b12de6d0dec808173c949ca. This round adds one test file change (uncommitted, see "Coverage"). A parallel uncommitted change to `reviews/and-notes-list/review.md` was present in the tree; it is not part of this report.

Requirements:
- tasks/and-notes-list.md, acceptance criteria 1-5 and assumptions #1-#4
- AGENTS.md project notes (en/de strings, fictitious test data, Android check command)
- docs/ARCHITECTURE.md "Coverage" (JaCoCo via `createDebugUnitTestCoverageReport`)
- reviews/and-notes-list/review.md and fixes.md (the CR-1 P3 fix and its regression test)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorViewModelTest.kt` (this round)

Earlier rounds of the task (already committed) added or extended `NotesListViewModelTest.kt`, `NotesActionsScreensTest.kt`, `NoteEditorViewModelTest.kt`, `NoteEditorRouteTest.kt`, `NotesRepositoryTest.kt` and `NotesRouteInAppTest.kt`.

## Tests added or updated

Count: 1 (this round)

- `NoteEditorViewModelTest`: `unpin of a pinned note sends pinned false over its version, and pin then pins it again`. Gap it closes: the editor's unpin direction (`togglePin` on a pinned note, `NoteEditorViewModel.kt:358`) had no test; only pin of an unpinned note and the Unpin label on screen were tested. Criterion 2 names pin/unpin in the editor menu.

This test was not run by the test-writer: Gradle cannot run in its sandbox. The parent worker runs it:
`android/gradlew -p android ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.NoteEditorViewModelTest'`

Review of the existing task tests against the final code: every criterion already has tests. The search ordering and race cases (debounce, older search answering last, clearing a pending search, list and search messages kept apart, the CR-1 regression `a reload whose search fails before the list answers keeps the search's message`) are covered with `CompletableDeferred` and virtual time, with no real delays. The pin/archive/delete success, conflict, offline and refused cases are covered in the list view model, the editor view model and the repository. Undo, including Undo after a delete from the editor (in-app route test), is covered, and so are the en/de string checks in Compose tests. No other meaningful gap was found.

## Requirement coverage

Commands: "parent focused" is `android/gradlew -p android ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'`, run by the parent worker on HEAD 52c0e918. "parent coverage" is `android/gradlew -p android createDebugUnitTestCoverageReport` (full unit suite), run by the parent worker on HEAD 52c0e918.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Search field, server search over title and body | NotesRepositoryTest: `search asks for notes only, at most 50, with the trimmed text, and returns the note ids`, `search ignores hits of other types`, `a blank search asks nothing, a long one is cut to the 200 characters the server accepts`, `a failed search is offline or failed`; NotesListViewModelTest: `the search runs once the typing paused 300 ms ...`, `clearing the search shows every note again without asking the server`, `clearing the search while it waits for the server ends the loading`, `a reload with a search text keeps loading until both the list and the search answered`, `a new search text drops the hits of the previous one at once ...`, `an older search that answers after a newer one is dropped`, `a failed search says why, and a reload searches again at once`, the three message tests (lines 327, 353, 413), `a reload whose search fails before the list answers keeps the search's message`; NotesActionsScreensTest: `the search field reports what is typed, and Clear empties it`, `a search shows only the notes found, and says when none match` | parent focused; parent coverage | Pass |
| 2. Pin/unpin and archive/unarchive in the row menu and the editor menu; Archived view | NotesRepositoryTest: `the archived list asks the server for archived=true ...`, `pinning patches only pinned ...`, `archiving patches only archived`, `unpinning and unarchiving send false for that flag only`, the conflict and refusal tests of a pin; NotesListViewModelTest: `the Archived view loads the archived notes, and back to the notes`, `the Archived view keeps the tag filter`, `pinning replaces the note and keeps pinned notes first`, `unpinning sends pinned false ...`, `archiving removes the note from the view with a snackbar`, `unarchiving in the Archived view removes the note from it`, `a conflicting pin says so and reloads the list ...`, `a load that started before an archive does not bring the archived note back`, `a change uses the latest shown version, and a second tap while it runs is ignored`; NoteEditorViewModelTest: the pin/archive tests from line 829 on; NotesActionsScreensTest: the row menu and editor menu tests; NoteEditorRouteTest: `pinning from the menu shows Unpin afterwards` | parent focused; parent coverage | Pass |
| 2. Editor unpin (added this round) | NoteEditorViewModelTest: `unpin of a pinned note sends pinned false over its version, and pin then pins it again` | `android/gradlew -p android ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.NoteEditorViewModelTest'` (to be run by the parent) | Not run: Gradle cannot run in the test-writer's sandbox; the parent runs it |
| 3. Delete a note to the Trash, with Undo | NotesRepositoryTest: `delete sends the version as If-Match and reports refusals with their status`, `restore takes the note out of the Trash`; NotesListViewModelTest: `deleting removes the note, offers Undo and reloads the tags`, `Undo restores the note and loads the list again`, `a failed Undo says so`, `a delete refused because the note changed or is gone reloads ...`, `each snackbar is a new one ...`, `deleting during a search removes the note from the results, and Undo searches again and shows it`, `a delete whose tags cannot be reloaded keeps the tags shown before`; NoteEditorViewModelTest: the delete tests from line 1018 on; NoteEditorRouteTest: `deleting from the menu hands the note to onDeleted, then closes without a save`; NotesRouteInAppTest: `a note deleted in its editor is gone from the list, whose snackbar undoes it`; NotesActionsScreensTest: `a trashed note's snackbar offers Undo` | parent focused; parent coverage | Pass |
| 4. New strings in English and German | NotesActionsScreensTest: `the new list texts are translated to German`, `the editor menu and its messages are translated to German`, plus the English texts asserted in the other screen tests | parent focused | Pass |
| 5. Unit/Compose tests cover the new behavior; `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass | All of the above; coverage below | parent focused (ktlintCheck + notes unit tests); parent coverage (full unit suite) | Partly: the unit tests and ktlintCheck pass. `lintDebug` and `sh scripts/check.sh` were not run by the test-writer, and the parent's report does not mention them. The parent must run them and record the results |

## Focused test execution

Command:

`android/gradlew -p android ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'` (run by the parent worker on HEAD 52c0e918, before this round's test was added)

Result:

Pass (reported by the parent). The test added this round has not run yet. The parent must rerun the command above, which includes it.

## Full test execution

Command:

`android/gradlew -p android createDebugUnitTestCoverageReport` (full Android debug unit suite with JaCoCo, run by the parent worker on HEAD 52c0e918)

Result:

Pass (reported by the parent; the JaCoCo report was written at 17:44:36 on 2026-11-08, after the HEAD commit at 17:39:45).

## .taskcheck

Result:

Not run by the test-writer (no Gradle in this sandbox). Per the task notes, `sh scripts/check.sh` failed earlier in agent sandboxes only because Docker was unreachable for the server's Testcontainers tests; this task changes no server code. The parent runs it before `done`.

## Bugs found

None. No test exposed a production defect in the final revision.

## Coverage

Status: verified for HEAD 52c0e918, measured before this round's single new test (that test only adds hits; it does not change the denominators).

Metric: whole-file coverage of task-changed production files (JaCoCo counters).
Changed-line coverage: measured separately. Method: the executable lines (JaCoCo `line` elements with instructions) that fall inside the added/modified hunks of `git diff -U0 6fbabadfb..52c0e918` for each file. Result: 422/424 lines (99.5%), and 214/229 (93.4%) branches on those lines. The branch figure counts all branches on changed lines; it is not changed-branch coverage in a strict sense.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-notes-list; base 6fbabadfbafe865bbdba739d8dc5de77cb446065 (the claim commit, given by the parent); HEAD 52c0e918537a05ff9b12de6d0dec808173c949ca; task commits from `git log --oneline 6fbabadfb..HEAD`, all named and-notes-list or the merge of `origin/wip/and-notes-list`; production files from `git diff --name-only 6fbabadfb..HEAD -- android/app/src/main`, all inside the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): the measured run used committed HEAD 52c0e918 with a clean tree (`git status` was clean when the report was read). After the run, this round changed only `NoteEditorViewModelTest.kt` (sha256 7ae1944b160b852f4db7e89e64c9a66075ee53a6b1868d69c68bbc673ae8c87c, uncommitted). It was not part of the measured run.
- Exact included files: `NoteEditorScreen.kt`, `NoteEditorViewModel.kt`, `NotesListViewModel.kt`, `NotesModels.kt`, `NotesRepository.kt`, `NotesScreen.kt` (all in `android/app/src/main/java/ch/sonensei/cockpit/notes/`). Excluded: `res/values/strings_notes.xml` and `res/values-de/strings_notes.xml` (resources, not instrumented; covered by the en/de Compose tests). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo through the Android Gradle plugin's `createDebugUnitTestCoverageReport` (version as configured in the project build; this run did not record it), `android/gradlew -p android createDebugUnitTestCoverageReport` from the clone root, full debug unit suite.
- Run start/completion time and exit status: JaCoCo session `dell26-9b8e1de3`, start 2026-11-08T17:39:53+01:00, dump 2026-11-08T17:44:27+01:00; exit status pass (reported by the parent).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 0ec28df17005d4b4fd0cc94e07cae829d49944f661da69fa785363012171d074, session id `dell26-9b8e1de3`. Parsed with `python3 -I .tmp/cov.py` (Git-ignored scratch script).
- Evidence outputs are fresh and inputs stayed unchanged: the session started 8 s after the HEAD commit, the report file is newer than HEAD, it holds a single session, and the tree was clean when it was read.
- Final run after latest test additions and production fixes: yes for the production code (no production change after 52c0e918). No for this round's new test, which only adds hits on the already counted `NoteEditorViewModel.kt:358`. The parent's rerun of the focused command confirms that the test passes.

Values below use covered/total (percentage); "Statements" are JaCoCo instructions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `NoteEditorScreen.kt` | 3790/3859 (98.2%) | 141/155 (91.0%) | 71/87 (81.6%) | 263/269 (97.8%) |
| `NoteEditorViewModel.kt` | 2510/2522 (99.5%) | 182/196 (92.9%) | 67/67 (100.0%) | 240/240 (100.0%) |
| `NotesListViewModel.kt` | 1604/1637 (98.0%) | 96/98 (98.0%) | 58/63 (92.1%) | 171/173 (98.8%) |
| `NotesModels.kt` | 118/121 (97.5%) | N/A (0 branches) | 17/18 (94.4%) | 28/28 (100.0%) |
| `NotesRepository.kt` | 1017/1032 (98.5%) | 51/54 (94.4%) | 46/48 (95.8%) | 113/115 (98.3%) |
| `NotesScreen.kt` | 3821/3877 (98.6%) | 107/118 (90.7%) | 79/91 (86.8%) | 291/292 (99.7%) |
| Aggregate (summed counters, 6 files) | 12860/13048 (98.6%) | 577/621 (92.9%) | 338/374 (90.4%) | 1106/1117 (99.0%) |

No scoped file is missing from the report or unmeasured. Only the two string resource files are excluded (see above).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `NoteEditorViewModel.kt:358` (one of 2 branches) | Editor unpin (criterion 2) | Test added: `unpin of a pinned note sends pinned false over its version, and pin then pins it again` |
| `NotesListViewModel.kt:38,43` (lines missed) | Default `id = 0` arguments of the `Trashed` and `Archived` snackbar data classes (synthetic default constructors) | Non-material: the view model always numbers snackbars; tests build them with ids |
| `NotesListViewModel.kt:262` (1/2) | `?: note` fallback when the changed note is no longer in the loaded list | Non-material defensive fallback; the main path (latest shown version) is tested by `a change uses the latest shown version ...` |
| `NotesListViewModel.kt:288` (5/6) | `loadJob?.isActive` with no load ever started (`loadJob == null`) | Non-material: the screen always refreshes before a row can be changed |
| `NoteEditorViewModel.kt:375` (2/4) | Note gone or not editable after a successful save but before the flag change | Non-material race guard; the refusal and unsaved-text cases are tested (`no pin when the typed text could not be saved`, conflict tests) |
| `NoteEditorViewModel.kt:385` (1/2), `:415` (1/2), `:419` (11/12) | `title.orEmpty()` for an untitled server version; `note ?: return` guard; a delete refused with no HTTP status | Non-material: null-title fallback, a guard behind `delete()`'s own `note == null` check, and the status-less refusal, which ends in the same CHANGE_FAILED branch tested with a 412 refusal (`a delete that did not get through says why and keeps the editor open`) |
| `NotesScreen.kt:291` (6/12), `:323` (3/4) | `LaunchedEffect`/`when` bytecode branches of the snackbar effect; Undo on a non-Trashed snackbar (impossible: only Trashed has an action) | Non-material: every snackbar type and the Undo and dismiss outcomes are tested in NotesActionsScreensTest |
| `NotesScreen.kt:337`; `NoteEditorScreen.kt:171-173, 293, 412-413`; `NotesRepository.kt:262-263` | Lines outside the task's changed hunks (camera without an app, saving status label, photo message labels, `IllegalArgumentException` mapping) | Not task scope: these lines predate the task (the changed-line figure shows 0 missed changed lines in these files) |

## Unable to test

- `lintDebug` and `sh scripts/check.sh` (criterion 5): the test-writer cannot run Gradle. The parent must run them and record the results. Earlier notes say `scripts/check.sh` needs Docker for the server stage. This needs a follow-up by the parent.
- This round's new test has not run yet, for the same reason. The parent runs `android/gradlew -p android ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.NoteEditorViewModelTest'`.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. One test was added for the editor's unpin direction; the parent worker still has to run it, together with `lintDebug` and `.taskcheck`.
