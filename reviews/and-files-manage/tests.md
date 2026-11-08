# Test Report

Date: 2026-11-08

Task: and-files-manage (Android files: new folder, rename, move, delete), test pass on the final code including c700dfcff

Scope: base c43ccaaf8c21f7cdacfd56882a1bd7c2400066a2 .. HEAD 4294509ec0cd6d04a4944c053388f85dd996aba9 (`git diff c43ccaaf8..HEAD -- android/app/src/main/java/ch/sonensei/cockpit/files android/app/src/test/java/ch/sonensei/cockpit/files android/app/src/main/res`). Task commits supplied by the parent: 199268e29 (implementation), 9838aa27c and 1b147bd12 (tests), 2496829f5 (fixes CR-1..CR-4 of the first review), 4e45f4c65 (more tests), c700dfcff (parent fix of the second-review P3s: 404 retry dead end in the rename dialog and move picker, picker formatting), merged in 258c31e3b, and 4294509ec (the tests of this pass, committed by the parent). Task ownership: commits and base supplied by the parent, checked against `git log c43ccaaf8..HEAD` and the task's `touches`. The same range also contains and-scheduled-list commits (7037aee25, 50268ff71: `gtd/lists`, `ui/CockpitShell.kt`, `ui/navigation/CockpitDestination.kt`, `strings_projects_lists.xml`), which belong to another task and are excluded.

Production files in scope (from the diff):
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesModels.kt` (entry versions, name validation)
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesRepository.kt` (create folder, rename, move, delete with If-Match; FilesWriteApi)
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt` (selection bar, entry menu, name dialog, move picker, confirmations)
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt` (selection, name dialog, move picker, delete, stale-version adoption)
- `android/app/src/main/res/values/strings_files.xml`, `android/app/src/main/res/values-de/strings_files.xml` (resources, not instrumented)

Requirements:
- `tasks/and-files-manage.md` acceptance criteria 1-4
- `AGENTS.md` project notes (English and German strings, lint, fictitious test data), SPEC.md §4.2 (If-Match optimistic locking)
- `reviews/and-files-manage/review.md` second-review P3 findings fixed in c700dfcff (404 retry dead end)
- `docs/ARCHITECTURE.md` "Coverage"
- Existing test conventions in `android/app/src/test/java/ch/sonensei/cockpit/files/`

## Assessment of c700dfcff

c700dfcff came with two tests (rename of something gone elsewhere closes the dialog; move into a folder gone elsewhere sends the picker back to the origin at the drive root). Three behaviors of the new code were not pinned down:
- With an origin below the drive root, "back to the origin" and "back to the drive root" were indistinguishable in the existing test.
- The interaction of the new `targetGone` path with a partly successful multi-entry move (the picker path reset, the confirmation and the closing after the reload).
- The new `nameDialog?.saving == false` guard in `withVersionsOf`: a reload must not close a rename dialog whose save is still running.

