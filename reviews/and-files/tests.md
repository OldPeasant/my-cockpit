# Test Report

Date: 2026-10-22

Task: and-files - Android files

Scope: task commits 0978e7bd1 and 8621c16f9 on top of base 99220d2c8a1dcc6c87d3935a955abc276f6b7942 (`git diff 99220d2c8..8621c16f9`), measured at HEAD 8621c16f9929cfac68970b8ad15765c2c19ffb46 plus the uncommitted test changes listed below. Branch main, no uncommitted production changes.

Requirements:
- tasks/and-files.md acceptance criteria 1 and 2, and its recorded assumptions (Save to device = ACTION_CREATE_DOCUMENT; Open = cache/files-open/<id>/ + ACTION_VIEW through the provider with authority `<app>.files`; uploads via multipart POST; browse/open/save/upload only)
- SPEC.md §9.2 item 7, §8.2
- AGENTS.md, docs/ARCHITECTURE.md "Coverage"
- Parent instructions (gaps to close: DeviceFiles content resolver paths, in-app route test)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/files/FilesRouteInAppTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/files/PickedFilesProvider.kt` (new test helper: a fictitious document provider standing in for picker URIs, plus `resetFileProviderCache()`)
- `android/app/src/test/java/ch/sonensei/cockpit/files/DeviceFilesTest.kt` (extended)
- `android/app/src/test/java/ch/sonensei/cockpit/files/FilesRepositoryTest.kt` (extended)

## Tests added or updated

Count: 19 new tests (10 in FilesRouteInAppTest, 7 in DeviceFilesTest, 2 in FilesRepositoryTest). The files suite now has 48 tests (29 before).

## Requirement coverage

Focused command (F): `cd android && ./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*' createDebugUnitTestCoverageReport` -> BUILD SUCCESSFUL, 48 tests, 0 failures, 0 skipped.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Browse: real screen in the app, folder opens, system back goes up | FilesRouteInAppTest: `the Files screen is the real drive, not the placeholder`, `a folder opens, and the system back goes up to the drive root, still on the Files screen`; existing FilesViewModelTest/FilesScreenTest browse tests | F | Pass |
| AC1 Download/open with intents (FileProvider): tap a file -> download -> ACTION_VIEW, content:// URI with authority `<pkg>.files`, read grant, the bytes readable through the provider | FilesRouteInAppTest: `tapping a file downloads it and opens it in another app through this app's FileProvider with a read grant`; DeviceFilesTest: `launching reports whether an app on the phone took the file` | F | Pass |
| AC1 Open with no app installed / failed download | FilesRouteInAppTest: `a file no app on the phone opens says so and starts nothing`, `a failed download says so and opens nothing` | F | Pass |
| AC1 Download = save to device via ACTION_CREATE_DOCUMENT, written into the chosen document, toast; cancel downloads nothing | FilesRouteInAppTest: `save to device asks where, then writes the file there and says so`, `a cancelled save-as picker downloads nothing`; DeviceFilesTest: `saving writes into the chosen document and replaces what it held`, `a save target that cannot be written gives null instead of crashing` | F | Pass |
| AC1 Upload from device: ACTION_OPEN_DOCUMENT (multiple) -> picked URI described (name/type/size) -> repository upload into the folder shown with the picked bytes -> reload; failure state; cancel uploads nothing | FilesRouteInAppTest: `files picked on the device are uploaded into the folder shown, which then reloads`, `a failed upload shows why`, `a cancelled file picker uploads nothing`; DeviceFilesTest: `a picked file is described by its provider's name, size and type, and read only when opened`, `a picked file without a size has an unknown size`, `a blank, missing or unreadable name falls back to the URI's last segment, an unknown type to octet-stream`, `a picked file that is gone or no longer allowed opens as null instead of crashing` | F | Pass |
| Unreadable server answers are `Failed(null)`, not offline; entries without payload skipped | FilesRepositoryTest: `an answer with a malformed id or date is unreadable, not a lost connection`, `an entry whose folder is missing is skipped` | F | Pass |
| AC2 `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*'` passes, covering browse and upload states (Robolectric/Compose UI tests for screens) | all 48 tests in FilesRouteInAppTest, FilesScreenTest, FilesViewModelTest, FilesRepositoryTest, DeviceFilesTest | F | Pass |

All of AC1 is run under Robolectric with a mocked `FilesRepository` (Hilt `@BindValue`) and Robolectric's activity/intent shadows. It is not run on a real device or against a real server. The real repository is covered separately against MockWebServer.

## Focused test execution

Command:

`cd android && ./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*' createDebugUnitTestCoverageReport`

Result:

