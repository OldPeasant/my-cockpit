# Test Report

Date: 2026-11-08

Task: and-files-preview - Android files: in-app preview

Scope: `b3359b2e9133ab1cabc75989e6502e2212856de0..b521e71c92e151bc48913b68f364165a30cde45b`, limited to `android/`. That covers the task commits 340c6c113..39d2acf2e, merged via `origin/wip/and-files-preview`, plus three later commits: fix round 1 63d0999f4 (CR-1, CR-2, CR-3), the test-writer test 6459fb674 and fix round 2 b521e71c9. Branch `main`, HEAD b521e71c92e151bc48913b68f364165a30cde45b. The working tree was clean when this report was written, apart from this report.

Requirements:
- `tasks/and-files-preview.md` acceptance criteria and recorded assumptions
- SPEC.md §8.2 (in-app preview, PDF via `PdfRenderer`)
- `reviews/and-files-preview/review.md` and `fixes.md`, round 1: CR-1 cancellable download and a separate cache file for each load, CR-2 busy disables Save and Open with
- `reviews/and-files-preview/fixes.md`, round 2: a PDF that is still opening when the preview moves on gets closed, closing runs on `NonCancellable + Dispatchers.IO`, and `PdfPages` uses KTX `createBitmap`

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/files/FilePreviewScreenTest.kt`

## Tests added or updated

Count: 1 by the test-writer (committed in 6459fb674). This pass added nothing.

- `FilePreviewScreenTest`: `while a file downloads, the Open with of a PDF that cannot be shown is disabled`. The CR-2 fix passes `busy` into the failed notice of `PdfPreview`, and this path had no test.

How round 2 is covered (the test was written by the fixer in b521e71c9; the test-writer reviewed it):
- `a PDF still opening when the preview moves on is closed once it is open`: latches hold the opener until the preview has moved on to a text file. After that the PDF must be closed and no page may be shown. This is the regression test for the round-2 leak: in the earlier code the `try`/`finally` began only after the open had finished.
- The three existing close tests (on step, on close, and the no-pages PDF) now use the `assertClosed` helper. It waits up to `WAIT_MS` for the close, because the close now runs on `Dispatchers.IO`, so those tests also check the off-main-thread close.
- The `createBitmap` change in `PdfPages.kt` (line 62) is inside `RendererPdfPages.render`, which Robolectric cannot run (see "Unable to test"). The behavior did not change: KTX `createBitmap(w, h)` defaults to `ARGB_8888`. `lintDebug` is clean.

Conclusion: round 2 needs no further tests.

Existing task tests reviewed (already present, unchanged in this pass): `FilePreviewModelsTest`, `FilePreviewScreenTest`, `FilePreviewViewModelTest`, `PreviewFilesTest`, `FilesRepositoryTest`, `FilesRouteInAppTest`, `FilesScreenTest`, all in `android/app/src/test/java/ch/sonensei/cockpit/files`. Together they cover every criterion and every fix. No redundant or weakened tests were found.

## Requirement coverage

Command (the parent ran it on b521e71c9, at the final snapshot): `cd android && ./gradlew ktlintCheck lintDebug createDebugUnitTestCoverageReport` -> exit 0, 3860 unit tests, 0 failures, 0 errors, 0 skipped, lintDebug clean. `createDebugUnitTestCoverageReport` runs the whole debug unit test suite, so it includes every test listed below.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Image preview with zoom | FilePreviewScreenTest: image loaded / without loader / failed + retry / failure does not block next / double tap zoom / pinch zoom 1x..5x / next image unzoomed; FilePreviewModelsTest: zoom clamp, pan bounds, image type detection | parent's command above | Pass |
| PDF via PdfRenderer with page navigation | FilePreviewScreenTest: page by page + bar turns pages / single page / closed on step and close / still-opening PDF closed once open / new file starts page 1 / cannot be opened / no pages / double-tap zoom; FilePreviewModelsTest: page size; FilePreviewViewModelTest: download into cache, failure cleanup, unwritable cache, stale loads; PreviewFilesTest: missing file is no PDF | parent's command above | Pass (against a fake `PdfPages`; real `PdfRenderer` rendering was not run, see "Unable to test") |
| Text preview | FilePreviewViewModelTest: text + truncated, 415 fallback, offline/refused + retry, late answers; FilesRepositoryTest: text preview decoding and statuses; FilePreviewScreenTest: text + truncated notice | parent's command above | Pass |
| Other types fall back to Open with | FilePreviewViewModelTest: unsupported without server call; FilePreviewScreenTest: unsupported offers Open with, PDF failed offers Open with; FilesRouteInAppTest: open with FileProvider grant | parent's command above | Pass |
| Previous/next file in the folder | FilePreviewViewModelTest: step through files, stop at ends, file not in folder, no-op without preview; FilePreviewScreenTest: neighbour names, disabled at ends; FilesRouteInAppTest: next/previous through folder, back closes preview first | parent's command above | Pass |
| Thumbnails in the list (server thumbnails) | FilePreviewScreenTest: image rows show thumbnail, none without loader, selected row shows check, tap previews; FilePreviewModelsTest: preview and thumbnail URLs | parent's command above | Pass |
| EN + DE strings | FilePreviewScreenTest: German preview, German PDF pages, every new string translated (all 14 changed keys) | parent's command above | Pass |
| CR-1 cancellable download | FilesRepositoryTest: `a cancelled download stops copying at the next buffer` | parent's command above | Pass |
| CR-1 separate cache file for each load | PreviewFilesTest: every load gets its own file; FilePreviewViewModelTest: `opening the same PDF again while its first download still runs keeps the new file`, stepping/closing deletes partial file | parent's command above | Pass |
| CR-2 busy disables Save / Open with | FilePreviewScreenTest: `while another file downloads, Save and Open with are disabled...`, unsupported Open with disabled, `while a file downloads, the Open with of a PDF that cannot be shown is disabled` | parent's command above | Pass |
| Round 2: a PDF opened during cancellation is closed, and the close runs off the main thread | FilePreviewScreenTest: `a PDF still opening when the preview moves on is closed once it is open`, plus the three close tests using `assertClosed` | parent's command above | Pass |
| ktlintCheck, testDebugUnitTest and lintDebug pass | the whole Android unit suite | `cd android && ./gradlew ktlintCheck lintDebug createDebugUnitTestCoverageReport` | Pass (parent run). `scripts/check.sh` was not run by the test-writer: the parent runs it |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck lintDebug createDebugUnitTestCoverageReport` (parent run on b521e71c9; it runs all unit tests, including the files tests)

