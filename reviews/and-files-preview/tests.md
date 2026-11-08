# Test Report

Date: 2026-11-08

Task: and-files-preview - Android files: in-app preview

Scope: `b3359b2e9133ab1cabc75989e6502e2212856de0..63d0999f4d0a44a55a964eaa1953cb7fe70c47da`, limited to `android/` (task commits 340c6c113..39d2acf2e merged via `origin/wip/and-files-preview`, plus fix commit 63d0999f4 for review findings CR-1, CR-2, CR-3). Branch `main`, HEAD 63d0999f4. Uncommitted at report time: only the test change listed below.

Requirements:
- `tasks/and-files-preview.md` acceptance criteria and recorded assumptions
- SPEC.md §8.2 (in-app preview, PDF via `PdfRenderer`)
- `reviews/and-files-preview/review.md` and `fixes.md` (CR-1 cancellable download + per-load cache file, CR-2 busy disables Save/Open with)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/files/FilePreviewScreenTest.kt`

## Tests added or updated

Count: 1

- `FilePreviewScreenTest`: `while a file downloads, the Open with of a PDF that cannot be shown is disabled` (CR-2 fix passes `busy` into `PdfPreview`'s failed notice; this path had no test, the other two busy paths did).

Existing task tests reviewed (already present, unchanged): `FilePreviewModelsTest`, `FilePreviewScreenTest`, `FilePreviewViewModelTest`, `PreviewFilesTest`, `FilesRepositoryTest`, `FilesRouteInAppTest`, `FilesScreenTest` (all in `android/app/src/test/java/ch/sonensei/cockpit/files`). They cover every criterion and every fix; no redundant or weakened tests found.

## Requirement coverage

Command used by the parent on 63d0999f4 (before the one added test): `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*'` -> all pass, ktlint ok. The new test was not run by the test-writer (see "Focused test execution").

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Image preview with zoom | FilePreviewScreenTest: image loaded / without loader / failed + retry / failure does not block next / double tap zoom / pinch zoom 1x..5x / next image unzoomed; FilePreviewModelsTest: zoom clamp, pan bounds, image type detection | parent's files test command | Pass |
| PDF via PdfRenderer with page navigation | FilePreviewScreenTest: page by page + bar turns pages / single page / closed on step and close / new file starts page 1 / cannot be opened / no pages / double-tap zoom; FilePreviewModelsTest: page size; FilePreviewViewModelTest: download into cache, failure cleanup, unwritable cache, stale loads; PreviewFilesTest: missing file is no PDF | parent's files test command | Pass (fake `PdfPages`; real `PdfRenderer` rendering not run, see "Unable to test") |
| Text preview | FilePreviewViewModelTest: text + truncated, 415 fallback, offline/refused + retry, late answers; FilesRepositoryTest: text preview decoding and statuses; FilePreviewScreenTest: text + truncated notice | parent's files test command | Pass |
| Other types fall back to Open with | FilePreviewViewModelTest: unsupported without server call; FilePreviewScreenTest: unsupported offers Open with, PDF failed offers Open with; FilesRouteInAppTest: open with FileProvider grant | parent's files test command | Pass |
| Previous/next file in the folder | FilePreviewViewModelTest: step through files, stop at ends, file not in folder, no-op without preview; FilePreviewScreenTest: neighbour names, disabled at ends; FilesRouteInAppTest: next/previous through folder, back closes preview first | parent's files test command | Pass |
| Thumbnails in the list (server thumbnails) | FilePreviewScreenTest: image rows show thumbnail, none without loader, selected row shows check, tap previews; FilePreviewModelsTest: preview and thumbnail URLs | parent's files test command | Pass |
| EN + DE strings | FilePreviewScreenTest: German preview, German PDF pages, every new string translated (all 14 changed keys) | parent's files test command | Pass |
| CR-1 cancellable download | FilesRepositoryTest: `a cancelled download stops copying at the next buffer` | parent's files test command | Pass |
| CR-1 per-load cache file | PreviewFilesTest: every load gets its own file; FilePreviewViewModelTest: `opening the same PDF again while its first download still runs keeps the new file`, stepping/closing deletes partial file | parent's files test command | Pass |
| CR-2 busy disables Save / Open with | FilePreviewScreenTest: `while another file downloads, Save and Open with are disabled...`, unsupported Open with disabled, new: PDF-failed Open with disabled | parent's files test command | Pass for existing tests; new test Not run: test-writer cannot run Gradle in this sandbox |
| ktlintCheck testDebugUnitTest lintDebug and scripts/check.sh pass | n/a (parent runs) | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`; `sh scripts/check.sh` | Not run by test-writer: parent runs them |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.files.FilePreviewScreenTest'`

Result:

Not run by the test-writer: Gradle cannot run inside this sub-agent's sandbox. The parent must run it to cover the one added test. The parent's earlier run of all `ch.sonensei.cockpit.files.*` tests on 63d0999f4 passed.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run by the test-writer (sandbox); the parent runs it.

## .taskcheck

Result:

Not run by the test-writer (sandbox); the parent runs `sh scripts/check.sh`.

## Bugs found

None. No production defect was found while reviewing the tests against the criteria and the fixes.

## Coverage

Status: unavailable to the test-writer (Gradle cannot run in the sandbox); measured by the parent, see below.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Task coverage scope (production files changed in `b3359b2e9..63d0999f4` under `android/`):
- `android/app/src/main/java/ch/sonensei/cockpit/files/DeviceFiles.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/files/FileImages.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilePreviewView.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesModels.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesRepository.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesScreen.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/files/FilesViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/files/PdfPages.kt`

Excluded: `res/values/strings_files.xml`, `res/values-de/strings_files.xml` (resources, not executable; checked by the translation test), all test files.

### Coverage (measured by parent)

The task-scoped coverage measurement is provided by the parent: the parent runs `cd android && ./gradlew createDebugUnitTestCoverageReport` on the final snapshot (63d0999f4 plus the added test) and appends the per-file numbers for the files above from the generated `report.xml` below this paragraph, with command, run time, exit status and report hash.

### Significant untested areas seen by the test-writer

| File and location | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `PdfPages.kt` (`RendererPdfPages`: `render`, `close`, the success path of `openPdf`) | Real PDF rendering with `PdfRenderer`, page bitmaps, closing the renderer and file descriptor | Not testable under Robolectric (no `PdfRenderer` implementation); needs an instrumented/device test. Only the `openPdf` failure path is covered (`PreviewFilesTest`). The UI is covered against a fake `PdfPages`. |
| `FileImages.kt` (Coil loader with auth headers, memory-only) | Real image/thumbnail loading from the server | Covered only through injected fake `ImageLoader`s; the production loader's network/auth wiring is not exercised in unit tests. Justified gap: low-risk glue. |
| `FilePreviewView.kt` gesture math under real touch | Pinch/pan feel on a device | Logic covered by `FilePreviewModelsTest` and Compose gesture tests; real-device feel not testable here. |

## Unable to test

- Real `PdfRenderer` rendering (Robolectric lacks it): needs a device or emulator test; follow-up only if the project adds instrumented tests.
- Running the added test, the files suite, lint and `scripts/check.sh`: the test-writer sandbox cannot run Gradle. The parent must run `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.files.FilePreviewScreenTest'` (and the full check) before relying on this report.

## Summary

The tests cover every acceptance criterion and fix, and no production defects were found. The parent still has to add the coverage numbers. One test was added for the only untested fix path (a PDF that cannot be shown keeps Open with disabled while another file downloads). The test-writer could not run it, so the parent must run `FilePreviewScreenTest` and append the coverage numbers.
