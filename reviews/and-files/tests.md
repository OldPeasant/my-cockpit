# Test Report

Date: 2026-10-22

Task: and-files - Android files

Scope: task base 236a674ae0cd7d802b090b62351109d53d86e8d4 to HEAD ba712b4d2914a827c401cbc03c7695406c7e316a on branch main, limited to `android/app/src/{main,test}/java/ch/sonensei/cockpit/files` (plus the task's manifest and resource files, which are not instrumented). Task commits touching android: 7cc9ac06f, 387cc679c, e205d09cf, 47962da18 (first-review fixes CR-1..CR-5), 83e84d55c (test-writer regression tests) and ba712b4d2 (second-review fixes CR-1/TW-1 and CR-2). The parent supplied the base and HEAD; `git log 236a674ae..HEAD -- android` lists exactly these commits. The working tree was clean. This report replaces the earlier one, which described 577c0dcc3, before the second-review fixes.

Requirements:
- tasks/and-files.md acceptance criteria 1 and 2, and its recorded assumptions (Save to device = ACTION_CREATE_DOCUMENT; Open = cache/files-open/{file id}/ + ACTION_VIEW through the app's `.files` FileProvider; uploads as a streaming multipart POST; browse/open/save/upload only; CR-4 cache preparation stays on the main thread)
- SPEC.md §9.2 item 7, §8.2
- reviews/and-files/review.md (both reviews) and reviews/and-files/fixes.md (both sections)
- AGENTS.md, docs/ARCHITECTURE.md "Coverage"
- Parent instructions: update the report for the final code; add tests only for a real gap in the new code; this sub-agent cannot run Gradle, the parent ran it

## Test files changed

In this session: none. No real gap was found in the code of ba712b4d2 (see "Coverage"), so no test was added.

Committed in the task: FilesRouteInAppTest.kt, PickedFilesProvider.kt (test content provider), DeviceFilesTest.kt, FilesRepositoryTest.kt, FilesScreenTest.kt, FilesViewModelTest.kt. In ba712b4d2 the fixer added FilesViewModelTest `a save ignored while another file downloads discards its document, the running download is not touched` and FilesScreenTest `while a file downloads, no other file can be opened or saved`. It also updated the end of FilesViewModelTest `a download cancelled with the screen discards what it wrote`: the ignored save is now discarded exactly once, where the test used to assert that it discarded nothing. This change follows the TW-1 fix and does not weaken the test.

## Tests added or updated

Count: 0 in this session. The files suite has 66 tests: DeviceFilesTest 18, FilesRepositoryTest 9, FilesRouteInAppTest 14, FilesScreenTest 9, FilesViewModelTest 16.

## Requirement coverage

Focused command (F), run by the parent on HEAD ba712b4d2 at 13:24 +0200 in `android/`: `./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*' createDebugUnitTestCoverageReport` -> exit 0. The JUnit XML (testsuite timestamps 11:24:30Z to 11:24:52Z) shows 66 tests, 0 failures, 0 errors, 0 skipped.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Browse: real screen in the app, folders open, system back goes up | FilesRouteInAppTest `the Files screen is the real drive, not the placeholder`, `a folder opens, and the system back goes up ...`; FilesViewModelTest `browsing goes into folders, back up, and to any folder of the path`, `a failed load keeps what was shown and says why`; FilesScreenTest browse tests | F | Pass |
| AC1 Open with intents (FileProvider): download -> ACTION_VIEW with a content URI from the package's `.files` authority and a read grant; the bytes can be read through the provider; no app -> toast; failed download -> banner | FilesRouteInAppTest `tapping a file downloads it and opens it in another app ...`, `a file no app on the phone opens says so ...`, `a failed download says so and opens nothing`; DeviceFilesTest provider, launch and target tests | F | Pass |
| AC1 Save to device via ACTION_CREATE_DOCUMENT; cancelling downloads nothing | FilesRouteInAppTest `save to device asks where, then writes the file there and says so`, `a cancelled save-as picker downloads nothing`; DeviceFilesTest openForWriting tests | F | Pass |
| AC1 Upload from device: picker -> described -> uploaded into the folder shown -> reload; failure; cancel | FilesRouteInAppTest `files picked on the device are uploaded ...`, `a failed upload shows why`, `a cancelled file picker uploads nothing`; DeviceFilesTest describePicked tests; FilesViewModelTest upload tests | F | Pass |
| 1st review CR-1: a second tap while one file downloads touches no cache file, and the first file still opens | FilesViewModelTest `a second open while one downloads touches no file, and the first is still opened`; FilesRouteInAppTest `tapping another file while one downloads leaves the first intact, and it still opens` | F | Pass |
| 1st review CR-2: a pending save survives activity recreation | FilesRouteInAppTest `a save still arrives when the activity was recreated while the save-as picker was open` | F | Pass |
| 1st review CR-3: a failed or cancelled open or save discards what it wrote; a discard that throws is harmless | FilesViewModelTest `a failed open deletes what it wrote into the cache`, `a failed save discards the chosen document, a successful one keeps it`, `a download cancelled with the screen discards what it wrote`, `a save cancelled with the screen discards the chosen document`, `a discard that fails does not hide why the save failed, and the next save still works`; DeviceFilesTest `discarding a failed save deletes the created document, ...`; FilesRouteInAppTest `a failed save deletes the document the save-as picker created` | F | Pass |
| 1st review CR-4: picked files are described off the caller thread, into the folder shown when picked | FilesViewModelTest `picked files are described in the view model's IO coroutine and go into the folder shown when picked` | F | Pass |
| 1st review CR-5: cache names are at most 200 UTF-8 bytes, cut between characters, extension kept | DeviceFilesTest `long names are cut to 200 UTF-8 bytes ...`, `two-byte characters count as two bytes and are never cut in half`, `an extension of up to 16 characters is kept, ...`, `a long name written to the cache can be opened` | F | Pass |
| 2nd review CR-1 / TW-1: while a file downloads, no other file can be opened or saved through the screen, and a save that the view model ignores anyway discards its document without touching the running download | FilesScreenTest `while a file downloads, no other file can be opened or saved`; FilesViewModelTest `a save ignored while another file downloads discards its document, the running download is not touched`, `a download cancelled with the screen discards what it wrote`; FilesRouteInAppTest `saving another file while one downloads leaves no empty document where the user chose` | F | Pass |
| 2nd review CR-2: callbacks kept by the view model hold the application, not the activity | Exercised by every FilesRouteInAppTest test that opens, saves or uploads through the real route (it checks they still work with the application context) | F | Pass for the behavior. That the activity is not retained is not asserted: see "Unable to test" |
| AC2 `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*'` passes, covering browse and upload states | the whole files suite (66 tests) | F | Pass |

All of AC1 runs under Robolectric with a mocked `FilesRepository` (Hilt `@BindValue`) and Robolectric's activity and intent shadows. Nothing runs on a real device or against a real server.

Note on the TW-1 regression test: once the Save item is disabled, the test's click finds no started activity and the test ends in its "no save can start while a file downloads" branch, so it passes. The FilesScreenTest above asserts directly that the items are disabled, and the FilesViewModelTest covers the view model's safety net.

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*' createDebugUnitTestCoverageReport` (run by the parent, 2026-10-22 13:24 +0200, on HEAD ba712b4d2)

Result:

Pass: exit 0, 66 tests, 0 failures, 0 errors, 0 skipped. ktlintCheck and lintDebug are clean.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run. This sub-agent cannot run Gradle, and the parent reported no full android run for this revision.

## .taskcheck

Result:

Not run. This sub-agent cannot run Gradle or `sh scripts/check.sh`. The parent runs it before `done`.

## Bugs found

None open. TW-1 from the previous report (an ignored concurrent "Save to device" left the picker's empty document behind) is fixed in ba712b4d2. Its regression test and the fixer's new tests pass in F.

## Coverage

Status: verified for HEAD ba712b4d2 with the complete 66-test files suite.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. Four of the five Kotlin files are new in this task. FilesScreen.kt was a placeholder that the task replaced almost completely, so whole-file coverage is close to changed-line coverage here.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-files; base 236a674ae0cd7d802b090b62351109d53d86e8d4; HEAD ba712b4d2914a827c401cbc03c7695406c7e316a. The parent supplied the base and HEAD. The commits come from `git log 236a674ae..HEAD -- android` (listed under Scope). The file scope is the one the parent supplied.
- Source/test snapshot identifier (including uncommitted inputs): HEAD ba712b4d2, clean working tree, no uncommitted inputs. Production sha256: DeviceFiles.kt 8b16bf1d1907516b71e40ff04c1dfb6d2e52a04bf44945386fa9ac9b8c0414f2, FilesModels.kt 02758107896743fd14bbad0d3e45ce8b6fdbadd52be53038ae95e0754cc5a181, FilesRepository.kt 905392642d1cc9bacaa13453edf10010c807c3eab4de12eccacdaf53791ec3c8, FilesScreen.kt a556731bb4ba29b55f5578c662695ad0c73aab5261edfa7dfbe19f1412535371, FilesViewModel.kt 6845f0a5e091921e95fb09725c2c24d912bb9ac0a6c855922723fc94bb199934. Test sha256: DeviceFilesTest.kt 138225721fe970972f58e364b42de798287f310e2f3d465b328907e53f0432c6, FilesRepositoryTest.kt 21f5475da14817b07b8edc5dc5af546dd0bd7979d1b091d47b11095558dfa3a6, FilesRouteInAppTest.kt c5051d12f1cdbba7f3b2a853853835784ff40a9e1a3d3b7bb66cae252633297f, FilesScreenTest.kt 1ded7c0f4d17c57b99beb863aa82c390884eeaa316eded00f54e60988cd7dc9b, FilesViewModelTest.kt 95fd0a7d61527beadfc78b560afb4272c9c02b457b8103855e0f68e84ef6c6ad, PickedFilesProvider.kt 69efe9e0c80efd152e401d32c9ba2866ab17be758689e28a11b805eb9bef4fd4.
- Exact included files: android/app/src/main/java/ch/sonensei/cockpit/files/{DeviceFiles,FilesModels,FilesRepository,FilesScreen,FilesViewModel}.kt. All five are in the report. Excluded: AndroidManifest.xml, res/xml/files_open_paths.xml and res/values/strings_files.xml. These are configuration and resources that JaCoCo cannot instrument, and the FileProvider tests still exercise them. The Hilt/Dagger-generated `*_Factory.java` and `*_HiltModules*.java` classes and Compose's `LazyDsl.kt` entries in the package report are generated code and are not counted. No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `createDebugUnitTestCoverageReport`; `./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*' createDebugUnitTestCoverageReport` in `android/`, run by the parent; selection `ch.sonensei.cockpit.files.*`.
- Run start/completion time and exit status: the test suites ran 2026-10-22 13:24:30 to 13:24:53 +0200 and the report was written at 13:24:56 +0200. Exit 0, as the parent reported.
- Generated report paths and report hashes/run identifier: android/app/build/reports/coverage/test/debug/report.xml, sha256 16e686cf26e3d7e26c168ecaff02c27082bc60a39d9f030885d05b5dca961b35. This differs from the previous report (7d66377f…), so the run regenerated it. The counters were read with `python3 -I .tmp/and-files-cov.py android/app/build/reports/coverage/test/debug/report.xml`.
- Evidence outputs are fresh and inputs stayed unchanged: the parent states that the run used exactly this code. The JUnit XML lists the tests added in ba712b4d2 (FilesScreenTest 9, FilesViewModelTest 16), so the run included the final test files. Neither this session nor anyone else changed the tree afterwards (`git status` is clean).
- Final run after latest test additions and production fixes: yes.

Values below are covered/total (percentage), read from the report.xml counters above. JaCoCo gives instructions in place of statements and methods in place of functions.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/files/DeviceFiles.kt` | 543/553 (98.2%) | 56/60 (93.3%) | 19/19 (100%) | 87/87 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/files/FilesModels.kt` | 224/224 (100%) | 15/16 (93.8%) | 17/17 (100%) | 26/26 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/files/FilesRepository.kt` | 522/538 (97.0%) | 18/20 (90.0%) | 23/24 (95.8%) | 78/81 (96.3%) |
| `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt` | 3467/3625 (95.6%) | 103/119 (86.6%) | 78/88 (88.6%) | 291/301 (96.7%) |
| `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt` | 1170/1195 (97.9%) | 52/58 (89.7%) | 58/58 (100%) | 118/119 (99.2%) |
| Aggregate (summed counters; the 3 manifest/resource files excluded as above, none missing) | 5926/6135 (96.6%) | 244/273 (89.4%) | 195/206 (94.7%) | 600/614 (97.7%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `FilesViewModel.kt:172-174` (new: discard of an ignored save) | 2nd review CR-1/TW-1 safety net | Covered by FilesViewModelTest `a save ignored while another file downloads ...` and `a download cancelled with the screen ...`. Line 173 is only partly covered: the path where the ignored save's `discard()` throws, which `runCatching` swallows, is not exercised. The same `runCatching` pattern on the failure path is tested (`a discard that fails does not hide why ...`). Low risk. No test added. |
| `FilesScreen.kt:384,391,410,419,435,441` (new: `idle`/`enabled`) | 2nd review CR-1: no open/save while a download runs | Fully covered both ways (enabled by the existing route and screen tests, disabled by FilesScreenTest `while a file downloads, no other file can be opened or saved`). Branches 390 (3 of 8 missed) and 403 are Compose-generated `remember` paths of the lambdas. Left. |
| `FilesScreen.kt:121-122,143` (new: application context) | 2nd review CR-2 | Covered: every route open/save/upload test runs through these lines. That no activity is retained is not measurable with line coverage (see "Unable to test"). |
| `FilesScreen.kt:133` branch (`file == null`) | Picker result while `saving == null` | After CR-2 this happens only if the saved state is lost. Not reachable through the UI. Left. |
| `FilesViewModel.kt:148,150` null branches of `cache?.` | Discard/Open when `prepare()` never set the cache (only if `target()` throws) | Defensive: `OpenedFiles.target` does not throw in practice. Left. |
| `FilesViewModel.kt:233-234` | `openCache` IOException -> null -> Failed(null) | Cannot be triggered reliably under Robolectric. The repository's "target null" path is tested in FilesRepositoryTest. Left. |
| `FilesViewModel.kt:108,119,188,250` branches | `loaded` already true; `goTo` beyond the path; `ok` after a successful run in `finally`; 413 vs other failures | Partly covered by the tests. The remaining cases are trivial. Left. |
| `DeviceFiles.kt:156` loop-exit branch | `prefixOfBytes` where the whole stem fits | Unreachable: `fitBytes` only cuts when the stem plus the extension exceed 200 bytes. Left. |
| `DeviceFiles.kt:43,51,100` branches | null cursor, URI without a last segment, `listFiles()` null | Defensive null paths. Left. |
| `FilesScreen.kt:87-91,97` | Placeholder outside a Hilt activity | Fallback for non-Hilt hosts (shell navigation tests). Left. |
| `FilesScreen.kt:355,357`, branch 352 | Upload texts for OFFLINE/FAILED | Trivial mapping. The states are tested in FilesViewModelTest. Left. |
| `FilesScreen.kt:488,490,491,493`, branch 485 | Tile icons for image, audio, video and other files | Purely visual. `fileKind` is tested in DeviceFilesTest. Left. |
| `FilesScreen.kt:118,166-176,211-215,246` | Compose/Kotlin-generated code: exhaustive `when` throws, data-class default lambdas, banner text/flag branches for the two message kinds | Generated code or trivial. Left. |
| `FilesRepository.kt:48,140-141`, branches 91, 168 | Hilt `@Provides`; CancellationException rethrown; MAX_PAGES; unparseable mime | Unchanged since the previous report. DI glue and defensive guards. Left. |
| `FilesModels.kt:52` branch | One case of a model mapping | Unchanged since the previous report. Trivial. Left. |

## Unable to test

- On a real device (system pickers, third-party viewers, FileProvider grants across processes, a real rotation while the picker or a download is running): Robolectric stands in for all of these. An instrumented or manual check on a device is an optional follow-up.
- 2nd review CR-2 (no activity leak): that the view model's lambdas no longer retain the destroyed activity is not asserted. A reliable test would need a leak detector or GC-based weak-reference checks under Robolectric, and neither is set up in this project. The change is small and visible in review (`context.applicationContext` at FilesScreen.kt:121). No follow-up needed beyond the review.
- The "save ignored by the view model" path can no longer be reached through the screen, because the Save item is disabled. It is tested at the view-model level only. The route-level TW-1 test confirms that no picker starts.
- CR-4's deliberate part (`OpenedFiles.target` on the main dispatcher) is a recorded assumption, not tested behavior.
- Observation outside the fixes, not counted as a finding: in `describePicked`, `resolver.getType(uri)` (DeviceFiles.kt:50) is outside the `runCatching`. A provider that throws there would fail the `uploadPicked` coroutine. Before CR-4 it would have thrown in the activity-result callback, so this is not a regression.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. TW-1 is fixed in ba712b4d2, and its regression test and the fixer's new screen and view-model tests pass. The parent ran the focused command on HEAD ba712b4d2: 66 tests passed, 0 failed, and ktlint and lint are clean. Task-scoped coverage is 97.7% of lines (600/614) and 89.4% of branches (244/273). No test was added in this session because the new code has no meaningful gap.
