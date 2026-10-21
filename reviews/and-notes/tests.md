# Test Report

Date: 2026-10-21

Task: and-notes - Android notes

Scope: `git diff c167a9fa9ecc107793e5865b7e4953e747e0b10c..c7c89efdc053944e5f454d04fb981d1150ef7daf -- android`, limited to the task-owned commits (`git log --grep='^and-notes' c167a9fa..c7c89efd -- android/app/src/main`): 5b3ba5bd, 31cd8171, fac1120a, 3cdd0816, c7c89efd (2abd7142 and fed32c21 touch tests/reviews only). The range also contains the finished task `and-reminders` (commits 21154eec..19f2c945: `reminders/`, `MainActivity.kt`, `CockpitShell.kt`, `CockpitRoot.kt`, parts of `AndroidManifest.xml`). Those are excluded as not owned by this task. Branch `main`, HEAD `c7c89efdc053944e5f454d04fb981d1150ef7daf`, plus the uncommitted test change of this run (`NoteEditorsTest.kt`). This is the final pass after the second review fixes (NoteEditors, NoteSaveScope, `onCleared` saves, `settle()`).

Requirements:
- `tasks/and-notes.md`: acceptance criteria and the recorded assumptions (conflict choices, reopen keeps pending text, leaving saves and the list waits)
- `reviews/and-notes/review.md` (second review, CR-1..CR-5) and `reviews/and-notes/fixes.md` (the fix paths to re-test)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`, parent instructions

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorsTest.kt`

## Tests added or updated

Count: 4 added. No existing test was changed, weakened or skipped.

The fixer's tests already cover most of the fix paths. Four gaps remained.
- `an editor cleared while a conflict waits saves nothing over the other version`. The existing test "cleared during a conflict or before its note loaded saves nothing" only tests the not-loaded case. This test covers the conflict branch of `onCleared` -> `save()`.
- `the list showing keeps an editor whose conflict waits, saves nothing, and reopening reaches the conflict`. It checks that `settle()` keeps an editor that has a conflict, does not re-save it, and that the same editor (conflict and local text kept, no reload) comes back when the note is opened from the list.
- `a photo still uploading when the list shows keeps its editor, and its image is saved once it arrives`. `settle()` must not drop (and so cancel) an editor whose upload is still running. Once the upload is in and saved, the next `settle()` drops it.
- `a new note that was created but not saved since is reached from the list, not offered again by New note`. `unsentNewNote()` ignores a new note that already has an id. Its editor is reached by the note's id instead.

## Requirement coverage

