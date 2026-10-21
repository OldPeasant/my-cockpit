# Test Report

Date: 2026-10-21

Task: web-files-upload-preview - Files: upload and preview

Scope: `git diff 5a2e737da035b8d8d1f61420ff1a050a0f9aafb9..240872786fde54831d70804f6076c83c8a03db13 -- web` (task commits 568835d6, 1365cdb6, 56b50ce7, 24087278 = HEAD), plus this run's uncommitted spec changes (listed below). Branch `main`.

Requirements:
- tasks/web-files-upload-preview.md (acceptance criteria 1-5 and the three recorded assumptions)
- SPEC.md §8.2 (Files, web drive)
- docs/ARCHITECTURE.md "Coverage", AGENTS.md project notes, docs/QUALITY.md
- reviews/web-files-upload-preview/review.md, finding CR-1 (the missing tests), given to me by the parent worker

Continuation note: an earlier test-writer run was interrupted after it committed specs in 56b50ce7 (files/preview/*.spec.ts, files/upload/*.spec.ts, capture/capture-dialog.spec.ts, files/browser/files-page.spec.ts) but before it wrote this report. This run checked those specs against CR-1, fixed one test-design problem, added two tests for gaps, and wrote this report. It did not redo the existing specs.

## Test files changed

Committed earlier in 56b50ce7 (by the interrupted test-writer run):
- `web/src/app/features/files/upload/upload.service.spec.ts`
- `web/src/app/features/files/upload/upload-panel.spec.ts`
- `web/src/app/features/files/upload/sha256.spec.ts`
- `web/src/app/features/files/preview/preview-kind.spec.ts`
- `web/src/app/features/files/preview/preview-dialog.spec.ts`
- `web/src/app/features/files/preview/pdf-viewer.spec.ts`
- `web/src/app/features/files/preview/file-thumb.spec.ts`
- `web/src/app/features/capture/capture-dialog.spec.ts`
- `web/src/app/features/files/browser/files-page.spec.ts`

Changed in this run (uncommitted, for the parent to commit):
- `web/src/app/features/files/upload/upload.service.spec.ts`: the real 50 MB SHA-256 test now has a 60 s budget (`REAL_FILE_TIMEOUT`, used for both the Vitest test timeout and the `next`/`until` waits). Under `npm run coverage` the instrumented `sha256.ts` takes longer than 5 s to hash 50 MB. The test then timed out, left an open request behind, and that failed 23 more tests in the same file (`http.verify()` in afterEach, then "Cannot configure the test module"). Without coverage it passed. Its assertions did not change. New test: `cancel between two chunks sends no further chunk and DELETEs the upload`.
- `web/src/app/features/files/preview/pdf-viewer.spec.ts`: new test `does not show an older document that loads successfully after the source changed`.

## Tests added or updated

Count: 3 in this run (2 added, 1 timeout-only update). The earlier run's committed specs add or change about 190 test cases in the files above.

## Requirement coverage

`focused` = `npm --prefix web run test:ci -- --include=src/app/features/files --include=src/app/features/capture`; `check` = `npm --prefix web run check`.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 picker + drag & drop upload, multi-file | files-page.spec `uploads picked files into the shown folder and lists them once stored`, `uploads files dropped on the folder and outlines it while files are dragged over`, `lets drags of anything but files pass by`; upload.service.spec `runs 2 uploads at a time and starts the next when one finishes` | focused, check | Pass |
| AC1 progress | upload.service.spec `sends a file up to 50 MB in one multipart request with progress`, `creates a resumable upload, sends every chunk and completes with the real SHA-256`; UploadJob `reports progress in whole percent, 100 for an empty file`; upload-panel.spec `shows each upload with its progress, and the count of running ones` | focused, check | Pass |
| AC1 chunked > 50 MB (threshold) | `uses the chunked upload only above 50 MB` (exactly 50 MB is whole, 50 MB + 1 is chunked); real 50 MB + 1 file in 3 chunks with the real SHA-256 | focused, check | Pass |
| AC1 chunk retry: success after a retry | `retries a chunk after a 503/500/network error/408/429 and completes the upload`, `retries a chunk up to the configured number of times, mixing failure kinds`, `waits the configured pause before retrying a chunk` | focused, check | Pass |
| AC1 chunk retry: retries used up | `fails after the last retry and keeps the upload for a resume`; `does not retry a chunk that fails with 400/403/404/409/413` | focused, check | Pass |
| AC1 resumable: received chunks are skipped | `retry resumes with the chunks the server is missing and the hash of the whole file`, `retry starts a new upload when the old one expired (404)`, `a failing resume lookup (not 404) fails the retry again` | focused, check | Pass |
| AC1 cancel | `cancel aborts the running chunk and DELETEs the upload on the server`, `cancel during the pause before a retry stops the retry and DELETEs the upload`, `cancel between two chunks sends no further chunk and DELETEs the upload` (new), `cancel while the upload is being created leaves nothing behind`, `cancel of a failed chunked upload discards it; a failing DELETE is ignored`, small-file cancel tests, upload-panel `Cancel stops a running upload; ...` | focused, check | Pass |
| AC2 preview selection by mime | preview-kind.spec `previewKind` table tests (image/pdf/text/markdown/audio/video/none, name fallbacks), `hasThumbnail`; preview-dialog.spec `pages through the previewable files with the arrow keys, choosing the viewer by mime` | focused, check | Pass |
| AC2 images (thumbnails, lightbox) | file-thumb.spec (2 tests), preview-dialog.spec `shows an image as a lightbox ...`, `says so when an image cannot be loaded ...` | focused, check | Pass |
| AC2 PDF (pdfjs-dist) | pdf-viewer.spec (10 tests, with a fake pdf.js; includes the new stale-document test) | focused, check | Pass (unit level, fake pdf.js; real rendering is AC5) |
| AC2 text/Markdown, audio/video | preview-dialog.spec text/markdown/media cases, stale and failed text loads | focused, check | Pass |
| AC3 capture attachments: upload, Save disabled while uploading, fileIds sent | capture-dialog.spec `uploads dropped and pasted files to the drive root and saves their fileIds`, `a failed upload shows Retry, and the retried file is attached`, `Remove cancels a running upload; a removed attachment is not linked`, Shift+Enter attachment tests | focused, check | Pass |
| AC3 Retry/Remove buttons named after their file (accessibility) | capture-dialog.spec `names the Retry and Remove buttons after their file` | focused, check | **Fail: production bug TW-1** |
| AC4 `cd web && npm run check` passes | whole web suite | check | **Fail**: 1 failed / 1655 passed (TW-1). Lint and e2e typecheck pass. `ng build` did not run because the test step failed first |
| AC5 Playwright `e2e/files-upload/upload.spec.ts` (60 MB upload, PDF page renders) | e2e spec | not run | Not run: this run did not start the e2e stack. The parent must run `npm run e2e -- e2e/files-upload/upload.spec.ts` |

## Focused test execution

Command:

`npm --prefix web run test:ci -- --include=src/app/features/files --include=src/app/features/capture`

Result:

Fail: 16 files, 270 passed, 1 failed (`CaptureDialog > attachments > names the Retry and Remove buttons after their file`, TW-1). Both new tests pass.

## Full test execution

Command:

`npm --prefix web run check` (2026-10-21T04:32:43Z to 04:33:42Z)

Result:

Fail: api:check, lint ("All files pass linting.") and e2e:typecheck pass. test:ci: 90 files, 1655 passed, 1 failed (TW-1).

## .taskcheck

Result:

Fail: `sh scripts/check.sh` (2026-10-21T04:33:47Z to 04:43:39Z, exit 1). The tfp completion-gate tests passed (20 tests, OK) and server `./gradlew check` passed. Web failed on TW-1 only. Android was not reached because `set -e` stopped the script.

## Bugs found

### Capture attachment Retry/Remove buttons have no accessible name with the file name

Finding ID:

`TW-1`

Test:

`web/src/app/features/capture/capture-dialog.spec.ts:710` `CaptureDialog > attachments > names the Retry and Remove buttons after their file`

Production source:

`web/src/app/features/capture/capture-dialog.html:112-127` (`i18n-aria-label` + `aria-label="Retry {{ job.name }}"` / `aria-label="Remove {{ job.name }}"`)

Requirement:

AC3 (capture dialog attachments) plus the project's accessibility conventions: each row's buttons must say which file they act on. The template sets these labels on purpose, but they never reach the DOM attribute.

Expected behavior:

After a failed upload of `scan.jpg`, the buttons have the attributes `aria-label="Retry scan.jpg"` and `aria-label="Remove scan.jpg"`.

Actual behavior:

`getAttribute('aria-label')` is `null` for both buttons. When an attribute has an interpolation and an `i18n-*` marker, Angular 22 compiles it to i18n attribute update opcodes. These apply the value with `setPropertyAndInputs`, which calls `renderer.setProperty(el, 'aria-label', value)` (node_modules/@angular/core/fesm2022/_debug_node-chunk.mjs, `applyUpdateOpCodes`, lines about 15985-15990). The value ends up in a JS expando property named `aria-label`, and no ARIA attribute is set. This also happens in real browsers, not only in jsdom: no DOM property named `aria-label` reflects to the attribute. Plain `[aria-label]` bindings use `ɵɵariaProperty`, but the i18n path does not. Screen readers hear only "Retry" or "Remove" for each attachment. The upload panel avoids the problem with `[attr.aria-label]="retryLabel(job)"` and a `$localize` label.

Severity:

Low (accessibility; nothing breaks for sighted users). It does block AC4/.taskcheck, because the test fails.

Fix required:

`Yes - production code must be fixed by the parent worker / fixer.` (For example, use `[attr.aria-label]` with a `$localize` label in capture-dialog.ts, as upload-panel does.)

Related review findings (no new failing test; listed for the fixer):
- CR-4 (a failed attachment is saved silently): `capture-dialog.spec.ts:660` `a failed attachment does not block saving and is not linked` asserts the behavior the reviewer flagged. That test came from the earlier run and documents current behavior. No requirement source says it is correct. If the fixer adopts CR-4 (Save disabled while an attachment failed), this test must be changed to match the new required behavior. That is a requirement change, not a weakening of the test.
- CR-3 (concurrent PDF page renders) and CR-5 (cancel during create/complete): the requirements do not define the expected behavior, so this run added no failing tests for them. Coverage shows the related branches are not exercised (see gaps).

## Coverage

Status: partial-failed. Numbers below are diagnostic. The configured coverage command `npm --prefix web run coverage` exits 1 because TW-1 fails, and in that case Vitest writes no report (`web/coverage/web/` was not created). To still measure coverage, I ran the same command with the one failing test filtered out (it is not deleted or skipped in the source) and included the scoped files explicitly. Rerun coverage after the TW-1 fix.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-files-upload-preview; base 5a2e737da035b8d8d1f61420ff1a050a0f9aafb9; HEAD 240872786fde54831d70804f6076c83c8a03db13; task commits 568835d6, 1365cdb6, 56b50ce7, 24087278, given by the parent worker. Scope from `git diff --name-only 5a2e737d..HEAD -- web/src` without `*.spec.ts`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 24087278 plus `git diff HEAD -- web | sha256sum` = `da7dc321d459f4d761764e613d6dd7a5fe2847d1ea001c6dff512e59a772e43a` (uncommitted changes to upload.service.spec.ts and pdf-viewer.spec.ts only). The same hash before and after the final run.
- Exact included files: the 12 TypeScript files in the table. Excluded: the `.html` templates (capture-dialog, files-page, preview-dialog, upload-panel) and `.scss` files (capture-dialog, files-page, pdf-viewer, preview-dialog, upload-panel). V8 coverage does not instrument them. Template behavior is exercised through the component specs. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + @vitest/coverage-v8 5.0.5 through `@angular/build:unit-test`. Run from the repo root: `npm --prefix web run coverage -- --filter='^(?!.*names the Retry and Remove buttons after their file)' --coverage-include='src/app/features/capture/capture-dialog.ts' --coverage-include='src/app/features/files/browser/files-page.ts' --coverage-include='src/app/features/files/preview/*.ts' --coverage-include='src/app/features/files/upload/*.ts' --coverage-exclude='**/*.spec.ts'`. That selects the full web suite except the TW-1 test.
- Run start/completion time and exit status: 2026-10-21T04:43:53Z to 04:44:35Z, exit 0; 90 files, 1655 passed, 1 filtered out (reported as skipped).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 `ddd394df5d1d80df5361fda1dcf5ec17e2817f2e580c83c9141c951d6b1e1624`; `web/coverage/web/coverage-final.json` sha256 `f573f660ff18dbbc04a1454f400ccd49ef42c74743dcdc4031f7365f038cada0`; also `lcov.info` and `lcov-report/`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The unfiltered coverage run just before had produced no `web/coverage/web`, so this run created the directory. The snapshot hash is the same before and after.
- Final run after latest test additions and production fixes: yes for the test additions. No production fixes have been made yet, so this describes the pre-fix revision 24087278.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/capture/capture-dialog.ts` | 90/90 (100%) | 59/59 (100%) | 26/26 (100%) | 70/70 (100%) |
| `web/src/app/features/files/browser/files-page.ts` | 141/145 (97.24%) | 55/64 (85.93%) | 37/38 (97.36%) | 120/120 (100%) |
| `web/src/app/features/files/preview/file-thumb.ts` | 18/18 (100%) | 16/16 (100%) | 3/3 (100%) | 10/10 (100%) |
| `web/src/app/features/files/preview/pdf-viewer.ts` | 110/115 (95.65%) | 36/41 (87.80%) | 16/18 (88.88%) | 86/88 (97.72%) |
| `web/src/app/features/files/preview/preview-dialog.ts` | 62/62 (100%) | 36/39 (92.30%) | 20/20 (100%) | 44/44 (100%) |
| `web/src/app/features/files/preview/preview-kind.ts` | 18/18 (100%) | 21/21 (100%) | 2/2 (100%) | 13/13 (100%) |
| `web/src/app/features/files/preview/preview.service.ts` | 12/12 (100%) | 7/8 (87.50%) | 5/5 (100%) | 9/9 (100%) |
| `web/src/app/features/files/upload/file-drop.ts` | 26/26 (100%) | 16/16 (100%) | 5/5 (100%) | 16/16 (100%) |
| `web/src/app/features/files/upload/format-bytes.ts` | 8/8 (100%) | 6/6 (100%) | 1/1 (100%) | 8/8 (100%) |
| `web/src/app/features/files/upload/sha256.ts` | 78/78 (100%) | 12/12 (100%) | 6/6 (100%) | 70/70 (100%) |
| `web/src/app/features/files/upload/upload-panel.ts` | 22/22 (100%) | 10/11 (90.90%) | 9/9 (100%) | 15/15 (100%) |
| `web/src/app/features/files/upload/upload.service.ts` | 169/170 (99.41%) | 62/65 (95.38%) | 45/45 (100%) | 140/140 (100%) |
| **Sum of the 12 files above** | 754/764 (98.69%) | 336/358 (93.85%) | 175/178 (98.31%) | 601/603 (99.67%) |

The sum covers exactly the 12 files above. Not measured: the 4 `.html` templates and 5 `.scss` files named in Evidence. The 1 TW-1 test was excluded from the run. Before this run's additions the same measurement gave statements 752/764 and branches 334/358.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `pdf-viewer.ts:135` (document resolves after the source changed) | AC2: a stale PDF must not replace the new one | Test added: `does not show an older document that loads successfully after the source changed` (now covered) |
| `upload.service.ts:324` (`check()` throws Cancelled between chunks) | AC1 cancel | Test added: `cancel between two chunks sends no further chunk and DELETEs the upload` (now covered) |
| `upload.service.ts:161` (send resolved but the job was cancelled meanwhile), `:195[1]`, `:349[1]` | CR-5 race (cancel while create/complete answers) | Left uncovered. The required behavior is open (CR-5). Add a test once the fixer decides it |
| `pdf-viewer.ts:130` (source changed while pdf.js was loading), `:145` (render without a document), `:151[1]`, `:159[1]`, `:114[1]` | CR-3 render races / defensive guards | Left uncovered. `render()` is expected to change for CR-3, and the fixer should add a sequence test with that change |
| `pdf-viewer.ts:24-26` (real `import('pdfjs-dist')` and worker URL) | AC2 real pdf.js loading | Not reachable in unit tests (a fake loader is injected). Covered by the AC5 Playwright test, which this run did not execute |
| `pdf-viewer.ts:87` (anonymous function at `input.required`) | none (compiler artifact) | Non-material |
| `files-page.ts:130[1]` (picker closed without files), `:145[1]` (`entries() ?? []`) | defensive | Non-material. Other uncovered files-page locations (73, 98, 173, 180, 182, 242, 321) are pre-existing code, not changed by this task |
| `preview-dialog.ts:57[1]`, `:61[1]`, `:69[1]`; `preview.service.ts:42[1]`; `upload-panel.ts:15[0]` | defensive fallbacks (`??` / optional branches) | Non-material |

## Unable to test

- AC5 (Playwright 60 MB upload, PDF page renders against the e2e stack): not run in this helper run (no e2e stack was started). The parent worker must run `npm run e2e -- e2e/files-upload/upload.spec.ts` and record it.
- CR-3, CR-4, CR-5 expected behavior: no requirement source defines it. No tests were written for them. See "Related review findings".
- Real pdf.js rendering and the worker asset path at runtime: unit tests only check the constant `PDF_WORKER_SRC`. Real behavior depends on AC5.

## Summary

Tests added; a production defect was found and must be passed to the `fixer` before the task can be completed. TW-1: the capture dialog's Retry/Remove `aria-label`s never reach the DOM attribute. The failing test is kept; it blocks `npm run check` and `.taskcheck`. All CR-1 test areas are covered and pass: chunk retry success and retries used up, resume skipping received chunks, cancel during a request, between chunks and during the retry pause, previewKind selection by mime, and the capture upload/Save-disabled/fileIds flow. Coverage numbers are diagnostic (TW-1 test filtered out) and must be measured again after the fix.
