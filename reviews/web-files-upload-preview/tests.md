# Test Report

Date: 2026-10-21

Task: web-files-upload-preview - Files: upload and preview

Scope: `git diff fe95f0ae6c4805b5db0f282824f5316030255509..d9efd780b74bec4ea94cc4cf380958a656b0f7f8 -- web` on branch `main`. That is 11 task commits (5bbaa8b7, 2ae7f95a, 025e9c16, 7f868049, f773f9e7, 45781c36, 40a96b60, ec558abe, 5aec485d, a82b5d0a, d9efd780 = HEAD). It includes this run's one uncommitted spec change, listed below. The base and the commit list came from the parent worker and match `git log fe95f0ae..HEAD`. Every commit carries the task id. This report replaces the stale report, which was written for an earlier revision.

Requirements:
- tasks/web-files-upload-preview.md (acceptance criteria 1-4, plus the recorded assumptions, including the accepted P3 race of a cancelled small upload)
- SPEC.md §8.2 (Files)
- docs/ARCHITECTURE.md "Coverage", AGENTS.md project notes, docs/QUALITY.md
- reviews/web-files-upload-preview/review.md (final re-review: 0 P0/P1/P2, 1 P3 accepted as an assumption)
- Criterion 5 (Playwright) is out of scope by the parent's instruction. The parent already verified it, and this run did not run Playwright or Docker.

## Test files changed

- `web/src/app/features/files/browser/files-page.spec.ts` (uncommitted, for the parent to commit)

## Tests added or updated

Count: 1 (added)

- `FilesPage > uploads and previews > pages the preview only through the files of a folder that also holds subfolders`. Coverage showed that no test opened a preview in a folder that also lists subfolders (`files-page.ts:147`, the folder side of the `flatMap`). The test checks that the preview gets only the folder's files: "2 of 2", ArrowLeft moves to the first file, and a further ArrowLeft stays on it.

The specs committed earlier in the task cover the rest of criteria 1-4 well (292 focused tests). I read the final production code and found no other meaningful gap, so I added no more tests.

## Requirement coverage

