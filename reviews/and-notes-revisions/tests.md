# Test Report

Date: 2026-11-09

Task: and-notes-revisions — Note editor: revisions list and restore (round 2, on the final code after the fixer)

Scope: `git diff 7d381705750ae41510671ebc6bb2f40bc5de563e..42443e52711f01df2bc3090df3da4e78d0fe8647 -- android` (base = `git merge-base HEAD origin/main`, given by the parent). Task commits: `bf809d1f1` (implementation), `8db10b448` (tests, round 1), `42443e527` (fixer: CR-1/TW-1, CR-2, CR-3). During this round the parent added `1d5a0ee92` (only `reviews/and-notes-revisions/review.md`), so the production code measured is identical to `42443e527`.

Requirements:
- `tasks/and-notes-revisions.md` acceptance criteria and recorded assumption (Earlier versions dialog, save first, then restore title/body; tags/pin/archive stay)
- `reviews/and-notes-revisions/fixes.md` (the final behavior: `restoring` blocks editing, `canRestore`, keepMine/useTheirs close the list)
- `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md`

## Test files changed

Round 2 (uncommitted, by this test-writer):
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorRevisionsViewModelTest.kt` (4 tests added)
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteRevisionsDialogTest.kt` (1 test added)

Already committed in the task: `NoteRevisionsRepositoryTest.kt` (round 1, unchanged), and the 6 tests the fixer added in `42443e527`. No existing test was weakened or removed.

## Tests added or updated

Count: 5 added in this round. The task now has 50 revision tests (8 repository, 29 ViewModel, 13 Compose/unit).

Added in this round:
- `while a restore runs nothing else starts, no edit mode, photo, pin, archive, delete or new list`
- `a restore that ends in a conflict leaves no restore lock, so the text is editable after the decision`
- `when saving first meets a conflict nothing is restored, and the text is editable after the decision`
- `when saving first fails offline the restore lock is lifted and the text can still be edited`
- `while a restore runs the text fields are disabled and the toolbar and the list are hidden` (Compose)

## Requirement coverage

Focused command (F): `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' createDebugUnitTestCoverageReport`
Gate command (G): `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Repository lists revisions (GET, mapping, newest first, empty, errors) | NoteRevisionsRepositoryTest (5 tests on list) | F, G | Pass |
| Repository restore (POST with If-Match, Saved, 412/409/404 conflicts, failures, offline) | NoteRevisionsRepositoryTest (3 tests on restore) | F, G | Pass |
| VM list: open/loading/newest selected, empty, offline/failed + Retry, select, close, out-of-order answers | NoteEditorRevisionsViewModelTest (round 1 tests on open/retry/select/close) | F, G | Pass |
| VM restore: saves unsaved text first, then takes restored title/body, tags kept, list closed | `restore saves the text written here first, ...`, `restoring a version without a title ...` | F, G | Pass |
| VM restore conflict / offline / refused / save-first fails | `a restore refused because the note changed elsewhere ...`, `a restore of a note deleted elsewhere ...`, `a restore without a connection or refused ...`, `when the text written here cannot be saved first, nothing is restored` | F, G | Pass |
| Fix CR-1/TW-1: no editing while a restore runs (title, body, tags, checklist), editable again after success | `while a restore runs the text cannot be edited, so nothing typed is replaced by the restored version` (fixer) | F, G | Pass |
| Fix CR-1: also no edit mode, photo, pin, archive, delete or new list while restoring | `while a restore runs nothing else starts, no edit mode, photo, pin, archive, delete or new list` (new) | F, G | Pass |
| Fix CR-1: the restore lock is always lifted (failure, restore conflict, save-first conflict, save-first offline) | `a restore that fails makes the text editable again` (fixer), `a restore that ends in a conflict leaves no restore lock, ...` (new), `when saving first meets a conflict nothing is restored, ...` (new), `when saving first fails offline the restore lock is lifted ...` (new) | F, G | Pass |
| Fix CR-1: pin/archive do not lock editing | `a pin does not block editing, unlike a restore` (fixer) | F, G | Pass |
| Fix CR-1: no restore while a photo uploads | `restore is ignored while a photo uploads, ...` (fixer) | F, G | Pass |
| Fix CR-1 (UI): while restoring, title/body disabled, toolbar (camera, gallery, edit toggle, menu) and list hidden; back to normal afterwards | `while a restore runs the text fields are disabled and the toolbar and the list are hidden` (new, Compose) | F, G | Pass |
| Fix CR-2: Restore disabled while changing or uploading | `Restore is disabled while a pin, archive or delete runs, or a photo uploads` (fixer, Compose) | F, G | Pass |
| Fix CR-3: keepMine/useTheirs close the hidden list | `a list hidden by a conflict is closed when the conflict is resolved, ...` (fixer) | F, G | Pass |
| Compose dialog: menu item, items, preview, select, Restore/Close, loading/empty/error texts, hidden in a conflict | NoteRevisionsDialogTest (round 1 tests) | F, G | Pass |
| New strings in English and German | English texts asserted in the Compose tests; `the earlier versions are translated to German` | F, G | Pass |
| `./gradlew ktlintCheck testDebugUnitTest lintDebug` passes | whole Android unit suite | G | Pass (BUILD SUCCESSFUL, exit 0) |
| `sh scripts/check.sh` passes | — | — | Not run: left to the parent's `.taskcheck` before `done` |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' createDebugUnitTestCoverageReport`