Result:

Pass: exit 0, 3860 tests, 0 failures, 0 errors, 0 skipped. The test-writer cannot run Gradle inside this sub-agent's sandbox, so the parent ran this command.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck lintDebug createDebugUnitTestCoverageReport`

Result:

Pass (parent run, same run as above). This is the full Android unit suite, plus ktlint and Android lint.

## .taskcheck

Result:

Not run by the test-writer, because the sandbox cannot run Gradle. The parent runs `sh scripts/check.sh` before `done`.

## Bugs found

None. Reviewing the tests against the criteria and both fix rounds found no production defect.

## Coverage

Status: verified (parent-run measurement on the final revision b521e71c9; the test-writer read the generated report itself).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id: and-files-preview. Immutable base: b3359b2e9133ab1cabc75989e6502e2212856de0 (the claim commit). Measured HEAD: b521e71c92e151bc48913b68f364165a30cde45b. The file scope comes from `git diff --stat b3359b2e9..b521e71c9 -- android/app/src/main`, checked against the task commits and the `touches` of the task.
- Source/test snapshot: commit b521e71c9 with a clean working tree. No uncommitted source or test inputs.
- Included files: `android/app/src/main/java/ch/sonensei/cockpit/files/{DeviceFiles,FileImages,FilePreviewView,FilesModels,FilesRepository,FilesScreen,FilesViewModel,PdfPages}.kt`. Excluded: `res/values/strings_files.xml` and `res/values-de/strings_files.xml`, because resources are not executable (the translation test checks them), and all test files. No files were renamed or deleted.
- Provider: JaCoCo, through the project's existing `createDebugUnitTestCoverageReport` task (AGP unit test coverage). Command: `./gradlew ktlintCheck lintDebug createDebugUnitTestCoverageReport`, working directory `android/`, test selection: the whole debug unit test suite.
- Run: completed 2026-11-08 16:40 local time, exit 0. The parent recorded only the completion time.
- Report: `android/app/build/reports/coverage/test/debug/report.xml`, 5737907 bytes, modified 2026-11-08 16:40, sha256 `936c6f21eb29bd0864b8286e03ad3606c4a0b20b70387c92f29ecaf1f168652b`.
- Freshness: the report is newer than commit b521e71c9 (16:35:56), and the working tree is clean, so the inputs did not change after the run. The test-writer took the per-line data below from this same file.
- Final run after the latest test additions and production fixes: yes. This pass added no tests.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `DeviceFiles.kt` | N/A | 57/62 (91.9%) | 21/21 (100%) | 91/91 (100.0%) |
| `FileImages.kt` | N/A | N/A (no branches) | not recorded | 7/7 (100.0%) |
| `FilePreviewView.kt` | N/A | 84/88 (95.5%) | 67/67 (100%) | 262/264 (99.2%) |
| `FilesModels.kt` | N/A | 41/42 (97.6%) | 30/30 (100%) | 51/51 (100.0%) |
| `FilesRepository.kt` | N/A | 31/34 (91.2%) | 35/37 (94.6%) | 110/112 (98.2%) |
| `FilesScreen.kt` | N/A | 284/313 (90.7%) | not recorded | 605/611 (99.0%) |
| `FilesViewModel.kt` | N/A | 262/289 (90.7%) | not recorded | 364/364 (100.0%) |
| `PdfPages.kt` | N/A | 2/18 (11.1%) | 3/6 (50.0%) | 10/41 (24.4%) |
| **Total (summed counters, 8 of 8 scoped files measured, none missing)** | N/A | 761/846 (90.0%) | not aggregated | 1500/1541 (97.3%) |

