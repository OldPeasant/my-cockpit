# Test Report

Date: 2026-10-21

Task: and-notes - Android notes

Scope: `git diff c167a9fa9ecc107793e5865b7e4953e747e0b10c..bb99aee8228b93c0587df382a5913f702164c7ca -- android` (task commits 8146e773 implementation, 9de16469 + 794df513 tests/review, bb99aee8 fixes CR-1..CR-6; the parent named them c6f41c46, 1d07ddef, 17f9578f, af0780bf, the same commits before a rebase: same subjects, and the range holds no other `android/` commits), plus the uncommitted test changes of this run listed below. Branch `main`, HEAD `bb99aee8228b93c0587df382a5913f702164c7ca`.

Requirements:
- `tasks/and-notes.md` acceptance criteria and its recorded assumptions (no offline cache, conflict choices, FileProvider, remote images not loaded, reopen reloads only when nothing is pending, photo during a conflict, leaving saves and the list waits)
- `reviews/and-notes/review.md` findings CR-1..CR-6 and `reviews/and-notes/fixes.md` (the fix paths to re-test)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`, parent instructions

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorRouteTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NotesRouteInAppTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/notes/MarkdownViewTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NotesRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/notes/NotePhotoFilesTest.kt`

## Tests added or updated

Count: 14 added (no existing test weakened, removed or skipped; one pre-existing `!!` that caused a compiler warning in `NotesRepositoryTest` was removed, the assertion is unchanged)

- NoteEditorViewModelTest (+7):
  - `a photo uploaded while the note was deleted elsewhere is dropped when the user discards, and not brought back on reopen`
  - `a photo uploaded while the note was deleted elsewhere goes into the new note when the user keeps their text`
  - `a note opened again while a conflict waits keeps the conflict and the local text`
  - `a note opened again while a photo uploads keeps it, and the photo is still inserted`
  - `a note opened again while its save runs is not reloaded over the save`
  - `a note opened again while its first load runs is loaded once`
  - `a note opened again after a failed load is loaded again`
- NoteEditorRouteTest (+2, Robolectric/Compose):
  - `a note that failed to load cannot be edited, and Retry loads it`
  - `leaving a note that failed to load saves nothing`
- NotesRouteInAppTest (new, +2, Hilt activity with `@BindValue` fake `NotesRepository`, the real `NotesRoute` and navigation):
  - `leaving the editor lets the list reload only once the save is done, so it shows the new text` (records the order list, saved, list with a deferred save)
  - `a note opened again from the list is loaded again and shows the server's newer text`
- MarkdownViewTest (+1):
  - `the image client sees the drive image request but never the web image` (waits until the interceptor sees the `cockpit-file:` request, so the "web image never requested" assertion is not vacuous)
- NotesRepositoryTest (+1): `an unreadable answer is a failure without a status, never offline or ok` (malformed JSON, invalid date, missing field)
- NotePhotoFilesTest (+1): `a photo whose stream fails while reading is refused, not a crash`

## Requirement coverage

Command for all rows: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' createDebugUnitTestCoverageReport` (final run 2026-10-21 12:01, 118 tests, 0 failures, 0 skipped).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Notes list, pinned first | NoteOrderTest (4), NotesRepositoryTest `the list is pinned first...`, `a note on two pages...`, NotesListViewModelTest `the list keeps the order...`, NotesScreensTest `the list shows notes in the given order...` | above | Pass |
| AC1 Tag filter | NotesListViewModelTest `loads the notes and tags, and filters by a tag`, `a list for an earlier filter that answers last is dropped`, `choosing All...`, NotesScreensTest `the active tag is selected...` | above | Pass |
| AC1 Markdown view with rendered images | MarkdownTest (11), MarkdownViewTest (4) incl. new `the image client sees the drive image request but never the web image`, NotesScreensTest `the view renders Markdown with its images...` | above | Pass |
| AC1 Plain editor with autosave | NoteEditorViewModelTest autosave/serialisation tests, NoteEditorRouteTest `leaving saves what was typed and closes`, `the system back also saves at once...`, new NotesRouteInAppTest `leaving the editor lets the list reload only once the save is done...` | above | Pass |
| AC1 Add photo from camera/gallery (uploads image) | NoteEditorRouteTest camera/gallery/cancel tests, NotePhotoFilesTest (6) incl. new read-failure test, NoteEditorViewModelTest photo tests, NotesRepositoryTest upload tests | above | Pass |
| AC2 Save conflict handling | NoteEditorViewModelTest conflict tests (keep mine, use theirs, deleted note, conflict during save), NotesRepositoryTest 404/409/412 tests, NotesScreensTest conflict banners, NoteEditorRouteTest `a note deleted elsewhere closes the editor...` | above | Pass |
| AC2 Robolectric/Compose UI tests for screens | NotesScreensTest (16), NoteEditorRouteTest (10), NotesScreenInAppTest (1), NotesRouteInAppTest (2), MarkdownViewTest (4) | above | Pass |
| Fix CR-1: failed load is not editable, Retry works | `after a failed load nothing can be edited...` (existing), new `a note that failed to load cannot be edited, and Retry loads it`, `leaving a note that failed to load saves nothing`, `a note opened again after a failed load is loaded again` | above | Pass |
| Fix CR-2: reopen reloads only when nothing is pending | existing 3 VM tests + route test, new: in-app reopen from the list, reopen during conflict / upload / running save / first load | above | Pass |
| Fix CR-3: web images not loaded, placeholder shown | existing placeholder test, new interceptor test with a positive control | above | Pass |
| Fix CR-4: back saves, then the list refreshes | existing `save now returns a job...`, `the system back also saves at once...`, new in-app ordering test (list, saved, list) | above | Pass |
| Fix CR-5: photo during a conflict | existing keep-mine / use-theirs tests, new deleted-note keep-mine (goes into the new note) and discard (dropped, not re-inserted after reopen) | above | Pass |
| `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'` passes (AC2 command) | all of the above | above | Pass |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' createDebugUnitTestCoverageReport`