Command for all rows: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' createDebugUnitTestCoverageReport` (2026-10-21 12:23-12:24 +02:00, BUILD SUCCESSFUL, 134 tests in 12 classes, 0 failures, 0 errors, 0 skipped).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Notes list, pinned first | NoteOrderTest (4), NotesRepositoryTest pinned-first/paging tests, NotesListViewModelTest order test, NotesScreensTest `the list shows notes in the given order...` | above | Pass |
| AC1 Tag filter | NotesListViewModelTest tag filter tests (incl. a late answer for an old filter), NotesScreensTest active tag | above | Pass |
| AC1 Markdown view with rendered images | MarkdownTest (11), MarkdownViewTest (4), NotesScreensTest render test | above | Pass |
| AC1 Plain editor with autosave | NoteEditorViewModelTest autosave tests, NoteEditorRouteTest leave/back saves, NotesRouteInAppTest (5) incl. shell back arrow saves and list waits for the save, NoteEditorsTest (13) | above | Pass |
| AC1 Add photo from camera/gallery (uploads image) | NoteEditorRouteTest camera/gallery tests, NotePhotoFilesTest (6), NoteEditorViewModelTest photo tests, NotesRepositoryTest upload tests, new `a photo still uploading when the list shows keeps its editor...` | above | Pass |
| AC2 Save conflict handling | NoteEditorViewModelTest conflict tests, NotesRepositoryTest 404/409/412, NotesScreensTest conflict banners, new `an editor cleared while a conflict waits...` and `the list showing keeps an editor whose conflict waits...` | above | Pass |
| AC2 Robolectric/Compose UI tests for screens | NotesScreensTest (16), NoteEditorRouteTest (10), NotesRouteInAppTest (5), NotesScreenInAppTest (1), MarkdownViewTest (4) | above | Pass |
| 2nd review CR-1: leaving by any way saves, without cancelling a running save or a photo's create | NoteEditorsTest clear/autosave/running-save/photo-create tests, NotesRouteInAppTest shell back arrow test | above | Pass |
| 2nd review CR-2: unsaved new-note text reachable | NoteEditorsTest reach-by-id, unsent new note, new created-note-not-offered-by-New-note test; NotesRouteInAppTest two in-app tests | above | Pass |
| 2nd review CR-5: editors do not pile up, and pending ones are kept | NoteEditorsTest dropped/kept tests, new conflict-kept and upload-kept tests | above | Pass |
| `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*'` passes (AC2 command) | all of the above | above | Pass |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.NoteEditorsTest'`, then `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' createDebugUnitTestCoverageReport`

Result:

Pass. NoteEditorsTest: 13 tests, 0 failures. Notes package: 134 tests (MarkdownTest 11, MarkdownViewTest 4, NoteEditorRouteTest 10, NoteEditorViewModelTest 39, NoteEditorsTest 13, NoteOrderTest 4, NotePhotoFilesTest 6, NotesListViewModelTest 7, NotesRepositoryTest 18, NotesRouteInAppTest 5, NotesScreenInAppTest 1, NotesScreensTest 16), 0 failures, 0 errors, 0 skipped. ktlintCheck clean.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Pass (whole Android unit suite: 89 classes, 915 tests, 0 failures, 0 errors, 0 skipped; ktlint and lintDebug clean)

## .taskcheck

Result:

Pass. `sh scripts/check.sh` printed "== all checks passed" (2026-10-21, about 12:25 to 12:29 +02:00) on the working tree with the new tests. The web build printed its existing bundle-budget warning, which is unrelated to this task.

## Bugs found

None. All 4 new tests passed against the production code at c7c89efd.

## Coverage

Status: verified (whole-file, notes test selection only)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. Nine of the 10 Kotlin files in scope were added by this task (whole-file equals changed-line for them). `NotesScreen.kt` was a placeholder before the task and is almost entirely rewritten.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-notes`; base `c167a9fa9ecc107793e5865b7e4953e747e0b10c` (from the parent); measured HEAD `c7c89efdc053944e5f454d04fb981d1150ef7daf`. Task-owned production commits are 5b3ba5bd, 31cd8171, fac1120a, 3cdd0816 and c7c89efd (`git log --grep='^and-notes' c167a9fa..c7c89efd -- android/app/src/main`).
- Source/test snapshot identifier (including uncommitted inputs): `sha256sum app/src/test/java/ch/sonensei/cockpit/notes/*.kt app/src/main/java/ch/sonensei/cockpit/notes/*.kt | sha256sum` (in `android/`) = `4903af871dd1b445f2eab1d51f6026b69a77d9e066fe4d8ca4078dcdbe2f3622`, identical before and after the run. Production files are unchanged from HEAD. The only uncommitted input is the modified `NoteEditorsTest.kt`.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/notes/{Markdown,MarkdownView,NoteEditorScreen,NoteEditorViewModel,NoteEditors,NoteImages,NotesListViewModel,NotesModels,NotesRepository,NotesScreen}.kt`.
  - Excluded: `AndroidManifest.xml`, `res/values/strings_notes.xml` and `res/xml/notes_photo_paths.xml`, because they hold configuration and resources with no executable code.
  - Also excluded: Hilt/Dagger-generated `*_Factory`/`*_HiltModules*` classes in the same JaCoCo package, because they are generated.
  - Also excluded: all `and-reminders` files, because another task owns them.
  - No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (the AGP default version). Gradle 9.8.1. Command: `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.*' createDebugUnitTestCoverageReport` in `android/`. Selection: `ch.sonensei.cockpit.notes.*` only.
