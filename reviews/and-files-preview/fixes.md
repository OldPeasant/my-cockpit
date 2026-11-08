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

# Round 2 (review of 63d0999f4)

Date: 2026-11-08

Task: and-files-preview - Android files: in-app preview

Scope: the two findings of the re-review of 63d0999f4 (reviews/and-files-preview/review.md), on top of HEAD 6459fb674.

## Fixed findings

### CR-1 — A PDF opened while the preview is leaving is never closed (PdfRenderer and file descriptor leak)

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilePreviewView.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/files/FilePreviewScreenTest.kt`

Problem:
`PdfPreview` took the opened `PdfPages` as the return value of `withContext(NonCancellable + Dispatchers.IO)`. If the `produceState` coroutine was cancelled while the opener ran, `withContext` threw `CancellationException` on its way back and the result was dropped before the `try`/`finally` began, so the renderer and its fd stayed open.

Fix:
`pages` is now a local `var` declared before the `try`, and the opener result is assigned to it inside the non-cancellable block. The `finally` therefore always sees the opened PDF and closes it, also when the cancellation surfaces as the block returns.

Regression test: "a PDF still opening when the preview moves on is closed once it is open". Its opener blocks on a latch, the test steps the preview to the next file and waits until the text shows, then releases the opener and asserts that the returned fake PDF was closed. With the old line restored (result taken from the `withContext` return value), this test fails ("the PDF was left open"). With the fix, it passes.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.files.FilePreviewScreenTest'` with the old open line temporarily restored — Fail, as expected (1 of 33: the new test)
- `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*'` with the fix — Pass

### CR-2 — Closing the PDF can block the main thread while a page renders

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilePreviewView.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/files/FilePreviewScreenTest.kt`

Problem:
`pages?.close()` ran in the `finally` on the composition's main dispatcher, and `RendererPdfPages.close` waits on the same lock as a running `render`.

Fix:
The `finally` now closes with `withContext(NonCancellable + Dispatchers.IO) { pages?.close() }`. `NonCancellable` lets the block run in the already cancelled coroutine, so the close is still guaranteed.

Because the close now runs on IO, the three existing tests that asserted `closed` right after `waitForIdle()` would race. They now use a helper `assertClosed`, which calls `waitForIdle()` (this lets the preview leave and the `finally` start), then waits up to `WAIT_MS` for the close, then asserts with the same message "the PDF was left open". The assertions are not weakened: each test still fails if the PDF is never closed. A trace confirmed that `waitUntil` alone does not let the composition leave, so the `waitForIdle()` is needed.

Validation:
- `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*'` — Pass (FilePreviewScreenTest 33/33, FilesViewModelTest 61, FilesScreenTest 28, FilesRouteInAppTest 24, FilesRepositoryTest 23, FilePreviewViewModelTest 18, DeviceFilesTest 18, FilePreviewModelsTest 12, PreviewFilesTest 5, FilesModelsTest 5, 0 failures)

## Not fixed

None of the supplied findings.

Observed, not a supplied finding: `./gradlew lintDebug` fails with 1 error, `UseKtx` ("Use the KTX function createBitmap instead?"), at `android/app/src/main/java/ch/sonensei/cockpit/files/PdfPages.kt:61`. This line comes from task commit 340c6c113 and was not changed in this round. The fixer did not change it because it was not among the supplied findings. The parent should decide whether to fix it, since acceptance criterion 4 needs lintDebug to pass.

## Validation summary

Focused tests:
- `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*'` — Pass
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.files.FilePreviewScreenTest'` — Pass, 33/33

Full test suite:
- Not run (the full Android unit suite and `sh scripts/check.sh` are left to the parent)

Lint/type-check/build:
- `./gradlew ktlintCheck` — Pass
- `./gradlew lintDebug` — Fail: 1 error (UseKtx, PdfPages.kt:61), not caused by this round's changes

`.taskcheck`:
- Not run

Remaining unresolved findings:
0

## Final status

All validated findings fixed. Focused validation passed. lintDebug still fails on an existing error that was not among the supplied findings.