The FilesScreen.kt part of c700dfcff only extracts local values in the picker row (no behavior change); the picker's dimmed, non-openable and moving states are covered by the existing FilesScreenTest picker tests.

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/files/FilesViewModelTest.kt`

## Tests added or updated

Count: 3 (added; no existing test changed)
- `a move into a folder gone elsewhere sends the picker back to a subfolder the entry is in`
- `a partly moved selection whose other entry is gone elsewhere closes the picker after the reload`
- `a rename dialog is not closed by a reload while its save is still running` (ordering controlled with CompletableDeferred, no real delays)

Task test total in the files package: FilesViewModelTest 61, FilesScreenTest 28, FilesRouteInAppTest 21, FilesRepositoryTest 19, FilesModelsTest 5.

## Requirement coverage

"Full run" below is the parent's run of `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` on HEAD 4294509ec (BUILD SUCCESSFUL, exit 0, 3430 tests, 0 failures, 0 errors, 0 skipped). "Focused run" is the test-writer's `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.files.FilesViewModelTest' -q` (61/61 pass, ktlint clean).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: new folder (created in the folder shown, name validated, taken name, folder gone) | FilesViewModelTest (new folder created and confirmed, name the server would refuse not sent, taken name on create, new folder in a folder gone elsewhere), FilesScreenTest (new folder dialog, name errors), FilesRepositoryTest (create folder), FilesModelsTest 5/5, FilesRouteInAppTest (new folder named in the dialog) | full run | Pass |
| AC1: rename (If-Match, 409/412/404, retry with reloaded version, entry gone closes dialog) | FilesViewModelTest rename tests incl. `a rename of something gone elsewhere closes the dialog once the reload no longer has it` and new `a rename dialog is not closed by a reload while its save is still running`, FilesScreenTest rename dialog, FilesRepositoryTest rename, FilesRouteInAppTest rename | full run | Pass |
| AC1: move with folder picker (browse, cannot open moved folder, cannot move in place, partial failures, stale versions, target gone) | FilesViewModelTest move and picker tests incl. new `a move into a folder gone elsewhere sends the picker back to a subfolder the entry is in` and `a partly moved selection whose other entry is gone elsewhere closes the picker after the reload`, FilesScreenTest picker tests, FilesRepositoryTest move (folderId/parentId, null target), FilesRouteInAppTest move | full run | Pass |
| AC1: delete to Trash (one, selection, already gone, conflicts, one at a time) | FilesViewModelTest delete tests, FilesScreenTest menu, FilesRepositoryTest delete, FilesRouteInAppTest Trash tests | full run | Pass |
| AC2: long-press multi-select for move/delete (auto-managed folders excluded, back ends selection) | FilesViewModelTest selection tests, FilesScreenTest long-press and selection bar tests, FilesRouteInAppTest long-press tests | full run | Pass |
| AC3: strings in English and German | FilesScreenTest German tests (selection, menu, confirmations, name dialog, move picker, invalid name) | full run | Pass |
| Second-review P3 fix (404 retry dead end) | 2 tests of c700dfcff plus the 3 new tests above | focused run and full run | Pass |
| AC4: `ktlintCheck testDebugUnitTest lintDebug` pass | whole Android suite | full run | Pass |
| AC4: `sh scripts/check.sh` passes | whole repository check | `sh scripts/check.sh` | Not run by the test-writer: the parent runs `.taskcheck` before done |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.files.FilesViewModelTest' -q` (test-writer, working tree with the 3 new tests on 258c31e3b)

Result:

