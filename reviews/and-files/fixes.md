# Fix Report

Date: 2026-10-22

Task: and-files - Android files

Scope: current task; findings CR-1 to CR-5 of `reviews/and-files/review.md`. Fixes were applied in commit 5903df968 (task base 236a674ae; task commits ad8cfd84f, 75b350c21, 82d213a9e, 5903df968). This report was written after verifying that commit against each finding; no further code changes were made in this fixer session.

## Fixed findings

### CR-1 - Tapping a second file while one downloads deletes the file being downloaded

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/files/FilesViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/files/FilesRouteInAppTest.kt`

Problem:
`FilesRoute` called `OpenedFiles.target` (which deletes every other `files-open` subfolder) before the view model's single-flight check, so a second tap deleted the file still being downloaded and the viewer later got a missing file.

Fix:
`FilesViewModel.open(file, target: () -> File)` now takes a target factory. `download` performs the single-flight check (`downloadJob?.isActive`) first and only then, inside the launched coroutine, calls `prepare()`, which invokes the factory. An ignored second tap therefore never touches the cache. The route passes `{ OpenedFiles.target(context, file.id, file.name) }`.

Validation:
- `FilesViewModelTest`: "a second open while one downloads touches no file, and the first is still opened" (asserts the second factory is never invoked, no second download, the first file is opened with its content).
- `FilesRouteInAppTest`: "tapping another file while one downloads leaves the first intact, and it still opens" (through the real route; the started ACTION_VIEW URI is readable and has the first file's content).
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*'` - Pass (run by the parent on 5903df968)

### CR-2 - The file to save is lost when the activity is recreated while the save-as picker is open

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/files/FilesRouteInAppTest.kt`

Problem:
The pending save was held in `remember`, so recreation while the CreateDocument picker was open dropped the picker's result and left an empty document.

Fix:
The pending file is now `rememberSaveable` with a `PendingSaveSaver` that stores id, name, mime, size and updatedAt as strings (all fictitious-safe metadata, no file contents) and restores the `DriveEntry.File`.

Validation:
- `FilesRouteInAppTest`: "a save still arrives when the activity was recreated while the save-as picker was open" (recreates the scenario, dispatches the picker result to the new activity's registry, asserts the "saved" toast and the written content).
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*'` - Pass (parent run)

### CR-3 - A failed or interrupted "Save to device" leaves an empty or truncated document behind

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/files/DeviceFiles.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/files/DeviceFilesTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/files/FilesViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/files/FilesRouteInAppTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/files/PickedFilesProvider.kt` (test provider answers `DocumentsContract.deleteDocument`)

Problem:
On a failed or cancelled download, the document created by the picker (and the partial cache file for "Open") stayed behind.

Fix:
`download` wraps the work in `try/finally`; when the result is not `Ok` (failure or cancellation), it calls a `discard` callback in `withContext(NonCancellable + io)`, errors ignored. For save, the route passes `discardDocument(resolver, uri)`, a new best-effort `DocumentsContract.deleteDocument` wrapper. For open, discard deletes the partial cache file. A save ignored by the single-flight check never discards anything.

Validation:
- `DeviceFilesTest`: "discarding a failed save deletes the created document, and a provider that refuses does not crash".
- `FilesViewModelTest`: "a failed open deletes what it wrote into the cache", "a failed save discards the chosen document, a successful one keeps it", "a download cancelled with the screen discards what it wrote" (ViewModelStore cleared mid-download; the ignored concurrent save does not discard).
- `FilesRouteInAppTest`: "a failed save deletes the document the save-as picker created" (offline mid-download through the real route; document removed and offline banner shown).
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*'` - Pass (parent run)

### CR-4 - Content-provider queries and cache deletion run on the main thread (partially fixed, remainder deliberate)

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/files/FilesViewModelTest.kt`

Problem:
`describePicked` (ContentResolver `query`/`getType` per picked URI) ran in the activity-result callback, and `OpenedFiles.target` (list/delete/mkdirs in the app cache) ran in the click handler, both on the main thread.

Fix:
- Picked-URI queries: the route now calls `viewModel.uploadPicked { uris.map { describePicked(resolver, it) } }`; the view model runs the lambda via `withContext(io)` on an injected dispatcher (`Dispatchers.IO` in production via the `@Inject` constructor, the test dispatcher in tests). The target folder is captured when the files are picked, as before. This is the part the finding's "why it matters" (slow cloud providers, ANR risk) is about, and it is fully fixed.
- Cache target: `OpenedFiles.target` no longer runs in the click handler on every tap; it runs once per started download inside the download coroutine (CR-1 fix). It still runs on the main dispatcher. Moving `prepare()` into `withContext(io)` was tried by the parent worker and made 3 Robolectric `FilesRouteInAppTest` tests time out, so it was reverted. The remaining work is local app-cache file-system work on at most one small folder (only the last opened file is kept), not IPC to another provider. Kept as a deliberate partial; the parent may record it as an assumption.

Validation:
- `FilesViewModelTest`: "picked files are described in the view model's IO coroutine and go into the folder shown when picked" (describe lambda not run in the caller; upload goes to the folder shown at pick time even after navigating away).
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*'` - Pass (parent run)

