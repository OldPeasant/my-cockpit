# Code Review

Date: 2026-10-22

Task: and-files - Android files

Scope: `git diff 236a674ae..HEAD -- android`; committed changes only. The parent named the task commits ad8cfd84f, 75b350c21, 82d213a9e, 5903df968. After a rebase the branch holds them as 7c4cdd757, 48e95279e, 670ec3f22, b2da3656f (plus 577c0dcc3, reports only). `git diff 5903df968 HEAD -- android` is empty, so the reviewed code is the same as the named fix revision.

Requirements:
- tasks/and-files.md acceptance criteria and recorded assumptions: streaming multipart upload; "Save to device" via ACTION_CREATE_DOCUMENT; "Open" via cache plus a dedicated FileProvider; browse/open/save/upload only; OpenedFiles.target stays on main (deliberate partial of the earlier CR-4)
- SPEC.md §8.2, §9.2 item 7
- docs/ARCHITECTURE.md, AGENTS.md "Project notes"
- Previous review (CR-1..CR-5 on 8621c16f9) and reviews/and-files/fixes.md

Git range:
- Branch: main
- HEAD: 577c0dcc3a9b5a675c8187f983a7f4996b9bdac7
- Base/start: 236a674ae
- Commits reviewed: 4 code commits (7c4cdd757, 48e95279e, 670ec3f22, b2da3656f) plus 577c0dcc3 (fixes report only)

Summary: 0 P0, 0 P1, 1 P2, 1 P3

Reviewer note: This sub-agent could not run Gradle. The parent ran this in android/ on the final code: `./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*' createDebugUnitTestCoverageReport`. Result: exit 0, 59 tests, 0 failures. `python tasks.py verify and-files` still shows both criteria unrecorded. The parent has to record them before `done`.

## Status of the previous findings

- CR-1 (second tap deletes the file being downloaded): **Fixed.** `FilesViewModel.open` now takes a target factory. `download` checks single-flight first (FilesViewModel.kt:168) and only then calls `prepare()` inside the coroutine (FilesViewModel.kt:174). An ignored tap therefore never calls `OpenedFiles.target`. Covered by FilesViewModelTest.kt:149 and FilesRouteInAppTest.kt:174, which goes through the real route and reads the granted URI.
- CR-2 (pending save lost on recreation): **Fixed.** FilesScreen.kt:126 uses `rememberSaveable` with `PendingSaveSaver` (FilesScreen.kt:157). The saved list holds Strings and is Bundle-safe, and the save and restore are symmetric. The view model survives configuration changes, and after process death a new one handles the result correctly. Covered by FilesRouteInAppTest.kt:249 (`scenario.recreate()`, then the result goes to the new registry).
- CR-3 (failed or cancelled save leaves a document behind): **Fixed for started downloads.** The `try/finally` in FilesViewModel.kt:173-182 calls `discard` under `NonCancellable + io` with errors ignored. For "Save" it deletes the document (DeviceFiles.kt:71 `discardDocument`), and for "Open" it deletes the cache file. Covered by view model, DeviceFiles and route tests. A save that never starts because of single-flight still leaves the document behind; see the new CR-1.
- CR-4 (provider queries and cache work on main): **Partly fixed, remainder accepted by assumption.** `describePicked` runs inside `withContext(io)` (FilesViewModel.kt:194). The folder is captured when the user picks the files, so uploads go to the folder shown at pick time. `OpenedFiles.target` still runs on Main, now once per started download. This matches the recorded assumption, so it is not re-raised.
- CR-5 (cache names cut by characters): **Fixed.** `safeName` caps names at 200 UTF-8 bytes on code-point boundaries (DeviceFiles.kt:141-163) and keeps an extension of up to 16 chars (at most 64 bytes, so it always fits). A result that is empty or only dots becomes `file`. Path separators and control characters are still replaced. Covered by DeviceFilesTest.

Also checked and found sound: the manifest provider is `exported="false"` with `grantUriPermissions="true"` and only `cache/files-open/` is shared. ACTION_VIEW gets a read grant per URI. `ok` is set before the `Open`/`Saved` event is sent, so a successful download is never discarded. Download state is cleared on every completed path. The paging, error mapping, streaming download/upload and folder-load race handling are unchanged since the earlier review and still correct. No file names or contents are logged.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | "Save to device" started while another file downloads is silently dropped and leaves the empty document the picker created**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt:142`, `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt:132`, `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt:168`
    - Category: Correctness
    - Problem: While file A downloads, only A's overflow menu becomes a spinner (FilesScreen.kt:388). Every other row still offers "Save to device". Choosing it launches ACTION_CREATE_DOCUMENT (FilesScreen.kt:142-145), and the picker creates the document at the place the user chose. The result callback then calls `viewModel.save(...)`, which returns early on `downloadJob?.isActive` (FilesViewModel.kt:168). Nothing is downloaded, `discard` is never called, and no message or toast appears. FilesViewModelTest.kt:223/230 asserts this behaviour ("the ignored save discarded something" must be false).
    - Why it matters: The user picked a place to save, and a 0-byte file with the drive file's name is left there with no hint that the save never happened. This is the same outcome the earlier CR-3 fixed for failed saves, reached by a different path.
    - Suggested fix: Do not offer an action that will be ignored. For example, hide or disable "Open"/"Save" and the row click on every row while `state.downloading != null`, or make the route skip `pickSaveTarget.launch` while a download runs. Also (or instead), when `save` is rejected by single-flight, call `discard` and report it. Update the view model test to expect the new behaviour.

## P3 Low

- [ ] **CR-2 | View model coroutines keep the Activity alive for the length of a download or upload**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt:141`, `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt:132`, `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt:123`
    - Category: Quality
    - Problem: The lambdas passed into the view model capture the Activity: the `open` target factory holds `context` (LocalContext, the Activity), and the `save` target/discard and the `uploadPicked` describe lambda hold `resolver` (`context.contentResolver`, whose ContextImpl refers back to the Activity). `PickedUpload` keeps that resolver until its upload ends. These lambdas live in `viewModelScope` coroutines that outlive configuration changes, and the fix made this deliberate: the discard now also runs after the view model is cleared.
    - Why it matters: After a rotation during a large download or upload, the destroyed Activity and its view tree cannot be garbage-collected until the transfer ends. LeakCanary or StrictMode would flag it.
    - Suggested fix: Capture `context.applicationContext` and `context.applicationContext.contentResolver` in the route before building these lambdas. The FileProvider URI, the cache dir and document deletion all work with the application context.

## Review conclusion

Material issues found; fix before completing the task. The earlier CR-1, CR-2, CR-3 and CR-5 are correctly fixed and tested. CR-4 is partly fixed, and the rest is covered by a recorded assumption. Both acceptance criteria are implemented in substance: browse, open via FileProvider, save, and streaming upload, with browse and upload states covered by view-model, Compose and Robolectric route tests. The new CR-1 is a correctness gap in the save flow that should be closed (or recorded as an assumption by the parent). CR-2 is a minor cleanup.