`focused` = `npm --prefix web run test:ci -- --include=src/app/features/capture --include=src/app/features/files` (17 files, 292 tests passed); `check` = `npm --prefix web run check` (passed, 1677 tests).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 picker and drag & drop upload, multi-file | files-page.spec `uploads picked files into the shown folder and lists them once stored`, `uploads nothing when the picker is closed without a choice`, `uploads files dropped on the folder and outlines it while files are dragged over`, `lets drags of anything but files pass by`, `does not refresh the listing for an upload into another folder`; drop-hint.spec (4 tests, FileDrop directive); upload.service.spec `runs 2 uploads at a time and starts the next when one finishes` | focused, check | Pass |
| AC1 progress | upload.service.spec `sends a file up to 50 MB in one multipart request with progress`, `creates a resumable upload, sends every chunk and completes with the real SHA-256`; UploadJob `reports progress in whole percent, 100 for an empty file`; upload-panel.spec `shows each upload with its progress, and the count of running ones` | focused, check | Pass |
| AC1 chunked upload above 50 MB | `uses the chunked upload only above 50 MB` (exactly 50 MB goes in one request, 50 MB + 1 byte is chunked); the real 50 MB + 1 byte file in chunks with the real SHA-256; sha256.spec (FIPS vectors, matches WebCrypto in parts) | focused, check | Pass |
| AC1 chunk retry | `retries a chunk after a 503/500/network error/408/429 and completes the upload` (it.each), `retries a chunk up to the configured number of times, mixing failure kinds`, `fails after the last retry and keeps the upload for a resume`, `does not retry a chunk that fails with 400/403/404/409/413`, `waits the configured pause before retrying a chunk` | focused, check | Pass |
| AC1 resumable upload | `retry resumes with the chunks the server is missing and the hash of the whole file`, `retry starts a new upload when the old one expired (404)`, `a failing resume lookup (not 404) fails the retry again`, `a file that can no longer be read fails with a generic message and keeps the upload`, `a failed complete (checksum mismatch) fails the upload` | focused, check | Pass |
| AC1 cancel | small files: `cancel stops a running request; a cancelled job ignores retry`, `cancel of a queued upload means it is never sent`, `cancelling a running upload starts the next queued one`, `cancel of a done upload changes nothing`; chunked: `cancel aborts the running chunk and DELETEs the upload on the server`, `cancel between two chunks ...`, `cancel during the resume lookup ...`, `cancel while the upload is being created/completed ...` (4 tests), `cancel during the pause before a retry ...`, `cancel of a failed chunked upload discards it; a failing DELETE is ignored`; upload-panel.spec Cancel/Remove/Clear finished | focused, check | Pass |
| AC2 preview selection by mime | preview-kind.spec (table tests for image/pdf/text/markdown/audio/video/none, parameters, a Markdown name on text/plain, `hasThumbnail`); preview-dialog.spec `pages through the previewable files with the arrow keys, choosing the viewer by mime`, `starts at the first previewable file when the given one has no preview`, `renders nothing when no file has a preview`; files-page.spec `shows a folder with breadcrumbs, ... previews and download links` (the preview button for a PDF, the download link for a zip) | focused, check | Pass |
| AC2 images (thumbnails, lightbox) | file-thumb.spec (2 tests); preview-dialog.spec `shows an image as a lightbox with its name, position and download link`, `says so when an image cannot be loaded, and shows the next one again`; files-page.spec `opens the preview of a file, paging through the previewable files of the folder`, `pages the preview only through the files of a folder that also holds subfolders` (new) | focused, check | Pass |
| AC2 PDF (pdfjs-dist) | pdf-viewer.spec (13 tests with a fake pdf.js: loading with credentials, paging, render cancel and races, source change, load and render errors, destroy); loadPdfJs (2 tests) | focused, check | Pass (unit level. The real pdf.js rendering is criterion 5, which the parent verified with Playwright) |
| AC2 text/Markdown, audio/video | preview-dialog.spec text, Markdown and media cases, `says so when a text cannot be loaded`, stale text loads (2 tests), `shows the HTML and unsafe links of a Markdown file as inert text`, `leaves arrow keys typed in a media player to the player` | focused, check | Pass |
| AC3 capture attachments (upload, then pass fileIds) | capture-dialog.spec `uploads dropped and pasted files to the drive root and saves their fileIds`, `a failed upload shows Retry, and the retried file is attached`, `a failed attachment blocks saving, with the reason, until it is removed`, `a retry of a failed attachment lifts the block once it is attached`, `shows Waiting, not a percentage, for an attachment queued behind running uploads`, `announces Waiting in the live region and keeps Save disabled while an attachment is queued`, Remove (3 tests), `names the Retry and Remove buttons after their file`, drag/drop (2 tests), Shift+Enter attachment tests (4) | focused, check | Pass |
| AC4 Vitest tests for chunk retry and preview selection by mime; `cd web && npm run check` passes | the AC1 retry and AC2 mime rows above; whole web suite | `npm --prefix web run check` | Pass: api:check, lint, e2e typecheck, 91 files / 1677 tests, `ng build`. Warning only: initial bundle 650.91 kB over the 500 kB budget (not an error) |
| AC5 Playwright e2e | - | - | Not run: excluded by the parent's instruction. The parent recorded it as verified in the task file |

## Focused test execution

Command:

`npm --prefix web run test:ci -- --include=src/app/features/capture --include=src/app/features/files`

Result:

Pass: 17 files, 292 tests (before my change: 291 passed at HEAD d9efd780 with no local changes).

## Full test execution

Command:

`npm --prefix web run check`

Result:

Pass: lint clean, 91 test files / 1677 tests passed, build complete (warning: initial bundle budget exceeded by 150.91 kB).

## .taskcheck

Result:

Pass: `sh scripts/check.sh` -> "== all checks passed" (server, web, android).

## Bugs found

None. No production defect found.

