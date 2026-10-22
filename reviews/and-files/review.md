# Code Review

Date: 2026-10-22

Task: and-files - Android files

Scope: `git diff 236a674ae..HEAD -- android`, committed changes only. This is the final review after the fixes in ba712b4d2 for the second review's CR-1 and CR-2. The working tree was clean.

Requirements:
- tasks/and-files.md acceptance criteria and recorded assumptions: streaming multipart upload; "Save to device" via ACTION_CREATE_DOCUMENT; "Open" via cache plus a dedicated FileProvider; browse/open/save/upload only; OpenedFiles.target stays on main (the deliberate partial of the first review's CR-4)
- SPEC.md §8.2, §9.2 item 7
- docs/ARCHITECTURE.md, AGENTS.md "Project notes"
- Previous reviews and reviews/and-files/fixes.md (both sections)

Git range:
- Branch: main
- HEAD: ba712b4d2914a827c401cbc03c7695406c7e316a
- Base/start: 236a674ae
- Commits reviewed: 6 task commits touching android/ (7cc9ac06f, 387cc679c, e205d09cf, 47962da18, 83e84d55c, ba712b4d2) plus 6a2a23f58 (report only)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Reviewer note: This sub-agent could not run Gradle. The parent ran this in android/ on HEAD ba712b4d2: `./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*' createDebugUnitTestCoverageReport`. Result: exit 0, 66 tests, 0 failures. `python tasks.py verify and-files` still shows both criteria unrecorded. The parent has to record them before `done`.

## Status of the previous findings

Second review (ids used in the previous review.md):

- CR-1 (a "Save to device" started during another download was silently dropped and left the empty document the picker created): **Fixed.** There are two layers.
    - UI: while `state.downloading != null`, every file row is disabled (FilesScreen.kt:384, 391; `clickable(enabled = ...)` at EntryRow). The menu items "Open" and "Save to device" are also disabled (FilesScreen.kt:410, 419). The save-as picker therefore cannot be launched during a download. Folders stay clickable, so browsing continues. Because `downloading` is set synchronously in `download` (FilesViewModel.kt:176), the row is disabled from the next frame.
    - View model: if a save still arrives during a running download, the save's `discard` runs on `io` with errors ignored (FilesViewModel.kt:172-175), and its target is never opened. For an ignored open, `discard` is `cache?.delete()` on a null `cache`, so it does nothing. That keeps the first review's CR-1 fix intact: an ignored tap never calls `OpenedFiles.target`.
    - Tests: FilesScreenTest "while a file downloads, no other file can be opened or saved" asserts the disabled row and menu items, and that a folder still opens. FilesViewModelTest "a save ignored while another file downloads discards its document..." asserts one discard, an unopened target, no download for the other file, and that the running download still completes and opens. The updated "a download cancelled with the screen..." test asserts the ignored save is discarded exactly once. FilesRouteInAppTest:301 now takes its "no picker started" branch through the real route.
- CR-2 (view model callbacks held the Activity): **Fixed.** FilesRoute now captures `context.applicationContext` and its `contentResolver` (FilesScreen.kt:121-122). It uses them in every lambda handed to the view model: the open target factory (`OpenedFiles.target(app, ...)`, which only uses `cacheDir`), the save target and discard (`openForWriting` / `discardDocument` with the app resolver) and the picked-upload describe lambda. The Activity `context` is used only in the composition-scoped event collector (toasts, `OpenedFiles.launch`). That is correct, because starting an activity needs it and the collector does not outlive the composition.

First review: CR-1, CR-2, CR-3 and CR-5 stay fixed, and the code paths involved did not change in ba712b4d2 beyond what is described above. CR-3's line "a save ignored by single-flight never discards anything" is replaced by the discard above, which is the intended fix. CR-4 remains partly fixed, and the rest is covered by the recorded assumption.

Also re-checked and found sound: the manifest provider is `exported="false"` with `grantUriPermissions="true"` and only `cache/files-open/` is shared. ACTION_VIEW gets a per-URI read grant. `ok` is set before the `Open`/`Saved` event is sent, so a successful download is never discarded. `downloading` is cleared on every completed path. The pending save is `rememberSaveable` with a symmetric String-only Saver. Folder-load races are handled through `loadJob` cancellation. Uploads go to the folder shown at pick time, and provider queries run on `io`. No file names or contents are logged. No new issues were found in the final diff.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. Both acceptance criteria are implemented: browse, open via a FileProvider ACTION_VIEW intent, save to device, and streaming upload from device. Browse and upload states are covered by view-model, Compose screen and Robolectric route tests, and the parent's focused Gradle run passed (66 tests, 0 failures). Before `done`, the parent still has to record the evidence with `tasks.py verify`.
