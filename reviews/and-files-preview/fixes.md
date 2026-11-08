# Fix Report

Date: 2026-11-08

Task: and-files-preview - Android files: in-app preview

Scope: Findings CR-1, CR-2 and CR-3 of `reviews/and-files-preview/review.md`, limited to `android/app/src/main/java/ch/sonensei/cockpit/files` and `android/app/src/test/java/ch/sonensei/cockpit/files`.

## Fixed findings

### CR-1 — A cancelled PDF preview download keeps running and then deletes the cache file a newer preview of the same PDF is using

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesRepository.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/files/DeviceFiles.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/files/FilesRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/files/PreviewFilesTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/files/FilePreviewViewModelTest.kt`

Problem:
The blocking copy loop in `FilesRepository.download` never checked for cancellation, so a closed preview kept downloading the whole PDF. `PreviewFiles.target` returned the same path for every load of a file, so the late `finally` of a cancelled load deleted the file that a newer load of the same PDF had written and was showing.

Fix:
- `copy` is now a `suspend` function and calls `currentCoroutineContext().ensureActive()` before each buffer, so a cancelled caller stops the transfer at the next buffer. `download` documents this.
- `PreviewFiles.target` (now `@Synchronized`) deletes every file of earlier loads in `files-preview` and returns a new name per load (`{id}-{n}.pdf`, `n` from an `AtomicLong`). A stale load's cleanup deletes only its own `File`, which can never be the newer load's file. "Only the latest load is kept" stays as before.
- `FilesViewModel.loadPdf` checks `ensureActive()` after the download returns. A load cancelled meanwhile ends there, whatever the download returned. It never writes the preview state, and its `finally` deletes its own file. The cleanup comment says that only this load's own file is deleted.

Tests:
- `FilesRepositoryTest`: "a cancelled download stops copying at the next buffer". A 4 MB body, and the first write cancels the caller. Expected: the job is cancelled, exactly one buffer is written, and less than the body is copied.
- `PreviewFilesTest`: the name test now checks the folder and the `{id}-...pdf` shape. "previewing the same PDF again keeps its cache file" contradicted the required fix, so it was replaced by "every load of the same PDF gets a file of its own...". Each load gets a distinct file, the earlier one is deleted, and deleting the earlier one leaves the newer one intact.
- `FilePreviewViewModelTest`: "opening the same PDF again while its first download still runs keeps the new file". The first download ignores cancellation until a gate opens. The test closes the preview, reopens it, lets the second load finish, then opens the gate. Expected: the preview still shows the second file with its content, and the stale file is gone.

Validation:
- Not run by the fixer (sub-agents cannot run Gradle here). The parent runs `cd android && ./gradlew ktlintCheck testDebugUnitTest`.

### CR-2 — In the preview, Save and "Open with" stay enabled while a different file is downloading

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilePreviewView.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/files/FilePreviewScreenTest.kt`

Problem:
The preview got only "this file is downloading". Save and "Open with" stayed enabled while another file downloaded, and the view model then silently dropped the action, which deleted the document the "save as" picker had just created.

Fix:
`FilesContent` now also passes `busy = state.downloading != null` to `FilePreviewView`. The top bar keeps the spinner when this file downloads (`downloading`). Otherwise Save and "Open with" are shown with `enabled = !busy`. `OpenWithButton` takes `busy`, used by the unsupported-type card and the "PDF cannot be shown" card. `PdfPreview` now passes the real state instead of a hard-coded `false`.

Tests:
- `FilePreviewScreenTest`: "while another file downloads, Save and Open with are disabled, and only that file shows the spinner". A text preview with the PDF downloading has no spinner, and Save and "Open with" are disabled. The unsupported card's "Open with" is disabled. Clicks record no callbacks. Everything is enabled again once the download ends. The existing tests for the file's own download, which show the spinner, are unchanged.

Validation:
- Not run by the fixer. The parent runs `./gradlew ktlintCheck testDebugUnitTest`.

### CR-3 — Status checks wrapped across lines by the formatter are hard to read

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilePreviewView.kt`

Problem:
`if (result.status ==\n UNSUPPORTED\n)` in `loadText` and `if (content.message ==\n FilesMessage.OFFLINE\n)` in `FilePreviewView` were formatter-wrapped.

Fix:
`loadText` uses `when (result.status) { UNSUPPORTED -> ...; else -> ... }`. The Problem branch of `FilePreviewView` computes `offline` and the string resource in local vals, then calls `Notice` on one line. Behaviour is unchanged, and the existing 415 and offline/failed tests cover it.

Validation:
- Not run by the fixer. The parent runs `./gradlew ktlintCheck testDebugUnitTest`.

## Not fixed

None.

## Validation summary

Focused tests:
- Not run by the fixer: Gradle is not available to sub-agents in this environment. To run: `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*'`.

Full test suite:
- Not run by the fixer. The parent runs `cd android && ./gradlew ktlintCheck testDebugUnitTest`.

Lint/type-check/build:
- Not run by the fixer. Manual check: no changed line is longer than 140 characters, imports are in lexicographic order, and no suppressions were added.

`.taskcheck`:
- Not run (left to the parent worker).

Remaining unresolved findings:
0

## Final status

All validated findings fixed. The fixer could not run validation, so the parent must run it.
