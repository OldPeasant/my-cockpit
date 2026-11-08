# Code Review

Date: 2026-11-08

Task: and-files-preview - Android files: in-app preview

Scope: `git diff b3359b2e9..63d0999f4 -- android`. This covers the task's commits 340c6c113..39d2acf2e, merged in by 16ff6931d, and the fix commit 63d0999f4. It is a re-review of revision 63d0999f4 after the fixer's report (reviews/and-files-preview/fixes.md). Uncommitted working-tree changes are not part of this review: a test-writer's in-progress edit to FilePreviewScreenTest.kt and an untracked tests.md.

Requirements:
- tasks/and-files-preview.md: the acceptance criteria and the three ASSUMPTION notes (tap previews every type, image types JPEG/PNG/WebP/GIF via /preview, thumbnails at size=256, memory-only Coil loader, PDF cached whole in cacheDir/files-preview, rendered at 2x width capped at 2048 px, text from the server's 256 KB preview)
- SPEC.md §8.2 (in-app preview, PDF via Android `PdfRenderer`)
- AGENTS.md project notes (no secrets or contents in logs, no lint suppressions, EN and DE strings, feature folder)
- Parent instructions (resource handling, Compose state, coroutine cancellation, verification of CR-1 to CR-3)

Git range:
- Branch: main
- HEAD: 63d0999f4d0a44a55a964eaa1953cb7fe70c47da
- Base/start: b3359b2e9133ab1cabc75989e6502e2212856de0
- Commits reviewed: 8 (340c6c113, 2c2f1d1c0, 723be35f0, a6d362b12, 4f1e44be2, a60d93e81, 39d2acf2e, 63d0999f4), plus the merge commit 16ff6931d

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## Status of the previous findings

- CR-1 (a cancelled PDF download kept running, and its late cleanup deleted a newer load's file): **fixed.**
    - `copy` in FilesRepository.kt:279-298 is now `suspend` and calls `ensureActive()` before each 64 KB buffer.
    - `PreviewFiles.target` (DeviceFiles.kt:92-100) is `@Synchronized`, deletes earlier loads' files, and returns a unique `{id}-{n}.pdf` per load.
    - `loadPdf` (FilesViewModel.kt:561-576) calls `ensureActive()` after the download, and its `finally` deletes only its own `cache`.
    - Tests cover each part: FilesRepositoryTest "a cancelled download stops copying at the next buffer", PreviewFilesTest "every load of the same PDF gets a file of its own...", and FilePreviewViewModelTest "opening the same PDF again while its first download still runs keeps the new file".
    - Side effect: the existing open and save downloads also stop on cancellation now, and their `finally` discards the partial file. This is an improvement.
- CR-2 (Save and "Open with" stayed enabled in the preview while another file downloaded): **fixed.**
    - FilesScreen.kt passes `busy = state.downloading != null`.
    - PreviewTopBar disables Save and "Open with" while `busy`, and `OpenWithButton` takes `busy` in both the unsupported card and the PDF-failed card.
    - FilePreviewScreenTest covers this: "while another file downloads, Save and Open with are disabled...", plus the unsupported-type and PDF-failed variants.
- CR-3 (formatter-wrapped status checks): **fixed.** `loadText` uses `when (result.status)`. The Problem branch in FilePreviewView computes `offline` and `text` locals.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A PDF opened while the preview is leaving is never closed (PdfRenderer and file descriptor leak)**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/files/FilePreviewView.kt:374`
    - Category: Correctness
    - Problem: `PdfPreview` opens the PDF with `val pages = withContext(NonCancellable + Dispatchers.IO) { opener(pdf) }`, then closes `pages` in a `finally` that only begins after this line. The comment says the PDF is "opened even when the preview moves on meanwhile, so it is always closed again", but that does not hold. `NonCancellable` only keeps the block itself from being cancelled. The switch from the composition's Main dispatcher to IO means the result is sent back to the caller with the prompt-cancellation guarantee of `withContext`: if the `produceState` coroutine was cancelled while `opener` ran (the user stepped to the next file, closed the preview or pressed back, or the screen was disposed), `withContext` throws `CancellationException` and drops the returned `PdfPages`. The `try`/`finally` is never entered, so the `PdfRenderer` and its `ParcelFileDescriptor` stay open. Only the finalizers release them later, with CloseGuard warnings. The existing tests ("the open PDF is closed when the preview moves on...") use a fake opener that returns before cancellation, so they do not cover this window.
    - Why it matters: Paging quickly through a folder of PDFs is a normal action. Each lost renderer holds a native PDF document and an fd on a cache file that `PreviewFiles.target` has already unlinked. This is the resource-safety property the code explicitly claims.
    - Suggested fix: Assign the result inside the non-cancellable block, so the `finally` always sees it. For example: `var pages: PdfPages? = null; try { withContext(NonCancellable + Dispatchers.IO) { pages = opener(pdf) }; ... } finally { pages?.close() }`. Alternatively, close the result in the block's own cancellation path. Add a test whose opener suspends or blocks until the preview has moved on, then assert that the fake was closed.

## P3 Low

- [ ] **CR-2 | Closing the PDF can block the main thread while a page renders**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/files/FilePreviewView.kt:379`
    - Category: Quality
    - Problem: `RendererPdfPages.render` and `close` are both `@Synchronized` (PdfPages.kt), and `render` runs on `Dispatchers.IO` for a bitmap up to 2048 x 8192 px. The `finally { pages?.close() }` in `PdfPreview` runs in the `produceState` coroutine on the main dispatcher. If the user steps away or closes the preview during a render, the UI thread waits on the lock until that render finishes.
    - Why it matters: This causes a visible stall (jank) on next/close for large pages. The serialization itself is correct and needed.
    - Suggested fix: Close on IO, for example `withContext(NonCancellable + Dispatchers.IO) { pages?.close() }` in the `finally`.

## Review conclusion

Material issues found; fix before completing the task.

Notes on what was checked and found sound:
- Acceptance criterion 1 is implemented:
    - Images get pinch zoom, double-tap zoom and clamped panning.
    - PDFs render through `PdfRenderer` with page buttons. The fd is closed if the constructor fails, each page is closed in `finally`, bitmaps are capped and drawn on white paper, and a PDF with no pages or that cannot be opened offers "Open with".
    - Text shows the server's preview in a monospace face with a notice when it is cut. A 415 falls back to "Open with".
    - Other types show the "Open with" card.
- Acceptance criterion 2 is implemented:
    - Previous and next walk the folder's files in list order.
    - Only the file still shown takes a loaded result, and a late result after close is dropped.
    - Back closes the preview first.
    - Image rows load `/thumbnail?size=256` through the authenticated, memory-only `FileImages` loader over the type tile.
- Acceptance criterion 3: all 13 new strings exist in values/ and values-de/. `files_open` changed to "Open with…" / "Öffnen mit…" in both.
- Nothing is logged, and no `@Suppress`, `ktlint-disable` or `@Ignore` was added.
- Existing FilesRouteInAppTest cases were adapted to open files through the row menu (a tap now previews). Their assertions were not weakened.
- Acceptance criterion 4: the parent reports `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*'` on 63d0999f4 as passing. `lintDebug` and `sh scripts/check.sh` still need to be run and recorded by the parent.