Note on test stability (not a production bug): the coverage run at 08:14-08:19 (load average about 220 on the shared host from other agents) failed 85 tests in 13 files, all through timeouts. In `upload.service.spec.ts`, the real 50 MB SHA-256 test exceeded its 60 s budget. Its open request then failed the following tests of that file in `afterEach` ("Cannot configure the test module ..."). The failures in other features (settings, linked-items, ...) were timeouts of the same kind. A rerun at lower load (about 30-37) passed fully. That run is the one this report uses. The real-file hash test is the slowest test in scope and the first to break when the machine is overloaded.

## Coverage

Status: verified (fresh full run, exit 0, on the final snapshot including the new test).

Metric: whole-file coverage of task-changed production files. It includes hits from all web specs, not only the task's specs.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-files-upload-preview; base fe95f0ae6c4805b5db0f282824f5316030255509 (from the parent); HEAD d9efd780b74bec4ea94cc4cf380958a656b0f7f8; 11 task commits `git log fe95f0ae..HEAD`, all named after the task.
- Source/test snapshot identifier (including uncommitted inputs): HEAD d9efd780 plus one modified file, `web/src/app/features/files/browser/files-page.spec.ts`, sha256 `6f9ab4f32a2c2bca864f8a32f68ef9f1efe035c68b93696869675ab889cf2577`. Checked before and after the run. `git status` was unchanged.
- Exact included files; renamed/deleted/excluded files and reasons: the 13 production `.ts` files of `git diff --name-only fe95f0ae..HEAD -- web/src` minus `*.spec.ts` (table below). Excluded: the templates/styles (`*.html`, `*.scss`), which v8 does not instrument (their behavior is tested through the component specs); `web/angular.json` (pdf.js worker asset copy, config only, exercised by the e2e PDF test); `web/e2e/**` (Playwright specs). There were no renames or deletions of production files. All 13 files had report data, so none is missing.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + @vitest/coverage-v8 5.0.5 through `@angular/build:unit-test`; `npm --prefix web run coverage` (`ng test --watch=false --coverage ...`) from the repository root; the whole web suite (91 files, 1677 tests).
- Run start/completion time and exit status: 2026-10-21T08:22:11+02:00 to about 08:23:02+02:00, exit 0, 1677/1677 passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (sha256 `2f767de7747cda0cec198ee8c7b9a8204d09d7f8a44f3ae13ea3280ae0ea971c`, mtime 08:23:01), `web/coverage/web/coverage-summary.json` (sha256 `9f91c60669b3485179c38081457b93aecaabe87daf7295a1bee9cf222de19dd4`). Per-file counters were read from coverage-summary.json, and uncovered locations from coverage-final.json.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The failed run before it (08:14-08:19, exit 1) left no coverage-summary.json behind, and its data is not used. The reports above were written by the 08:22 run, and the snapshot hash did not change.
- Final run after latest test additions and production fixes: yes. It ran after the one added test. Production code did not change in this run.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/capture/capture-dialog.ts` | 97/97 (100%) | 59/60 (98.33%) | 30/30 (100%) | 76/76 (100%) |
| `web/src/app/features/files/browser/files-page.ts` | 141/145 (97.24%) | 57/64 (89.06%) | 37/38 (97.36%) | 120/120 (100%) |
| `web/src/app/features/files/preview/file-thumb.ts` | 18/18 (100%) | 16/16 (100%) | 3/3 (100%) | 10/10 (100%) |
| `web/src/app/features/files/preview/pdf-viewer.ts` | 123/127 (96.85%) | 44/47 (93.61%) | 18/20 (90%) | 96/98 (97.95%) |
| `web/src/app/features/files/preview/preview-dialog.ts` | 62/62 (100%) | 36/39 (92.3%) | 20/20 (100%) | 44/44 (100%) |
| `web/src/app/features/files/preview/preview-kind.ts` | 18/18 (100%) | 21/21 (100%) | 2/2 (100%) | 13/13 (100%) |
| `web/src/app/features/files/preview/preview.service.ts` | 12/12 (100%) | 7/8 (87.5%) | 5/5 (100%) | 9/9 (100%) |
| `web/src/app/features/files/upload/drop-hint.ts` | 8/8 (100%) | 8/9 (88.88%) | 1/1 (100%) | 2/2 (100%) |
| `web/src/app/features/files/upload/file-drop.ts` | 26/26 (100%) | 16/16 (100%) | 5/5 (100%) | 16/16 (100%) |
| `web/src/app/features/files/upload/format-bytes.ts` | 8/8 (100%) | 6/6 (100%) | 1/1 (100%) | 8/8 (100%) |
| `web/src/app/features/files/upload/sha256.ts` | 78/78 (100%) | 12/12 (100%) | 6/6 (100%) | 70/70 (100%) |
| `web/src/app/features/files/upload/upload-panel.ts` | 22/22 (100%) | 10/11 (90.9%) | 9/9 (100%) | 15/15 (100%) |
| `web/src/app/features/files/upload/upload.service.ts` | 179/179 (100%) | 71/72 (98.61%) | 45/45 (100%) | 148/148 (100%) |
| **Aggregate (summed counters, 13 files; no file missing; html/scss/angular.json/e2e excluded as above)** | 792/800 (99.00%) | 363/381 (95.28%) | 182/185 (98.38%) | 627/629 (99.68%) |

For comparison, the first run at 08:11 (HEAD with no local changes, exit 0, 1676 tests) gave the same numbers except files-page.ts branches 56/64. The new test covers the extra branch.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `files-page.ts:147` (the folder side of the `flatMap` in `preview`) | AC2: the preview pages only through files when the folder has subfolders | New test `pages the preview only through the files of a folder that also holds subfolders`. Now covered. The remaining `?? []` side (entries `null`) cannot be reached, because a preview button exists only once entries are loaded |
| `files-page.ts:75, 100, 175, 182, 184, 244, 325` | Not changed by this task (folder tree viewChild, usage with total 0, loadMore/remove guards, message fallback) | Outside the task scope: these lines come from web-files-browser and are unchanged in this diff |
| `pdf-viewer.ts:24-26` (real `loadPdfJs` import and worker URL) | AC2 PDF with the real pdf.js | Not unit-tested: the specs replace pdf.js with a fake through `setPdfJsLoader`. Covered by criterion 5 (Playwright "PDF preview renders a page", verified by the parent) |
| `pdf-viewer.ts:87` (viewChild initializer counted as a function), `:118` (`go` out of range), `:149` (render without a doc), `:160` (`devicePixelRatio` fallback) | Defensive guards | Not material. The pager buttons are disabled at the ends, the render effect runs only once pages are known, and jsdom always has `devicePixelRatio` |
| `preview-dialog.ts:57, 61, 69` (the `file ?` empty sides) | Dialog without a previewable file | Not material. `renders nothing when no file has a preview` covers the rendering. These are the computed fallbacks of that case, and they only produce empty strings |
| `preview.service.ts:42` (`body ?? ''`) | Text preview with an empty body | Not material. Angular's text response gives a string |
| `capture-dialog.ts:149` (`result()?.id ?? []`) | A job without a result at submit time | Unreachable: submit requires no unfinished and no failed jobs, and removed or cancelled jobs leave the list |
| `upload.service.ts:199` (`receivedChunks ?? []`) | A server answer without receivedChunks | Not material. `receivedChunks` is required in the OpenAPI `UploadResponse` |
| `drop-hint.ts:15`, `upload-panel.ts:15` (branch 0) | Compiler-generated branch in the component metadata | Not material (instrumentation artefact) |

## Unable to test

- Criterion 5 (Playwright, real pdf.js rendering, a 60 MB upload against the e2e stack): not run here by the parent's instruction. The parent already recorded it as verified. No follow-up from this run.
- The race in review P3 (a small upload cancelled after the server stored it shows "Cancelled"): the parent accepted it as an assumption, so I added no test that would lock in either behavior.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. I added one test (a folder with subfolders in the preview). The focused suite (292), `npm --prefix web run check` (1677) and `.taskcheck` pass. Whole-file coverage of the 13 task-changed production files is 99.00% statements, 95.28% branches, 98.38% functions and 99.68% lines. The remaining gaps are explained above.