Pass. FilesViewModelTest 61 tests, 0 failures, 0 errors, 0 skipped (results written 2026-11-08T12:52:13Z); ktlintCheck clean.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` (run by the parent on HEAD 4294509ec, no other Gradle process)

Result:

Pass. BUILD SUCCESSFUL in 4m30s, exit 0; 3430 tests, 0 failures, 0 errors, 0 skipped; ktlint and lint clean.

An earlier run of the same command by the test-writer failed with `NoSuchFileException .../test-results/testDebugUnitTest/binary/in-progress-results-generic.bin` because another Gradle run wrote to the same results folder at the same time; it is not a test failure and its output was not used.

## .taskcheck

Result:

Not run by the test-writer; the parent runs `sh scripts/check.sh` before done.

## Bugs found

None. All task tests pass on the final code.

## Coverage

Status: verified (fresh full-suite run on HEAD 4294509ec).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured from the same JaCoCo report with `.tmp/tw_files_cov.py` (test-writer script): added/modified lines of `git diff -U0 c43ccaaf8 HEAD` per file intersected with JaCoCo lines that have instructions; a line counts as covered when it has a covered instruction; branches are the JaCoCo branch counters on those lines. Result: lines 534/535 (99.8%), branches on changed lines 363/390 (93.1%). Per file: FilesModels.kt 11/11 lines, 22/22 branches; FilesRepository.kt 24/25 lines, 13/14 branches; FilesScreen.kt 317/317 lines, 157/170 branches; FilesViewModel.kt 182/182 lines, 171/184 branches.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-files-manage; base c43ccaaf8c21f7cdacfd56882a1bd7c2400066a2; HEAD 4294509ec0cd6d04a4944c053388f85dd996aba9; commits 199268e29, 9838aa27c, 1b147bd12, 2496829f5, 4e45f4c65, c700dfcff, 4294509ec supplied by the parent and matched against the diff and the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 4294509ec; FilesViewModelTest.kt sha256 f611137d806f580823397ab706a75160f78ca6ec4d348c6bbcf463618093dbd6 (same content as tested in the focused run, now committed). The only uncommitted change at report time is `reviews/and-files-manage/review.md` (not a source or test input).
- Exact included files; renamed/deleted/excluded files and reasons: FilesModels.kt, FilesRepository.kt, FilesScreen.kt, FilesViewModel.kt. Excluded: the two `strings_files.xml` files (resources, not instrumented; covered by the German FilesScreenTest tests) and the and-scheduled-list files in the same range (another task). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via the Android Gradle plugin (`enableUnitTestCoverage`); `./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` in `android/`, whole unit suite, run by the parent.
- Run start/completion time and exit status: about 2026-11-08 13:05 to 13:10 UTC, BUILD SUCCESSFUL, exit 0 (reported by the parent).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 6d9ef21f660a4430579e22d4f3ef61cebf16f4bcd755bbb48d009fd8d04faddd (verified by the test-writer on disk).
- Evidence outputs are fresh and inputs stayed unchanged: the run was made on HEAD 4294509ec, which contains c700dfcff and the 3 new tests; the report hash on disk matches the parent's; the stale 13:39 report from before c700dfcff was not used.
- Final run after latest test additions and production fixes: yes.

Values below use covered/total (percentage); JaCoCo has no separate statement counter, so instructions are given instead.

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `android/.../files/FilesModels.kt` | 343/343 (100.0%) | 33/34 (97.1%) | 23/23 (100.0%) | 37/37 (100.0%) |
| `android/.../files/FilesRepository.kt` | 779/805 (96.8%) | 31/34 (91.2%) | 33/35 (94.3%) | 102/106 (96.2%) |
| `android/.../files/FilesScreen.kt` | 7382/7548 (97.8%) | 258/289 (89.3%) | 150/174 (86.2%) | 562/570 (98.6%) |
| `android/.../files/FilesViewModel.kt` | 3310/3355 (98.7%) | 214/232 (92.2%) | 120/120 (100.0%) | 293/294 (99.7%) |
| Aggregate (summed counters, all 4 files) | 11814/12051 (98.0%) | 536/589 (91.0%) | 326/352 (92.6%) | 994/1007 (98.7%) |

The aggregate excludes only the two string resource files (not instrumented); no scoped Kotlin file is missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `FilesRepository.kt:78` (and pre-existing 75) | Hilt `@Provides` of FilesWriteApi (Retrofit create) | Justified gap: DI wiring; tests build the repository with fakes, and FilesRouteInAppTest replaces the module |
| `FilesRepository.kt:178` (1/2 branches) | `folder.special?.value` on the created folder: the non-null case | Justified gap: a folder created by the user is never auto-managed |
| `FilesRepository.kt:222-223` | Rethrowing CancellationException (pre-existing) | Outside the task |
| `FilesViewModel.kt:537` (1/2) | `picker.moveMessage ?: ActionMessage.CHANGED` when the picker closes after a reload without a move refusal | Justified gap: adoption only follows a refusal, and a move refusal always sets moveMessage, so the fallback is defensive |
| `FilesViewModel.kt:237, 275, 291, 294, 302, 309, 310, 316, 366, 526, 531, 534` (partial) | Null-safe calls on an absent dialog/picker and short-circuit combinations | Justified gap: no-dialog/no-picker guards; the meaningful cases (no dialog, picker closed, late answers, moving, gone while saving) are tested |
| `FilesViewModel.kt:480` | `openCache` IOException (pre-existing download path) | Outside the task |
| `FilesScreen.kt` partial branches at 446, 464, 500, 503, 509, 717, 728, 737, 809, 813, 868 | Compose conditionals such as the IME Done action with an invalid name, the picker loading bar, offline vs failed picker message and default-argument branches | Justified gap: the state each represents is tested elsewhere (FilesScreenTest offline picker message, disabled save, loading); remaining ones are compiler-generated |
| `FilesScreen.kt:141, 362, 671, 673, 874, 876, 877, 879` | Exhaustive-when tails, upload state texts and kind tiles of other file families (pre-existing) | Outside the task or compiler-generated; none is a changed line |

## Unable to test

- `sh scripts/check.sh` (AC4 second part): left to the parent, who runs `.taskcheck` before done.
- Behavior against a real server (actual 404/409/412 answers): not in this task's criteria; the repository is tested with MockWebServer-style fakes in FilesRepositoryTest. No follow-up needed.

## Summary

Tests added and all validation passed. Three tests pin down the c700dfcff behavior that was not yet tested (picker back to a subfolder origin, partly moved selection with a gone entry, rename dialog not closed while its save runs); no production defects found. Task-scoped coverage: lines 994/1007 (98.7%), branches 536/589 (91.0%); changed lines 534/535, changed-line branches 363/390, with remaining gaps justified above.