Result:

Pass: 21 suites in the `notes` package, 327 tests, 0 failures, 0 errors (NoteEditorRevisionsViewModelTest 29, NoteRevisionsDialogTest 13, NoteRevisionsRepositoryTest 8). ktlintCheck Pass. A first attempt did not compile because one new test name contained a colon (not allowed in JVM names); renamed, then the run above passed.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Pass: BUILD SUCCESSFUL (exit 0). JUnit XML: 432 suites, 5344 tests, 0 failures, 0 errors. ktlintCheck and lintDebug pass.

## .taskcheck

Result:

Not run: `sh scripts/check.sh` covers server, web and Android and is run by the parent before `tasks.py done`. Its Android part (ktlintCheck + testDebugUnitTest) passed above.

## Bugs found

None. All new tests pass on the final code. TW-1 from round 1 (text typed during a restore was lost) is fixed by `42443e527`; the fixer's test and the new tests in this round confirm it: editing, edit mode, photos, pin, archive, delete and opening the list are refused while a restore runs, and the lock is lifted after every outcome (success, failure, restore conflict, save-first conflict, save-first offline).

## Coverage

Status: verified (task-scoped, whole-file, fresh JaCoCo run of the `notes` test package on the final code)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. The added/modified lines from the `git diff -U0 7d381705750a..HEAD` hunk headers were intersected with the per-line entries of the JaCoCo XML, counting only lines JaCoCo reports as executable (instructions > 0). Result: 193/193 changed executable lines covered (100%); branches on changed lines 142/148 (95.9%).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-notes-revisions`; base `7d381705750ae41510671ebc6bb2f40bc5de563e` (merge-base with origin/main, given by the parent); task commits `bf809d1f1`, `8db10b448`, `42443e527`. HEAD at the end of measurement `1d5a0ee92bd4ada22474493f96ce2b2c123dfe2e` (adds only `reviews/`; production identical to `42443e527`).
- Source/test snapshot identifier (including uncommitted inputs): production = HEAD, clean. Uncommitted test changes, sha256 after the edits and after both runs: `NoteEditorRevisionsViewModelTest.kt` 973d6b8808cdfdf5740f5eb17384c6dca021da3e5a1c9767d5d80f45c6ad0c5b, `NoteRevisionsDialogTest.kt` 2357e911bff07becfbb5359fcf8a5afea202f6944e43a8c0ca82f9e476947a4d; committed `NoteRevisionsRepositoryTest.kt` 1af2ad14ded9831c9014bbef825686d62b212fd2be01d2aa5b9d470816e23b9e.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/notes/{NoteEditorViewModel.kt, NoteEditorScreen.kt, NoteRevisionsDialog.kt, NotesModels.kt, NotesRepository.kt}`. Excluded: `res/values/strings_notes.xml`, `res/values-de/strings_notes.xml` (resources, not instrumented; asserted by the en/de Compose tests). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (version not pinned in the build files). `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' createDebugUnitTestCoverageReport` in `android/`; only the `notes` test package ran.
- Run start/completion time and exit status: started 2026-11-09T06:53:26Z (that first attempt failed to compile, see above); the passing run wrote its test results at 06:54:07Z to 06:54:19Z, the `.exec` at 06:54:18Z and the report at 06:54:21Z; BUILD SUCCESSFUL. The gate run (G) finished before 06:59:56Z, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` sha256 47b288772fb34198775cdade3db5edb5180f5b76d17b79880995c9f9f195115a; `android/app/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report was written by this run, after the last test edit; the test-file hashes matched after the run; the 29/13 test counts include the new tests. The later gate run did not regenerate `report.xml` (no coverage task in it).
- Final run after latest test additions and production fixes: yes. It ran on the fixer's final production code with all test additions.