Pass. BUILD SUCCESSFUL. The suites were DeviceFilesTest 13, FilesRepositoryTest 9, FilesRouteInAppTest 10, FilesScreenTest 8 and FilesViewModelTest 8: 48 tests, 0 failures, 0 errors, 0 skipped.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Pass. BUILD SUCCESSFUL in 1m 42s: 161 suites, 1596 tests, 0 failures, 0 errors, 0 skipped.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` ran the tfp gate tests (20 OK), server ktlint + tests, web eslint + tests (166 files, 3453 tests) + build, and android ktlint + unit tests + lint. It ended with "== all checks passed".

## Bugs found

None. The first run had one failure, and it came from the test environment, not the app. `FileProvider` caches its share roots in a static map, but Robolectric gives each test a new data dir while keeping statics. So a test that used the provider after another one was refused with "Failed to find configured root". On a phone the cache dir is the same for the whole process, so this does not happen there. The tests now clear that cache before they run (`resetFileProviderCache()` in PickedFilesProvider.kt). No production change is needed.

## Coverage

Status: verified (focused run, fresh, exit 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. Four of the five Kotlin files are new. FilesScreen.kt was a placeholder that the task replaced almost completely (474 lines added).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-files; base 99220d2c8a1dcc6c87d3935a955abc276f6b7942; HEAD 8621c16f9929cfac68970b8ad15765c2c19ffb46. The task-owned commits 0978e7bd1 and 8621c16f9 came from the parent and match `git diff --stat 99220d2c8..HEAD` and the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): production = HEAD 8621c16f9 (no uncommitted production changes). Test file sha256 values: DeviceFilesTest.kt a902748e…dcb1, FilesRepositoryTest.kt 21f5475d…a6a6, FilesRouteInAppTest.kt a42f1fb2…7b11, FilesScreenTest.kt f2468822…a3b31b5fb20a, FilesViewModelTest.kt f6d0d204…0671, PickedFilesProvider.kt cbd23b6b…e380.
- Exact included files: android/app/src/main/java/ch/sonensei/cockpit/files/{DeviceFiles,FilesModels,FilesRepository,FilesScreen,FilesViewModel}.kt. Excluded: AndroidManifest.xml, res/xml/files_open_paths.xml and res/values/strings_files.xml are configuration/resources that JaCoCo cannot instrument. The provider declaration and paths are still exercised by the FileProvider tests. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `createDebugUnitTestCoverageReport`; `./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*' createDebugUnitTestCoverageReport` in `android/`; selection `ch.sonensei.cockpit.files.*`.
- Run start/completion time and exit status: 2026-10-22 about 13:01:55 to 13:02:13 +0200, exit 0 (BUILD SUCCESSFUL in 14s).
- Generated report paths and report hashes/run identifier: android/app/build/reports/coverage/test/debug/report.xml (mtime 2026-10-22 13:02:13 +0200, sha256 7bec2dfcdd9169935bf4895ca4d6dab3bc33dfbd6283ca6cfcb0f0feafc169f7).
- Evidence outputs are fresh and inputs stayed unchanged: yes. This run regenerated the report: its hash differs from the previous run's (0aa74ea7…). No test or production file changed after this run. ktlintFormat ran inside the same command, before compilation.
- Final run after latest test additions and production fixes: yes. There were no production fixes.

Values below use covered/total (percentage). JaCoCo gives instructions in place of statements and methods in place of functions.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/files/DeviceFiles.kt` | 395/405 (97.5%) | 39/42 (92.9%) | 14/14 (100%) | 57/57 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/files/FilesModels.kt` | 224/224 (100%) | 15/16 (93.8%) | 17/17 (100%) | 26/26 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/files/FilesRepository.kt` | 522/538 (97.0%) | 18/20 (90.0%) | 23/24 (95.8%) | 78/81 (96.3%) |
| `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt` | 3328/3493 (95.3%) | 93/115 (80.9%) | 72/82 (87.8%) | 280/290 (96.6%) |
| `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt` | 904/910 (99.3%) | 44/48 (91.7%) | 46/46 (100%) | 96/97 (99.0%) |
| Aggregate (summed counters; the 3 resource/manifest files excluded as above) | 5373/5570 (96.5%) | 209/241 (86.7%) | 172/183 (94.0%) | 537/551 (97.5%) |

The parent's earlier run had DeviceFiles.kt at 21/57 lines and 23/42 branches, FilesScreen.kt at 230/290 and 74/115, and FilesRepository.kt at 75/81.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `FilesScreen.kt:89`, branches 85/86 | Placeholder outside a Hilt activity (previews, shell navigation tests) | This is a fallback for non-Hilt hosts, not files behavior. The task's tests always host the screen in the Hilt activity or use `FilesContent` directly. Left. |
| `FilesScreen.kt:340,342`, branch 337 | Upload texts for OFFLINE and FAILED | The mapping is trivial. The states are covered in FilesViewModelTest, and TOO_LARGE/DONE/UPLOADING are rendered in screen and route tests. Left. |
| `FilesScreen.kt:467-472`, branches 464, 373 | Tile icons for special folder, PDF, audio, video and other kinds | Purely visual. `fileKind` itself is tested in DeviceFilesTest. Left. |
| `FilesScreen.kt:116,161,231`, branches 95/127/196/197/200/226 | Compose/Kotlin-generated lambda and default-parameter branches; `saving == null` on a picker result | Not meaningfully reachable or generated code. Left. |
| `FilesRepository.kt:48` | Hilt `@Provides` for FileContentApi | DI glue; the route test replaces the repository. Left. |
| `FilesRepository.kt:140-141` | CancellationException rethrown from `call` | Defensive. The view model's load cancellation is covered by the view-model tests, but this exact catch is not hit. Left as a low-risk gap. |
| `FilesRepository.kt` branches 91, 168 | Page loop hitting MAX_PAGES (50); an unparseable mime type | Edge guards; 50 pages would need a large fixture. Left. |
| `FilesViewModel.kt:185` | `openCache` IOException -> Failed(null) when the cache file cannot be created | Hard to trigger deterministically under Robolectric. The repository's "target null -> Failed(null)" path is tested in FilesRepositoryTest. Left. |
| `DeviceFiles.kt` branches 42, 50, 84 | `listFiles()` returning null, URI without a last segment, null-cursor provider | Defensive null paths. Left. |

## Unable to test

- On a real device (Android system pickers, real third-party viewer apps, real FileProvider grants across processes): Robolectric stands in for the pickers and the other app, and checks the intent, the URI authority, the grant flag and that the content can be read through the provider. Follow-up is optional: an instrumented or manual check on a device.
- Upload/download of very large files (streaming memory behavior): not covered by unit tests, and the task does not require it.

## Summary

Tests added and all validation passed. The task-scoped coverage is sufficient, and no production defects were found.