Result:

Pass (BUILD SUCCESSFUL; 118 tests in 11 classes: NoteEditorViewModelTest 39, NotesRepositoryTest 18, NotesScreensTest 16, MarkdownTest 11, NoteEditorRouteTest 10, NotesListViewModelTest 7, NotePhotoFilesTest 6, MarkdownViewTest 4, NoteOrderTest 4, NotesRouteInAppTest 2, NotesScreenInAppTest 1; 0 failures, 0 errors, 0 skipped; ktlintCheck clean)

## Full test execution

Command:

`sh scripts/check.sh` (lint + unit tests of server, web and android)

Result:

Pass ("== all checks passed", 2026-10-21 12:01:46 to 12:05:06, about 3 min 20 s, above the 2-minute warning, far under the 15-minute limit)

## .taskcheck

Result:

Pass (`.taskcheck` is `sh scripts/check.sh`, run above on the working tree with the new tests)

## Bugs found

None. Every new test passed against the production code at bb99aee8. No production defect was found.

## Coverage

Status: verified (whole-file, the notes test selection only)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. 8 of the 9 Kotlin files in scope were added by this task, so for them whole-file coverage equals changed-line coverage. `NotesScreen.kt` was a placeholder before the task and was almost fully rewritten.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-notes`; base `c167a9fa9ecc107793e5865b7e4953e747e0b10c` (from the parent); HEAD `bb99aee8228b93c0587df382a5913f702164c7ca`; task-owned `android/` commits 8146e773, 9de16469, 794df513, bb99aee8 (`git log c167a9fa..HEAD -- android`). The other commits in the range only touch `tasks/`. The scope is `git diff --name-status c167a9fa..HEAD -- android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 of `sha256sum app/src/test/java/ch/sonensei/cockpit/notes/*.kt app/src/main/java/ch/sonensei/cockpit/notes/*.kt` = `63acea46c5c657736ddc2859667c87a5ffe5abbbd0548db869247a81c96f738f`. It was the same before and after the final run. Production files are unchanged from HEAD. The uncommitted inputs are the 5 modified test files and the new `NotesRouteInAppTest.kt`.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/notes/{Markdown,MarkdownView,NoteEditorScreen,NoteEditorViewModel,NoteImages,NotesListViewModel,NotesModels,NotesRepository,NotesScreen}.kt`. Excluded: `AndroidManifest.xml`, `res/values/strings_notes.xml` and `res/xml/notes_photo_paths.xml`, because they are configuration/resources with no executable code (the FileProvider wiring is exercised by `NotePhotoFilesTest` and the camera route test). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (version from the AGP/Gradle default, not pinned in `app/build.gradle.kts`); Gradle 9.8.1, JDK 21.0.12.1; `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' createDebugUnitTestCoverageReport` in `android/`; selection `ch.sonensei.cockpit.notes.*` only. So hits from other packages' tests are not counted.
- Run start/completion time and exit status: 2026-10-21T12:01:07+02:00 to 12:01:37+02:00, BUILD SUCCESSFUL (exit 0). `testDebugUnitTest` executed in this run (it was not up to date).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` sha256 `7a2f4259e3c2eb8ef8bd985f1f05814f9a1c53e333b29b159ab51644d8515b71`; `android/app/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec` sha256 `be27057c279662a9a4a575db86c12735fa106dea466310788097619a01ba8bea`. Per-file counters were read from the sourcefile elements of package `ch/sonensei/cockpit/notes`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report was regenerated in this run, the test task executed, and the snapshot hash was the same before and after.
- Final run after latest test additions and production fixes: yes (after the fixes in bb99aee8 and all test additions of this run).

Values below use covered/total (percentage); JaCoCo instruction counters are listed as Statements.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `notes/Markdown.kt` | 1102/1120 (98.4%) | 76/82 (92.7%) | 40/40 (100.0%) | 113/113 (100.0%) |
| `notes/MarkdownView.kt` | 1371/1406 (97.5%) | 60/74 (81.1%) | 10/10 (100.0%) | 129/130 (99.2%) |
| `notes/NoteEditorScreen.kt` | 3005/3119 (96.3%) | 126/142 (88.7%) | 54/66 (81.8%) | 213/220 (96.8%) |
| `notes/NoteEditorViewModel.kt` | 1743/1748 (99.7%) | 130/140 (92.9%) | 52/52 (100.0%) | 176/176 (100.0%) |
| `notes/NoteImages.kt` | 317/338 (93.8%) | 26/30 (86.7%) | 10/10 (100.0%) | 42/43 (97.7%) |
| `notes/NotesListViewModel.kt` | 365/368 (99.2%) | 28/28 (100.0%) | 15/16 (93.8%) | 41/41 (100.0%) |
| `notes/NotesModels.kt` | 93/96 (96.9%) | N/A (0 branches) | 15/16 (93.8%) | 25/25 (100.0%) |
| `notes/NotesRepository.kt` | 667/685 (97.4%) | 31/34 (91.2%) | 35/37 (94.6%) | 82/86 (95.3%) |
| `notes/NotesScreen.kt` | 1908/1954 (97.6%) | 60/71 (84.5%) | 41/45 (91.1%) | 157/159 (98.7%) |
| **Aggregate (summed counters, 9 files, none missing or excluded)** | 10571/10834 (97.6%) | 537/601 (89.4%) | 272/292 (93.2%) | 978/993 (98.5%) |

Before this run's additions (same command, 11:55), the aggregate was 10512/10834 (97.0%) instructions, 524/601 (87.2%) branches and 973/993 (98.0%) lines. The new tests closed 3 of the 4 missed reopen-while-pending branches in `NoteEditorViewModel.kt:121`, the route branches in `NotesScreen.kt:98,101`, one branch of `NoteEditorScreen.kt:167`, the invalid-date catch in `NotesRepository.kt:194` and the stream-failure catch in `NoteImages.kt:85-86`.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `NoteEditorViewModel.kt:100` (`id == null` on a new opening), `:289` (upload ends, not editable and no conflict), `:301,305` (`create()` finds a note already set / created note not equal to the text), `:193` (save while loading) | Defensive guards. New notes always get a fresh key per opening in `NotesRoute`, and no upload can be running while the editor reloads or closes without a conflict | Justified gap; the reachable variants are tested (`typing and a photo on a new note create it only once`, reopen tests) |
| `NoteEditorScreen.kt:154-156` (camera `ActivityNotFoundException`) | No camera app: the photo-unreadable message | Not added: Robolectric always resolves the camera intent, so this needs an activity without a camera handler. The resulting VM path (`addPhoto(null)` gives PHOTO_UNREADABLE) is tested. Low risk |
| `NoteEditorScreen.kt:266,343-344` and branches at `:263,340` (SAVING label, PHOTO_OFFLINE/PHOTO_FAILED texts), `:303` toggle label variants, `:77` default callback | Status/message strings only | Non-material; the states themselves are tested in the VM |
| `NotesScreen.kt:64` and branches at `:60-61` (placeholder outside a Hilt activity) | Shell navigation tests outside Hilt | Covered by the shell's own tests (outside the notes selection), non-material |
| `NotesScreen.kt:183`, `:207` (FAILED list colour / tag-chip variants), `:156` | Rendering variants | Non-material; FAILED list state is tested in NotesScreensTest |
| `NotesRepository.kt:184-185` (CancellationException rethrow), `:192-193` (IllegalArgumentException), branches `:121,178,214` | Defensive catch-all and paging edge cases | Justified gap; malformed answers (serialization, invalid date) are now tested |
| `NoteImages.kt:87` (SecurityException on a revoked URI) | Same handling as the tested IOException path | Not added: Robolectric's content resolver cannot throw SecurityException on open without a custom provider. Low risk |
| `MarkdownView.kt:138` and branches at `:57-58,189,202-223` (rule/inline style variants, excerpt of non-text blocks) | Rendering details | Non-material; every block kind is rendered in `every block kind is rendered...` |

## Unable to test

- Real camera/gallery apps and a real server are not used. The pickers are simulated with Robolectric `receiveResult`, and the server with MockWebServer or mocks. A real end-to-end run on a device is not part of the acceptance criteria.
- Camera missing (`ActivityNotFoundException`) and `SecurityException` on photo read: see the coverage table. Low risk, no follow-up needed.

## Summary

Tests added and all validation passed. Task-scoped coverage is sufficient, and no production defects were found. The review fix paths for CR-1 to CR-5 are now covered at view-model, route and in-app (Hilt) level.
