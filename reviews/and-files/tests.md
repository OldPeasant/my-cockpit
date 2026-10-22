# Test Report

Date: 2026-10-22

Task: and-files - Android files

Scope: task base 236a674ae0cd7d802b090b62351109d53d86e8d4 to HEAD 577c0dcc3a9b5a675c8187f983a7f4996b9bdac7 on branch main, limited to `android/app/src/{main,test}/java/ch/sonensei/cockpit/files` (plus the task's manifest and resource files, which are not instrumented). The task commits in this clone are 7c4cdd757, 48e95279e, 670ec3f22, b2da3656f (production fixes for CR-1..CR-5) and 577c0dcc3 (fixes report only). The parent named them ad8cfd84f, 75b350c21, 82d213a9e and 5903df968. Those are the same commits before a rebase: `git diff 5903df968 HEAD -- android` is empty. This report replaces the earlier one, which covered the code before the CR fixes. It adds 5 tests, which are uncommitted in the working tree (see "Test files changed").

Requirements:
- tasks/and-files.md acceptance criteria 1 and 2, and its recorded assumptions (Save to device = ACTION_CREATE_DOCUMENT; Open = cache/files-open/<id>/ + ACTION_VIEW through the `<app>.files` provider; uploads as a streaming multipart POST; browse/open/save/upload only; CR-4 cache preparation stays on the main thread)
- SPEC.md §9.2 item 7, §8.2
- reviews/and-files/review.md (CR-1..CR-5) and reviews/and-files/fixes.md
- AGENTS.md, docs/ARCHITECTURE.md "Coverage"
- Parent instructions: update the report for the final code; add tests only for real gaps in the fix code; this sub-agent cannot run Gradle

## Test files changed

In this session (uncommitted, not run yet: the parent must run them):
- `android/app/src/test/java/ch/sonensei/cockpit/files/DeviceFilesTest.kt` (+2 tests), sha256 138225721fe970972f58e364b42de798287f310e2f3d465b328907e53f0432c6
- `android/app/src/test/java/ch/sonensei/cockpit/files/FilesViewModelTest.kt` (+2 tests), sha256 9ef3b4f157b9e2910cdfa663a9c7a0668a11fdeabe1906d5631f72ee22df279d
- `android/app/src/test/java/ch/sonensei/cockpit/files/FilesRouteInAppTest.kt` (+1 test), sha256 c5051d12f1cdbba7f3b2a853853835784ff40a9e1a3d3b7bb66cae252633297f

Earlier in the task (committed): FilesRouteInAppTest.kt, PickedFilesProvider.kt, DeviceFilesTest.kt, FilesRepositoryTest.kt, FilesScreenTest.kt, FilesViewModelTest.kt.

## Tests added or updated

Count: 5 new tests in this session. None of them has been run: this sub-agent cannot run Gradle.

- DeviceFilesTest `two-byte characters count as two bytes and are never cut in half` (CR-5: the 2-byte UTF-8 branch of the byte count had no test; this covers an even budget and an odd one)
- DeviceFilesTest `an extension of up to 16 characters is kept, a longer one is cut like the rest of the name` (CR-5: the MAX_EXTENSION boundary at 16 and 17 characters)
- FilesViewModelTest `a save cancelled with the screen discards the chosen document` (CR-3: cancelling was only tested for "open", not for "save")
- FilesViewModelTest `a discard that fails does not hide why the save failed, and the next save still works` (CR-3: if the discard throws, the error is swallowed; the message, state and single-flight stay correct)
- FilesRouteInAppTest `saving another file while one downloads leaves no empty document where the user chose` (regression test for TW-1. **Expected to fail on the current code**)

With the committed suite (59 tests), the files suite now has 64 tests.

## Requirement coverage

Focused command (F), run by the parent on the final production code at 13:16 +0200: `cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*' createDebugUnitTestCoverageReport` -> exit 0. The JUnit XML shows DeviceFilesTest 16, FilesRepositoryTest 9, FilesRouteInAppTest 13, FilesScreenTest 8 and FilesViewModelTest 13: 59 tests, 0 failures, 0 errors, 0 skipped. The 5 tests added in this session were not part of F.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Browse: real screen in the app, folders open, system back goes up | FilesRouteInAppTest `the Files screen is the real drive, not the placeholder`, `a folder opens, and the system back goes up ...`; FilesViewModelTest `browsing goes into folders, back up, and to any folder of the path`, `a failed load keeps what was shown and says why`; FilesScreenTest browse tests | F | Pass |
| AC1 Open with intents (FileProvider): download -> ACTION_VIEW with a content URI from `<pkg>.files` and a read grant; the bytes can be read through the provider; no app -> toast; failed download -> banner | FilesRouteInAppTest `tapping a file downloads it and opens it in another app ...`, `a file no app on the phone opens says so ...`, `a failed download says so and opens nothing`; DeviceFilesTest provider, launch and target tests | F | Pass |
| AC1 Save to device via ACTION_CREATE_DOCUMENT; cancelling downloads nothing | FilesRouteInAppTest `save to device asks where, then writes the file there and says so`, `a cancelled save-as picker downloads nothing`; DeviceFilesTest openForWriting tests | F | Pass |
| AC1 Upload from device: picker -> described -> uploaded into the folder shown -> reload; failure; cancel | FilesRouteInAppTest `files picked on the device are uploaded ...`, `a failed upload shows why`, `a cancelled file picker uploads nothing`; DeviceFilesTest describePicked tests; FilesViewModelTest upload tests | F | Pass |
| CR-1 a second tap while one file downloads touches no cache file, and the first file still opens | FilesViewModelTest `a second open while one downloads touches no file, and the first is still opened`; FilesRouteInAppTest `tapping another file while one downloads leaves the first intact, and it still opens` | F | Pass |
| CR-2 a pending save survives activity recreation (rememberSaveable + PendingSaveSaver) | FilesRouteInAppTest `a save still arrives when the activity was recreated while the save-as picker was open` | F | Pass |
| CR-3 a failed open or save discards what it wrote | FilesViewModelTest `a failed open deletes what it wrote into the cache`, `a failed save discards the chosen document, a successful one keeps it`; DeviceFilesTest `discarding a failed save deletes the created document, and a provider that refuses does not crash`; FilesRouteInAppTest `a failed save deletes the document the save-as picker created` | F | Pass |
| CR-3 cancelled downloads discard what they wrote | FilesViewModelTest `a download cancelled with the screen discards what it wrote` (open) | F | Pass |
| CR-3 cancelled save discards; a discard that throws is harmless | FilesViewModelTest `a save cancelled with the screen discards the chosen document`, `a discard that fails does not hide why the save failed, and the next save still works` | not run | Not run: added in this session, the parent must run them |
| CR-3 a save that is never carried out (ignored by the single-flight check) leaves no empty document | FilesRouteInAppTest `saving another file while one downloads leaves no empty document where the user chose` | not run | Not run. Expected to **fail** on the current code: see TW-1 |
| CR-4 picked files are described off the caller thread, into the folder shown when picked | FilesViewModelTest `picked files are described in the view model's IO coroutine and go into the folder shown when picked` | F | Pass |
| CR-5 cache names are at most 200 UTF-8 bytes, cut between characters, extension kept | DeviceFilesTest `long names are cut to 200 UTF-8 bytes ...`, `a long name written to the cache can be opened` | F | Pass |
| CR-5 2-byte characters and the 16-character extension boundary | DeviceFilesTest `two-byte characters count as two bytes and are never cut in half`, `an extension of up to 16 characters is kept, ...` | not run | Not run: added in this session, the parent must run them |
| AC2 `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*'` passes, covering browse and upload states | the whole files suite | F (59 committed tests) | Pass for the committed 59 tests. Not run with the 5 new ones (one of them is expected to fail, TW-1) |

All of AC1 runs under Robolectric with a mocked `FilesRepository` (Hilt `@BindValue`) and Robolectric's activity and intent shadows. Nothing runs on a real device or against a real server.

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*' createDebugUnitTestCoverageReport` (run by the parent, 2026-10-22 13:16 +0200, on the final production code)

Result:

Pass for the committed tests: exit 0, 59 tests, 0 failures. The JUnit XML in android/app/build/test-results/testDebugUnitTest/ is timestamped 11:15:28Z to 11:15:39Z.

The 5 tests added in this session have not run. To run them: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*' createDebugUnitTestCoverageReport`. The expected result is that FilesRouteInAppTest `saving another file while one downloads leaves no empty document where the user chose` fails (TW-1) and the other 4 pass.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run. This sub-agent cannot run Gradle, and the parent reported no full run for this revision.

## .taskcheck

Result:

Not run. This sub-agent cannot run Gradle or `sh scripts/check.sh`; the parent runs it before `done`.

## Bugs found

### A second "Save to device" while another file downloads leaves an empty document behind, with no message

Finding ID:

`TW-1`

Test:

`FilesRouteInAppTest.saving another file while one downloads leaves no empty document where the user chose`

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt:168` (the single-flight `return` before the `try/finally` that discards), together with `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt:127-133,142-145,388-417`. Only the downloading row's menu is replaced by a spinner. Every other file's "Save to device" still launches the picker, and the picker result goes to `viewModel.save(...)`.

Requirement:

Review finding CR-3 (fixed as "a failed or interrupted Save to device must not leave an empty or truncated document"); AC1 "download ... with intents". The save-as picker creates the document before anything is downloaded.

Expected behavior:

The user starts file A downloading, then picks a place for file B with "Save to device". The place the user chose must not keep an empty `B` with no message. Any of these fixes would do: no save can start while a download runs, the save is queued and carried out, or the created document is discarded (and the user is told).

Actual behavior:

`download()` returns at `if (downloadJob?.isActive == true) return` without calling `discard`. The empty document the picker created stays where the user chose, and no banner or toast appears. This is the same harm as CR-3, through another path. The fixes report calls it intended ("A save ignored by the single-flight check never discards anything"), and the committed FilesViewModelTest `a download cancelled with the screen discards what it wrote` asserts it: `assertFalse("the ignored save discarded something", discarded)` (FilesViewModelTest.kt, at the end of that test). If the fixer chooses "discard the ignored save's document", they have to revise that assertion as well. I did not change it, because it belongs to the committed suite.

Severity:

Medium

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: verified for the final production code (it equals HEAD, 5903df968 = b2da3656f for android) with the committed 59-test suite. **It is stale for the 5 tests added in this session**, so the parent should rerun the coverage command. Because TW-1's test is expected to fail, that rerun gives partial/failed diagnostic coverage until TW-1 is fixed.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. Four of the five Kotlin files are new in this task. FilesScreen.kt was a placeholder that the task replaced almost completely.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-files; base 236a674ae0cd7d802b090b62351109d53d86e8d4; HEAD 577c0dcc3a9b5a675c8187f983a7f4996b9bdac7. The parent supplied the base and the commit list (pre-rebase hashes ad8cfd84f, 75b350c21, 82d213a9e, 5903df968). They match `git diff --stat 236a674ae..HEAD`, which changes only the task's `touches`, the manifest and the two feature resource files, plus tasks/ and reviews/.
- Source/test snapshot identifier (including uncommitted inputs): the measured run used the committed tree, and the android content equals 5903df968 and HEAD. Production sha256: DeviceFiles.kt 8b16bf1d…14f2, FilesModels.kt 02758107…a3c8, FilesRepository.kt 90539264…c3e8, FilesScreen.kt 90cffa03…037b970f, FilesViewModel.kt 26ba2115…6d834. Committed test files at the run: DeviceFilesTest.kt ca60905a…abdc32, FilesRepositoryTest.kt 21f5475d…dfa3a6, FilesRouteInAppTest.kt 69a7d062…6eb9eb5f, FilesScreenTest.kt f2468822…b5fb20a, FilesViewModelTest.kt 64f06214…8836, PickedFilesProvider.kt 69efe9e0…eb9bef4fd4. The working tree was clean before this session.
- Exact included files: android/app/src/main/java/ch/sonensei/cockpit/files/{DeviceFiles,FilesModels,FilesRepository,FilesScreen,FilesViewModel}.kt, all present in the report. Excluded: AndroidManifest.xml, res/xml/files_open_paths.xml and res/values/strings_files.xml, which are configuration and resources that JaCoCo cannot instrument (the FileProvider tests still exercise them). The Hilt/Dagger-generated `*_Factory.java` and `*_HiltModules*.java` classes in the same package are generated code, not counted. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `createDebugUnitTestCoverageReport`; `./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*' createDebugUnitTestCoverageReport` in `android/`, run by the parent; selection `ch.sonensei.cockpit.files.*`.
- Run start/completion time and exit status: the test suites ran 2026-10-22 13:15:28 to 13:15:40 +0200 and the report was written at 13:16:19 +0200. Exit 0 (reported by the parent).
- Generated report paths and report hashes/run identifier: android/app/build/reports/coverage/test/debug/report.xml, sha256 7d66377f0e55ab05458a32686f885e224d18cdfcfb92f3dd8cfa8410edbab5f9. It differs from the previous report (7bec2dfc…), so this run regenerated it.
- Evidence outputs are fresh and inputs stayed unchanged: the production inputs are unchanged by content. The source files have mtime 13:17:15, after the run, because the rebase rewrote them, but `git diff 5903df968 HEAD -- android` is empty. The test inputs changed after the run: this session added 5 tests.
- Final run after latest test additions and production fixes: no. It ran after the CR fixes but before this session's 5 tests. The TW-1 fix is still pending.

Values below use covered/total (percentage) from the report.xml counters above. JaCoCo gives instructions in place of statements and methods in place of functions.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/files/DeviceFiles.kt` | 541/553 (97.8%) | 55/60 (91.7%) | 19/19 (100%) | 87/87 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/files/FilesModels.kt` | 224/224 (100%) | 15/16 (93.8%) | 17/17 (100%) | 26/26 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/files/FilesRepository.kt` | 522/538 (97.0%) | 18/20 (90.0%) | 23/24 (95.8%) | 78/81 (96.3%) |
| `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt` | 3437/3595 (95.6%) | 99/115 (86.1%) | 78/88 (88.6%) | 285/295 (96.6%) |
| `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt` | 1126/1152 (97.7%) | 51/58 (87.9%) | 57/57 (100%) | 116/117 (99.1%) |
| Aggregate (summed counters; the 3 manifest/resource files excluded as above, none missing) | 5850/6062 (96.5%) | 238/269 (88.5%) | 194/205 (94.6%) | 592/606 (97.7%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `FilesViewModel.kt:168` (path with an ignored save) | CR-3: an ignored save leaves the picker's document behind | TW-1; FilesRouteInAppTest `saving another file while one downloads ...` |
| `FilesViewModel.kt:181` (save cancellation; discard throwing) | CR-3 cancel/error handling of the discard | FilesViewModelTest `a save cancelled with the screen discards the chosen document`, `a discard that fails does not hide why ...` (not run yet) |
| `FilesViewModel.kt:148-150`, null branches of `cache?.` | Discard/Open when `prepare()` never set the cache (only if `target()` throws) | Defensive: `OpenedFiles.target` does not throw in practice. Left. |
| `FilesViewModel.kt:226-227` | `openCache` IOException -> null -> Failed(null) | Cannot be triggered reliably under Robolectric. The repository's "target null" path is tested in FilesRepositoryTest. Left. |
| `FilesViewModel.kt:119,243` branches | `goTo` beyond the path; 413 vs other failures | Partly covered by the tests; the cases left are trivial. Left. |
| `DeviceFiles.kt:180` branch (2-byte code points) | CR-5 byte counting for 2-byte characters | DeviceFilesTest `two-byte characters count as two bytes ...` (not run yet) |
| `DeviceFiles.kt:156` loop-exit branch | `prefixOfBytes` where the whole stem fits | Unreachable: `fitBytes` only cuts when the stem plus the extension exceed 200 bytes. Left. |
| `DeviceFiles.kt:43,51,100` branches | null cursor, URI without a last segment, `listFiles()` null | Defensive null paths. Left. |
| `FilesScreen.kt:131` branch | Picker result while `saving == null` | After CR-2, this happens only if the saved state is lost. Not reachable through the UI. Left. |
| `FilesScreen.kt:87-91,97` | Placeholder outside a Hilt activity | A fallback for non-Hilt hosts (shell navigation tests). Left. |
| `FilesScreen.kt:353,355`, branch 350 | Upload texts for OFFLINE/FAILED | Trivial mapping; the states are tested in FilesViewModelTest. Left. |
| `FilesScreen.kt:480,482,483,485`, branch 477 | Tile icons for image, audio, video and other files | Purely visual; `fileKind` is tested in DeviceFilesTest. Left. |
| `FilesScreen.kt:118,164-174,244,386,398,209-213` | Compose/Kotlin-generated branches: exhaustive `when` throws, data-class default lambdas, `remember` paths | Generated code. Left. |
| `FilesRepository.kt:48,140-141`, branches 91, 168 | Hilt `@Provides`; CancellationException rethrown; MAX_PAGES; unparseable mime | Unchanged since the previous report; DI glue and defensive guards. Left. |

## Unable to test

- On a real device (system pickers, third-party viewers, FileProvider grants across processes, a real rotation while the picker is open): Robolectric stands in for all of these. An instrumented or manual check on a device is an optional follow-up.
- CR-4's deliberate part (`OpenedFiles.target` on the main dispatcher) is a recorded assumption, not tested behavior.
- The 5 tests added in this session could not be run here (no Gradle in this sub-agent). The parent must run them.
- Observation outside the CR fixes, not counted as a finding: in `describePicked`, `resolver.getType(uri)` (DeviceFiles.kt:50) is outside the `runCatching`. A provider that throws there would now fail the `uploadPicked` coroutine in `viewModelScope`. It would have thrown in the activity-result callback before, so this is not a regression. No test was added.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion. TW-1 (Medium): an ignored concurrent "Save to device" leaves the picker's empty document behind with no message. Its regression test is expected to fail until the fixer fixes it. The other 4 new tests close gaps in the CR-3 and CR-5 fix code and still have to be run by the parent. Task-scoped coverage on the final production code is 97.7% of lines and 88.5% of branches, measured before these 5 tests were added.
