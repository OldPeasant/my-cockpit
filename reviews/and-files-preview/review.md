# Code Review

Date: 2026-11-08

Task: and-files-preview - Android files: in-app preview

Scope: Android production changes of the task (base 5c5eab7cf3afdee9093d65710575bdd4c17162b7 to HEAD), focused on `android/app/src/main/java/ch/sonensei/cockpit/files` and `res/values{,-de}/strings_files.xml`; tests in `android/app/src/test/java/ch/sonensei/cockpit/files` read for context only (a test-writer was committing concurrently).

Requirements:
- tasks/and-files-preview.md (acceptance criteria and the three recorded assumptions)
- SPEC.md §8.2 (as summarized by the task: in-app preview, PDF via Android `PdfRenderer`)
- AGENTS.md project notes (no secrets in logs, lint not suppressed, features in their own folder)
- Parent agent instructions (resource handling, Compose state, auth on thumbnail/preview requests)

Git range:
- Branch: main
- HEAD: 20a97465e5cd5f351c05e264dfd4cfbb28ffea7d
- Base/start: 5c5eab7cf3afdee9093d65710575bdd4c17162b7
- Commits reviewed: the task's commits on main after the base (including a661d8002, 74734512d, 7c887ff07, 1721b5f23, 20a97465e and the earlier 85da95bf3, 7badf6f17, b3c42aeb6, 52ff47c6a named in the task notes). Shell access was denied in this review session, so the exact commit count and `git diff` could not be produced. The changed files were read in full at HEAD instead: FilesScreen.kt, FilePreviewView.kt, FilesViewModel.kt, PdfPages.kt, FileImages.kt, FilesModels.kt, DeviceFiles.kt, FilesRepository.kt, both strings_files.xml, and FilePreviewScreenTest.kt. NetworkModule.kt was read to check auth.

Summary: 0 P0, 0 P1, 2 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A cancelled PDF preview download keeps running and then deletes the cache file a newer preview of the same PDF is using**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt:561`
    - Category: Correctness
    - Problem: `loadPdf` runs in `previewJob`. `closePreview`/`previewStep`/`showPreview` cancel that job, but the actual transfer is the blocking `copy` loop inside `withContext(Dispatchers.IO)` in `FilesRepository.download` (`FilesRepository.kt:137-155`, `274-292`). That loop never checks for cancellation, and Retrofit only cancels the call while it waits for the response headers. So the download goes on to the end of the file. Only then does `withContext` throw `CancellationException`, and the `finally` at `FilesViewModel.kt:570-573` deletes `cache`. `PreviewFiles.target` (`DeviceFiles.kt:88-95`) always returns the same path `files-preview/<id>.pdf` for a file and does not delete the file it returns. Example: the user opens a large PDF, closes it (or steps to the next file and back), then opens the same PDF again. The second load opens a new stream to the same path. When the first, cancelled download finishes, it deletes that path. The second download then completes into an unlinked file. The preview becomes `PreviewContent.Pdf(file)` for a file that no longer exists, and `openPdf` fails with `FileNotFoundException`, so the user sees "This PDF cannot be shown here", which has no retry. On top of that, every cancelled preview keeps downloading the whole PDF in the background, which wastes mobile data.
    - Why it matters: Closing a slow preview and reopening it, or quickly going back and forth between files, are normal actions. Both can make a valid PDF fail to show, and they use bandwidth for transfers nobody wants anymore.
    - Suggested fix: Make the copy cancellable. For example, check `coroutineContext.ensureActive()` in the loop, or run it with `runInterruptible` / close the response body on cancellation, so a cancelled preview stops transferring. Also give each load its own cache file (for example `<id>-<n>.pdf`, or delete only the exact `File` object that this load created), so cleanup from a stale load can never remove the file of a newer one. Add a view-model test that reopens the same PDF while the first download is still running.

- [ ] **CR-2 | In the preview, Save and "Open with" stay enabled while a different file is downloading, and are then ignored without any message**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt:414`
    - Category: Correctness
    - Problem: `FilesContent` passes `downloading = state.downloading == it.file.id` to `FilePreviewView`. `PreviewTopBar` (`FilePreviewView.kt:200-209`) and `OpenWithButton` (`FilePreviewView.kt:257`) only disable or hide their actions for that file's own download. If the user starts "Open with" or "Save" on file A in the preview and then steps to file B, B's Save and "Open with" buttons are enabled. Tapping Save opens the system "save as" picker. After the user picks a place, `FilesViewModel.download` sees the running job and calls `discard()`, so the document just created is deleted and nothing is saved or shown. "Open with" is dropped in the same silent way. The list itself avoids this on purpose: `FileRow` disables every row while any download runs (`FilesScreen.kt:744-753`, "the 'save as' picker would create a document").
    - Why it matters: The user picks a save location and gets no file and no feedback. This breaks the one-download-at-a-time rule that the list already enforces.
    - Suggested fix: Pass the "a download is running" state to the preview as well (for example `busy = state.downloading != null`). Disable Save and "Open with" (top bar and card) while any download runs, and keep the spinner for the file that is downloading.

## P3 Low

- [ ] **CR-3 | Status checks wrapped across lines by the formatter are hard to read**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt:551`
    - Category: Quality
    - Problem: `if (result.status ==\n UNSUPPORTED\n) { ... } else { ... }` in `loadText` (lines 551-557), and the same shape for `content.message == FilesMessage.OFFLINE` in `FilePreviewView.kt:151-157`, are formatter output from overly long expressions. They hide very simple decisions.
    - Why it matters: Small readability cost in code that is likely to change, since it maps server statuses to UI states.
    - Suggested fix: Use a `when (result.status) { UNSUPPORTED -> ...; else -> ... }`, or pull the string resource into a local `val` before the call, so ktlint keeps each expression on one line.

## Review conclusion

Material issues found; fix before completing the task.

Notes on what was checked and found sound: thumbnail and preview images load through `FileImages`, which uses the app's authenticated `OkHttpClient` (server URL interceptor, access token, token authenticator) with no disk cache. `PdfPages`/`RendererPdfPages` closes the `ParcelFileDescriptor` when the renderer constructor fails, serializes `render` and `close`, and always closes the open page. `PdfPreview` opens the PDF outside cancellation and closes it when it leaves composition. Stale page renders cannot overwrite newer ones, because cancellation is checked when `withContext` returns. The PDF bitmap width is capped (2048 px wide, 8192 px high). Only the current file takes a view-model preview result. Image failure state is keyed per file, and retry recomposes `AsyncImage`. Back closes the preview first. The view model holds only the application context. Nothing is logged and no lint suppressions were added. All 14 new strings exist in English and German. Acceptance criteria 1-3 are implemented. Criterion 4 (test and lint runs) could not be checked in this session because shell access was denied. The parent must record that evidence.
