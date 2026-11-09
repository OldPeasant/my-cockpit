# Test Report

Date: 2026-11-09

Task: web-file-links-nopreview - Web: linked items for files without a preview

Scope: base ff39818981ace0c646932be1dbd8d79aed7cc4cd, task commit f3fff539f (rebased during this run to
7959c4179aca975ebefd1388404a4da36d30ed0e on top of task-board-only commits 83e655a52 and 024077eae;
`git diff f3fff539f 7959c4179 -- web` is empty, so the measured code is identical). Branch main.
Uncommitted inputs: only the three spec files listed below (test-writer changes).

Requirements:
- tasks/web-file-links-nopreview.md acceptance criteria
- docs/ARCHITECTURE.md "Coverage"
- Parent instructions (focused file-feature tests, German strings via messages-de.spec, no Gradle)

## Test files changed

- `web/src/app/features/files/browser/files-page.spec.ts`
- `web/src/app/features/files/preview/preview-dialog.spec.ts`
- `web/src/locale/messages-de.spec.ts`

## Tests added or updated

Count: 4 added (in addition to the parent's updated tests in files-page.spec.ts and preview-dialog.spec.ts)

- files-page.spec.ts: `opens a file without a preview alone in the dialog, with its download and linked items`
  (clicks the "Open Cards.zip" row in a folder with two images; dialog shows Cards.zip alone, no
  position or side buttons, ArrowRight keeps it, notice text, download href, Linked items toggle,
  no request for the zip's content or preview)
- preview-dialog.spec.ts: `shows a Word document without a preview with its notice, both downloads and Close`
  (DOCX mime; no viewer element and no preview request; header and notice downloads point to
  the file's content; ArrowLeft keeps it; Close closes)
- preview-dialog.spec.ts: `pages only through the previewable files when opened on one of them, skipping the zip`
  (regression guard: opening a PDF in a list starting with a zip still pages 2 of 2 and never
  lands on the zip)
- messages-de.spec.ts: `has the messages of files without a preview in English and German (web-file-links-nopreview)`
  (exact EN sources and DE targets for files.openFile, files.preview.unsupported,
  files.preview.downloadFile; the removed files.downloadFile unit is gone)

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: a file without a preview type can show its linked-items panel (dialog with "no preview" notice, download, links toggle) | preview-dialog.spec: `shows a file without a preview alone, with a notice, its download and its linked items` (parent), `shows a Word document without a preview ...`, `pages only through the previewable files ... skipping the zip`, `starts at the first previewable file when the given one is unknown` (parent); files-page.spec: `shows a folder with breadcrumbs ...` (parent, row is "Open Taxes.zip" button), `opens a file without a preview alone in the dialog ...` | `npx ng test --include='src/app/features/files/**/*.spec.ts' --include='src/locale/messages-de.spec.ts' --watch=false` (from web/) | Pass |
| AC2: new strings in English and German | messages-de.spec: `has the messages of files without a preview in English and German (web-file-links-nopreview)` plus the generic completeness/placeholder tests | same command | Pass |
| AC3: unit tests cover the new behavior | all of the above | same command; full suite via `npm run coverage` | Pass |
| AC3: `sh scripts/check.sh` passes | none (whole-project check) | Not run: it runs Gradle (server, android), which the parent told me not to run; the parent runs it | Not run: Gradle excluded by parent instruction |

## Focused test execution

Command:

`npx ng test --include='src/app/features/files/**/*.spec.ts' --include='src/locale/messages-de.spec.ts' --watch=false` (in web/)

Result:

Pass - 14 files, 249 tests passed.

Lint/format of the changed specs: `npx eslint --max-warnings=0` on the three specs -> no output (pass);
`npx prettier --check` on them -> all files use Prettier code style.

## Full test execution

Command:

`npm run coverage` (in web/; the full web Vitest suite with coverage)

Result:

Pass - 196 test files, 4815 tests passed, exit 0.

## .taskcheck

Result:

Not run - `sh scripts/check.sh` includes the Gradle server and Android checks, which the parent
instructed me not to run (Gradle build directory clashes with the parent). The parent runs it.

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: statements on all changed executable lines are covered. Method: the
provider's complete list of uncovered statements/branches in the scoped .ts files
(coverage-final.json) was intersected with the task diff's added lines (files-page.ts 141-147 and
290; preview-dialog.ts 24-27, 38-39, 54, 137-145); none of the uncovered locations (files-page.ts
75, 100, 150, 178, 185, 187, 247; preview-dialog.ts 70, 74, 82) is a changed line. The two .html
templates are 100% covered as whole files. Changed-branch coverage is not separately measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-file-links-nopreview; base ff39818981ace0c646932be1dbd8d79aed7cc4cd; HEAD 7959c4179aca975ebefd1388404a4da36d30ed0e (same web code as f3fff539f); task commit and scope from the parent and `git diff ff3981898 f3fff539f`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 (web/src/...) files-page.ts f1f74f9927b060e63e3476e922a91fbd1560aaf5f0defa425d3c8a9a88f5fe31, files-page.html 6e17a636b796a9f68cb06636693e009971ab00c42ef95e18a647ba78548f0518, preview-dialog.ts 91a7aa0d106a089b4da9d22b82c69be1c269db497527036add1c0ef67467793b, preview-dialog.html 94d51be27c61d39d213669379896733c0770189ec5285aa73fd1ba6a4877997a, files-page.spec.ts 851dda50ecd1a2071238235017f2c9a2fff8e2a874cb002537f9320443fa9283, preview-dialog.spec.ts dc8780882c51d49e78c8144fd81ec26560a01d803a178d9dd3bb04e8d0ab6be3, messages-de.spec.ts 66beb9fb516e580bd0b75e8c365410a75801cb04dc4aa29759fd52471ae65c06, messages.xlf e9ce4f019d95ca4ff1f89a536ca62dd125f994ce9c0abba90627a6ba815a34e2, messages.de.xlf eb8f8548ad4a04bccc48a8c7dc334e44eabef436c19576c56b201f32184d7f9f; identical before and after the run.
- Exact included files: web/src/app/features/files/browser/files-page.ts, files-page.html, web/src/app/features/files/preview/preview-dialog.ts, preview-dialog.html. Excluded: preview-dialog.scss (styles, not executable), messages.xlf and messages.de.xlf (translation data, checked by messages-de.spec instead), spec files (tests). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with @vitest/coverage-v8 5.0.5; `npm run coverage`; web/; full web suite.
- Run start/completion time and exit status: started 2026-11-09 11:15:02 +01:00, reports written 11:15:49 +01:00; exit 0 (196 files, 4815 tests passed).
- Generated report paths and report hashes/run identifier: web/coverage/web/coverage-final.json (sha256 be8d4c35c5922fe6659f8580b934e4c3e87b724133ca51fdfcc9b30d675b76a1), web/coverage/web/coverage-summary.json (sha256 446e0f11ed4676e0151f2e30bc60f0900db471291b94e1c3697fc0379d7a760f), web/coverage/web/lcov.info.
- Evidence outputs are fresh and inputs stayed unchanged: report files timestamped 11:15:49 within this run; input hashes identical before and after.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/files/browser/files-page.ts` | 141/145 (97.24%) | 58/64 (90.62%) | 37/38 (97.36%) | 120/120 (100%) |
| `web/src/app/features/files/browser/files-page.html` | 204/204 (100%) | 32/32 (100%) | 12/12 (100%) | 129/129 (100%) |
| `web/src/app/features/files/preview/preview-dialog.ts` | 71/71 (100%) | 40/43 (93.02%) | 24/24 (100%) | 51/51 (100%) |
| `web/src/app/features/files/preview/preview-dialog.html` | 100/100 (100%) | 24/24 (100%) | 5/5 (100%) | 64/64 (100%) |
| Aggregate (4 files, summed counters; scss and xlf excluded as above) | 516/520 (99.23%) | 154/163 (94.48%) | 78/79 (98.73%) | 364/364 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `files-page.ts:75` (viewChild initializer fn), `:100` (usage guard), `:150`, `:178`, `:185`, `:187` (loadMore guards), `:247` (remove while busy) | Pre-existing folder, usage, paging and delete behavior, not changed by this task | Left uncovered: outside the task's changed lines |
| `preview-dialog.ts:70`, `:74`, `:82` (the "no file" side of `file ? ... : ...` in src, downloadUrl, kind-dependent computed) | Defensive fallbacks when the dialog has no file; the template renders nothing then, so the computeds are never read | Left uncovered: pre-existing defensive code, not reachable through the rendered UI |

## Unable to test

- `sh scripts/check.sh` (AC3 second half): not run by the test-writer because it runs Gradle; the parent must run it.
- The visual look of the notice (preview-dialog.scss) is not asserted by unit tests; no follow-up needed beyond the parent's own visual check if desired.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.