Values below use covered/total (percentage). Statements are JaCoCo instructions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `notes/NoteEditorViewModel.kt` | 4117/4146 (99.3%) | 299/318 (94.0%) | 111/113 (98.2%) | 360/360 (100.0%) |
| `notes/NoteEditorScreen.kt` | 5046/5154 (97.9%) | 173/197 (87.8%) | 86/106 (81.1%) | 354/360 (98.3%) |
| `notes/NoteRevisionsDialog.kt` | 1120/1130 (99.1%) | 61/62 (98.4%) | 21/25 (84.0%) | 96/96 (100.0%) |
| `notes/NotesModels.kt` | 148/148 (100.0%) | N/A (0 branches) | 23/23 (100.0%) | 33/33 (100.0%) |
| `notes/NotesRepository.kt` | 1217/1235 (98.5%) | 63/66 (95.5%) | 52/54 (96.3%) | 127/131 (96.9%) |
| Aggregate (summed counters; all 5 files measured, none missing) | 11648/11813 (98.6%) | 596/643 (92.7%) | 293/321 (91.3%) | 970/980 (99.0%) |

Uncovered branches on the task's changed lines (gaps in old code of these files predate the task):

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `NoteEditorViewModel.kt:522` (2/4: `note != null && state.value.changeable` false after the save) | Note gone or no longer changeable between the save-first step and the action | Defensive: `save()` returning true leaves a note and no conflict; the save-first conflict and offline paths (save returns false) are tested by the new tests. Left uncovered |
| `NoteEditorViewModel.kt:565` (`retryRevisions` with `note == null`) | Retry for a new note not saved yet | Not reachable: no list opens without a note |
| `NoteEditorViewModel.kt:581` (`it.revisions == null` when a load answers) | List closed before the answer | Defensive: `closeRevisions` cancels the job first; the observable behavior is tested (`closing hides the list, and a load still running does not open it again`) |
| `NoteEditorViewModel.kt:611` (7/8: `now.note == null`) | Restore without a note | Not reachable: a list only opens with a note |
| `NoteRevisionsDialog.kt:59` (1/2) | Synthetic default-argument branch of `canRestore` | The editor always passes `canRestore`; non-material |

## Unable to test

- The date/time label of a revision (`formatSavedAt`) runs but its text is not asserted, as it depends on the JVM time zone and locale. No follow-up needed.
- On-device behavior (a real AlertDialog on an emulator or device) was not tested; Robolectric Compose tests only.
- `sh scripts/check.sh` not run by me (the parent runs `.taskcheck`).

## Summary

Tests added and all validation passed. 5 tests were added for the fixer's final behavior (everything else locked while a restore runs, the lock lifted after every outcome, the editor UI disabled during a restore). The `notes` package (327 tests) and `./gradlew ktlintCheck testDebugUnitTest lintDebug` (5344 tests) pass. Changed-line coverage is 193/193 lines. No production defects found; TW-1 is resolved.