JaCoCo has no statement counter, so statements are N/A. The function figures are JaCoCo METHOD counters, read by the test-writer from the report for the files it inspected. They are not aggregated because not all files were read.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `PdfPages.kt:26, 34-82` (the `openPdf` success path; all of `RendererPdfPages`: constructor, `render` including the new `createBitmap` line 62, `close`) | Real PDF rendering with `PdfRenderer`, page bitmaps, closing the renderer and the file descriptor | Robolectric has no `PdfRenderer` implementation, so this needs an instrumented or device test. `PreviewFilesTest` covers only the failure path of `openPdf`. The UI, the lifecycle and the round-2 close are covered against a fake `PdfPages`. Justified gap. |
| `FilePreviewView.kt:159, 392` (lines with no executed instructions) and branches at 150, 253 (2), 391 | Line 159 and lines 391-392 are the closing parts of exhaustive `when` expressions over sealed types (`PreviewContent`, `PdfState`). Line 150 is the `Problem` branch and line 253 is the `OpenWithButton` lambda. | Compiler- or Compose-generated branches, for example the fallback of an exhaustive `when`. These cannot be reached from source. The offline/refused Problem notice and the busy/not-busy Open with are both tested. Non-material. |
| `FilesRepository.kt:77, 80, 166, 191, 322` (partial) and branches at 127, 191, 271 | Defensive paths in the repository (mostly code that existed before this task in a file the task touched) | No test added: these are the remaining branches of an existing file, and the preview paths the task added are covered by `FilesRepositoryTest`. Non-material. |
| `DeviceFiles.kt` branches at 44, 52, 98, 124, 180; `FilesModels.kt` branch at 114 | Null and elvis fallbacks | Every line is covered. The missed branches are defensive or compiler-generated null branches. Non-material. |
| `FilesScreen.kt` (6 lines, 29 branches) and `FilesViewModel.kt` (27 branches) | Compose recomposition and `when` branches, much of it list code from before this task | The task's preview wiring is covered by `FilesScreenTest`, `FilesRouteInAppTest` and `FilePreviewViewModelTest`. Non-material. |
| `FileImages.kt` (Coil loader with auth headers, memory-only) | Real loading of images and thumbnails from the server | Lines are 100%, but only fake `ImageLoader`s exercise it. The real network and auth path is not tested. Low-risk glue. |
| `FilePreviewView.kt` gesture math under real touch | How pinch and pan feel on a device | `FilePreviewModelsTest` and the Compose gesture tests cover the logic. Real-device feel cannot be tested here. |

## Unable to test

- Real `PdfRenderer` rendering, `RendererPdfPages.close` and the KTX `createBitmap` call: Robolectric has no `PdfRenderer`. These need a device or emulator test. Follow up only if the project adds instrumented tests.
- Real touch gesture feel and real Coil network loading: outside unit-test reach. No follow-up needed.
- The test-writer cannot run Gradle or `scripts/check.sh` in this sandbox. All results above come from the parent's run on b521e71c9.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. Round 2 is covered by `a PDF still opening when the preview moves on is closed once it is open` and by the three close tests, which now wait for the off-main-thread close through `assertClosed`. This pass added no tests. On b521e71c9 the parent's run passed: 3860 tests, 0 failures, 0 errors, 0 skipped, ktlint and lint clean. Whole-file coverage is 1500/1541 lines (97.3%) and 761/846 branches (90.0%). The one significant gap is real `PdfRenderer` rendering in `PdfPages.kt`, which needs a device.