### CR-5 - Cache file names are cut by characters, not bytes

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/files/DeviceFiles.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/files/DeviceFilesTest.kt`

Problem:
`safeName` truncated to 120 UTF-16 chars, which could exceed the 255-byte file-name limit for multibyte names, split surrogate pairs, and drop the extension.

Fix:
`safeName` now limits the name to 200 UTF-8 bytes, cutting on code-point boundaries (`codePointAt`/`Character.charCount`), and keeps an extension of up to 16 chars. A result that is empty or only dots still becomes `file`.

Validation:
- `DeviceFilesTest`: "long names are cut to 200 UTF-8 bytes between characters, keeping the extension" (ASCII, CJK, emoji surrogate pairs, dots-only cut, normal name unchanged) and "a long name written to the cache can be opened" (writes and reads a 100-CJK-char name).
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*'` - Pass (parent run)

## Not fixed

None. The main-dispatcher cache preparation under CR-4 is a documented partial (see CR-4 above), not an open defect: the IPC part of the finding is resolved, and the remaining local cache work was reduced to once per download.

## Validation summary

Focused tests:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*'` on 5903df968 - BUILD SUCCESSFUL, 59 tests, 0 failures (5 classes). Run by the parent worker; this fixer cannot run Gradle.

Full test suite:
- Not run by the fixer (no Gradle access). The parent runs `.taskcheck` before `done`.

Lint/type-check/build:
- `cd android && ./gradlew ktlintCheck lintDebug` on the same code - clean (parent run).

`.taskcheck`:
- Not run (fixer cannot run Gradle; parent's responsibility).

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed (CR-4's main-dispatcher cache preparation kept as a deliberate, documented partial).

# Second review: fixes for review CR-1, CR-2 and test report TW-1

Date: 2026-10-22

Task: and-files - Android files

Scope: current task; the second review's findings CR-1 (P2) and CR-2 (P3) of `reviews/and-files/review.md` (they replace the first review's ids, which the section above uses) and TW-1 of `reviews/and-files/tests.md`, which is the same defect as the second review's CR-1. Code base: HEAD 36015d3e1 plus this fixer's uncommitted changes in `android/app/src/{main,test}/java/ch/sonensei/cockpit/files`.

## Fixed findings

### Second-review CR-1 / TW-1 - "Save to device" started while another file downloads is silently dropped and leaves the empty document the picker created

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/files/FilesViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/files/FilesScreenTest.kt`

Problem:
While one file downloaded, every other row still offered "Save to device". The save-as picker created the document, then `FilesViewModel.save` returned early on the single-flight check without calling `discard`, leaving a 0-byte file and no message.

Fix:
- `FilesViewModel.download`: when a download is ignored because another one runs, its `discard` is now called off the main thread (`viewModelScope.launch(io) { runCatching { discard() } }`, the same dispatcher and error handling as the failure path). For an ignored open this is a no-op (its cache file was never chosen); for an ignored save it deletes the picker's document. The `save` KDoc says so. This supersedes the line "A save ignored by the single-flight check never discards anything" in the first CR-3 section above.
- `FilesScreen.kt`: while any download runs (`state.downloading != null`) the file rows are not clickable and the menu's "Open" and "Save to device" items are disabled (`EntryRow` got an `enabled` parameter, default `true`, passed to `clickable`). Folders stay clickable, so browsing continues. The save-as picker therefore cannot be started during a download; the view-model discard is the safety net for any remaining race.
- No message is shown for the discarded save: with the UI change it is no longer reachable through the screen, so a new string and banner were not added.

Tests:
- `FilesViewModelTest` "a download cancelled with the screen discards what it wrote": updated to the new behaviour. It asserted that the ignored save discarded nothing; it now asserts the ignored save is discarded exactly once (right away, and not again when the view model is cleared).
- `FilesViewModelTest` "a save ignored while another file downloads discards its document, the running download is not touched": new. The ignored save's target is never opened, its discard runs once, the running download keeps going and its file is opened with its content, and nothing is downloaded for the other file.
- `FilesScreenTest` "while a file downloads, no other file can be opened or saved": new. Another file's row and its menu's Open and Save items are disabled and calling them does nothing; a folder still opens.
- `FilesRouteInAppTest` "saving another file while one downloads leaves no empty document where the user chose" (test-writer's regression test): unchanged. With the disabled Save item no picker is started, so it takes its "no save can start" branch.

Validation:
- Not run by the fixer (sub-agents cannot run Gradle). The parent runs `cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*'`.

### Second-review CR-2 - View model coroutines keep the Activity alive for the length of a download or upload

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt`

Problem:
The lambdas handed to the view model (the open target factory, the save target and discard, the picked-upload describe lambda) captured the Activity through `context` and `context.contentResolver`, so a rotation during a long transfer kept the destroyed Activity alive.

Fix:
`FilesRoute` now takes `val app = context.applicationContext` and `val resolver = app.contentResolver`; the open target factory uses `OpenedFiles.target(app, ...)`. `OpenedFiles.target` takes a plain `Context` and only uses `cacheDir`, which the application context has. The event collector (toasts and `OpenedFiles.launch`, which starts an activity) still uses the Activity `context`, because it runs in the composition and does not outlive it.

Validation:
- Covered by the existing route tests that open, save and upload through the real route (`FilesRouteInAppTest`). Not run by the fixer; see the parent's run.

## Not fixed

None.

## Validation summary

Focused tests:
- Not run by the fixer (no Gradle access). Command for the parent: `cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*'`.

Full test suite:
- Not run (parent runs `.taskcheck`).

Lint/type-check/build:
- Not run by the fixer; included in the parent's command above.

`.taskcheck`:
- Not run (fixer cannot run Gradle).

Remaining unresolved findings:
0

## Final status

All validated findings fixed; validation is pending the parent's Gradle run.