- Run start/completion time and exit status: 2026-10-21T12:23:29+02:00 to about 12:24:36+02:00 (report mtime 12:24:35). BUILD SUCCESSFUL (exit 0). `testDebugUnitTest` executed in this run (not up to date). The test-result timestamps are 10:24:16Z-10:24:33Z.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` sha256 `6cb24c2b60230a02da5f1109fc65c299636336293de22d910f0e182d06fd1c77`; `android/app/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec` sha256 `27aa7aec29f5825b5133657bcb8bdea48d415bcf7121c1e0673c018567ed7bde` (hashed before the later full-suite run, which rewrites the `.exec`). Counters come from the `<sourcefile>` elements of package `ch/sonensei/cockpit/notes`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report was regenerated by this run, and the snapshot hash matched before and after.
- Final run after latest test additions and production fixes: yes (production at c7c89efd, all 4 new tests included).

Values below use covered/total (percentage); JaCoCo instruction counters are listed as Statements.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `notes/Markdown.kt` | 1102/1120 (98.4%) | 76/82 (92.7%) | 40/40 (100.0%) | 113/113 (100.0%) |
| `notes/MarkdownView.kt` | 1371/1406 (97.5%) | 60/74 (81.1%) | 10/10 (100.0%) | 129/130 (99.2%) |
| `notes/NoteEditorScreen.kt` | 3028/3145 (96.3%) | 122/136 (89.7%) | 55/67 (82.1%) | 216/223 (96.9%) |
| `notes/NoteEditorViewModel.kt` | 1831/1836 (99.7%) | 134/144 (93.1%) | 57/57 (100.0%) | 183/183 (100.0%) |
| `notes/NoteEditors.kt` | 292/292 (100.0%) | 13/14 (92.9%) | 10/10 (100.0%) | 38/38 (100.0%) |
| `notes/NoteImages.kt` | 317/338 (93.8%) | 26/30 (86.7%) | 10/10 (100.0%) | 42/43 (97.7%) |
| `notes/NotesListViewModel.kt` | 365/368 (99.2%) | 28/28 (100.0%) | 15/16 (93.8%) | 41/41 (100.0%) |
| `notes/NotesModels.kt` | 93/96 (96.9%) | N/A (0 branches) | 15/16 (93.8%) | 25/25 (100.0%) |
| `notes/NotesRepository.kt` | 667/685 (97.4%) | 31/34 (91.2%) | 35/37 (94.6%) | 82/86 (95.3%) |
| `notes/NotesScreen.kt` | 1916/1960 (97.8%) | 56/61 (91.8%) | 41/45 (91.1%) | 159/161 (98.8%) |
| **Aggregate (summed counters, 10 files, none missing; generated Hilt classes excluded)** | 10982/11246 (97.7%) | 546/603 (90.5%) | 288/308 (93.5%) | 1028/1043 (98.6%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `NoteEditors.kt:74` (1 of 14 branches of the `unsentNewNote` filter) | New note goes on with an unsent new note | Both outcomes that matter (unsent returned; created or empty not returned) are tested, including the new test. The missed branch is a short-circuit variant of the same condition. Non-material |
| `NoteEditorViewModel.kt:105,132,149,215,314,326,330,340,357` (single branches) | Defensive guards: reopen of a new note, `closed` in `pending()`, `create()` finding a note already set, clamping in `insertImage` | Justified gap. The reachable variants are tested (reopen tests, `typing and a photo on a new note create it only once`, `insertImage` cases) |
| `NoteEditorScreen.kt:154-156` (camera `ActivityNotFoundException`), `:77`, `:273`, `:350-351` and branches | No camera app; status/message strings (SAVING, PHOTO_OFFLINE/FAILED) | Robolectric always resolves the camera intent. The resulting VM path (PHOTO_UNREADABLE) is tested. The strings are non-material, and the states are tested in the VM |
| `NotesScreen.kt:64,187` and branches at `:60-61,70,184,211` | Placeholder outside a Hilt activity; colour/chip variants | Covered by the shell's own navigation tests (outside this selection). Rendering variants are non-material |
| `NotesRepository.kt:184-185,192-193`, branches `:121,178,214` | CancellationException rethrow, IllegalArgumentException catch, paging edges | Defensive. Malformed answers (serialization, invalid date) are tested |
| `NoteImages.kt:87` (SecurityException), branches `:73-81` | A revoked photo URI | Same handling as the tested IOException path. Robolectric cannot throw it without a custom provider. Low risk |
| `MarkdownView.kt:138` and branches | Inline/rule style variants | Non-material. Every block kind is rendered in a test |

## Unable to test

- Real camera/gallery apps, a real device and a real server are not used: the pickers are simulated with Robolectric `receiveResult`, the server with MockWebServer or mocks. The acceptance criteria do not ask for an on-device end-to-end run, so no follow-up is needed.
- That `NoteSaveScope` outlives a real process-level teardown (app killed) is not testable in unit tests and is outside the spec (no offline cache, by assumption).

## Summary

Tests added and all validation passed. Task-scoped coverage is sufficient, and no production defects were found.
