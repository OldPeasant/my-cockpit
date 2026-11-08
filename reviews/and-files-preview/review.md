# Code Review

Date: 2026-11-08

Task: and-files-preview - Android files: in-app preview

Scope: `git diff b3359b2e9..b521e71c9 -- android`. This is the full task scope, from the task's commits 340c6c113..39d2acf2e (merged in by 16ff6931d) through the fix commits 63d0999f4, 6459fb674 and b521e71c9. This is a re-review of the final revision b521e71c9. It looks closely at the round-2 fixes, `git diff 63d0999f4..b521e71c9`, which changed FilePreviewView.kt, PdfPages.kt and FilePreviewScreenTest.kt. The working tree was clean.

Requirements:
- tasks/and-files-preview.md: the four acceptance criteria and the ASSUMPTION notes:
    - a tap previews every file type;
    - images (JPEG/PNG/WebP/GIF) load through /preview;
    - thumbnails use size=256, with a memory-only Coil loader;
    - a PDF is cached whole in cacheDir/files-preview and rendered at 2x the width, capped at 2048 px;
    - text comes from the server's 256 KB preview.
- SPEC.md §8.2 (in-app preview, PDF via Android `PdfRenderer`)
- AGENTS.md project notes:
    - no secrets or contents in logs;
    - no lint suppressions;
    - EN and DE strings;
    - each feature in its own folder, inside the task's `touches`.
- reviews/and-files-preview/fixes.md "Round 2" and the previous review of 63d0999f4
- Parent instructions: verify the previous CR-1 and CR-2, check that the NonCancellable + IO close is correct and the tests are deterministic, check the KTX createBitmap change, and re-check the whole task.

Git range:
- Branch: main
- HEAD: b521e71c9
- Base/start: b3359b2e9
- Commits reviewed: 10:
    - task commits: 340c6c113, 2c2f1d1c0, 723be35f0, a6d362b12, 4f1e44be2, a60d93e81, 39d2acf2e;
    - fix and test commits: 63d0999f4, 6459fb674, b521e71c9;
    - plus the merge commit 16ff6931d.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of the previous findings

- Previous CR-1 (P2): a PDF that finished opening after the preview had moved on was never closed. **Fixed.**
    - FilePreviewView.kt:374-383 declares `var pages: PdfPages? = null` before the `try`. It assigns the result inside `withContext(NonCancellable + Dispatchers.IO) { pages = opener(pdf) }`.
    - The assignment runs inside the block that cannot be cancelled. Suppose the `produceState` coroutine is cancelled while the opener runs. Then `withContext` still throws `CancellationException` on its way back (prompt cancellation). But `pages` is already set, and the `finally` closes it.
    - The result is written on an IO thread and read back on the main thread. This is safe: resuming through the dispatcher orders the write before the read.
    - If `opener` returns null, or throws (`openPdf` catches IOException and SecurityException), `pages` stays null, and nothing is left to close.
    - The regression test "a PDF still opening when the preview moves on is closed once it is open" (FilePreviewScreenTest.kt:464-482) is deterministic:
        - the opener blocks on a latch, so cancellation always happens before the opener returns;
        - `assertTextEquals` on the next file's text waits until recomposition has removed `PdfPreview`, which cancels the coroutine;
        - only after that is the latch released.
    - The fixer reports that this test fails with the old line restored.
- Previous CR-2 (P3): the PDF was closed on the main thread. **Fixed.**
    - The `finally` closes with `withContext(NonCancellable + Dispatchers.IO) { pages?.close() }`. `NonCancellable` is needed: without it, `withContext` in an already cancelled coroutine would throw before the close runs. With it, the close is guaranteed.
    - `RendererPdfPages.close` is `@Synchronized` and idempotent. So when it waits for a page that is still rendering, it now blocks an IO thread, not the UI thread.
    - The tests now close asynchronously and use `assertClosed` (FilePreviewScreenTest.kt:117-121):
        - `waitForIdle()` lets the composition leave;
        - a bounded `waitUntil` (5 s) waits for the close;
        - then an unconditional `assertTrue` checks it.
    - The assertion is not weakened: a PDF that is never closed still fails the test. `FakePdf.closed` is `@Volatile`, so the IO-thread write is visible to the test thread.
- lintDebug UseKtx fix (PdfPages.kt:62): `createBitmap(w, h)` from `androidx.core.graphics` defaults to `Bitmap.Config.ARGB_8888`, so behavior is unchanged. The parent reports lintDebug as clean on b521e71c9.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.

Notes on what was checked and found sound on b521e71c9:
- Acceptance criterion 1:
    - Images: pinch zoom (1x to 5x), double-tap zoom and clamped panning. A failed image offers a retry that loads it again.
    - PDFs: rendered through `PdfRenderer`, with buttons for the previous and next page.
        - The fd is closed if the constructor fails.
        - Each page is closed in `finally`.
        - Bitmaps are capped and drawn on white paper.
        - The open PDF is closed on every way out, including during opening (see above).
        - A PDF that cannot be opened, or has no pages, offers "Open with".
    - Text: shown monospaced, with a notice when the server cut it. A 415 falls back to "Open with".
    - Other types show the "Open with" card.
- Acceptance criterion 2:
    - Previous and next walk the folder's files in list order.
    - Only the file still shown takes a loaded result, and a late result after close is dropped.
    - A cancelled PDF download stops at the next buffer, and each load gets its own cache file.
    - Image rows show `/thumbnail?size=256` from the authenticated, memory-only `FileImages` loader, over the type tile.
- Acceptance criterion 3: every new preview string exists in values/ and values-de/. FilePreviewScreenTest "every new preview string has its own German translation" checks this.
- Acceptance criterion 4: the parent reports that `./gradlew ktlintCheck lintDebug createDebugUnitTestCoverageReport` on b521e71c9 ran 3860 unit tests with 0 failures, and lintDebug was clean. `sh scripts/check.sh` still has to be run and recorded by the parent.
- Conventions:
    - Nothing is logged.
    - No `@Suppress`, `ktlint-disable` or `@Ignore` was added.
    - Every change stays inside the task's `touches` (files feature folders and strings_files.xml).
    - Test data is fictitious.
    - Existing FilesRouteInAppTest cases now open files through the row menu, without weaker assertions.
- Coverage: PdfPages.kt has low line coverage (24%) because the real `PdfRenderer` cannot run under Robolectric. Its pure size logic (`pageSize`) is unit-tested, and the renderer path is behind the `PdfPages` interface that the screen tests fake. This is an accepted limit, not a finding.
