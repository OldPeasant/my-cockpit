# Test Report

Date: 2026-10-21

Task: files-preview - Thumbnails and previews

Scope: task commits `bd3d17b4f5a2766e739d479452de8f2db86f5436..054a2d823bb8a1a1852fb76dd5df5eb4b726c50d` on `main`
(ac0a87e3 implementation, 8aaa677c extra tests + review, 4144ca25 fixes CR-1..CR-3, 054a2d82 fix report), plus one
uncommitted test addition by this test-writer run (see Coverage evidence).

Requirements:
- `tasks/files-preview.md` acceptance criteria and its recorded assumptions (thumbnail endpoint and formats, 415/422
  rules, text preview types, 100 MP limit, cache layout)
- SPEC.md §8.2 (Files: preview), §4.2 (API conventions)
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md project notes
- `reviews/files-preview/review.md` and `fixes.md` (findings already addressed)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/files/ThumbnailStoreTests.kt`

## Tests added or updated

Count: 1

- `an image with a valid signature but a truncated header is undecodable and caches nothing` (new). The PNG signature
  selects the PNG reader, but the IHDR chunk is cut off. The test expects `UndecodableImageException` (the 422 path),
  no cached thumbnail and no temporary file. Before this test, `ThumbnailStore.kt:127-128` (an IOException while
  reading the header) had no coverage. The existing tests only reached the "no reader found" path at line 120.

Most of the behavior was already covered: 4 test classes and 44 preview/thumbnail tests from the implementation,
the earlier test-writer commit and the fixer. No other meaningful gaps remained (see the gap table).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: Thumbnails at 256 px and 1024 px with Thumbnailator | `png thumbnail scales the longer side to 256 by default and to 1024 on request`, `a 1024 thumbnail of an image between the sizes keeps its original size`, `jpeg thumbnail is a jpeg and a small image is not enlarged` | `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.files.*' jacocoTestReport` | Pass |
| AC1: jpeg/png/webp/gif supported; PNG output keeps alpha | `gif and webp images get png thumbnails`, `a png thumbnail keeps transparency`, `thumbnail accepts a mime type with other case and parameters` | same | Pass |
| AC1: cached on disk, deleted when the blob is purged | `thumbnails are cached on disk and deleted with the purged blob`, `purging a folder deletes the cached thumbnails of the files inside it, also in subfolders`, `delete removes every size and format and tolerates missing thumbnails`, `concurrent requests for the same thumbnail all get the same complete file`, `temporary files left by an interrupted render are removed at startup, cached thumbnails are kept` | same | Pass |
| AC1: EXIF orientation honored | `jpeg thumbnail honors the EXIF orientation`, `a small jpeg is rotated by its EXIF orientation without being enlarged`, `a 1024 jpeg thumbnail honors the EXIF orientation too` | same | Pass |
| Assumption: other sizes 400; non-images and SVG 415; undecodable images and images above 100 MP 422 | `thumbnail rejects other sizes, non-images and undecodable images`, `thumbnail size that is not a number is a bad request`, `an image header claiming more than 100 megapixels is rejected with 422`, `an image above 100 megapixels is rejected from its header without decoding`, `an image just below the pixel limit passes the size guard`, `a huge image header with an overflowing pixel count is still rejected`, `content that is no image format is undecodable and leaves no temporary file`, **`an image with a valid signature but a truncated header is undecodable and caches nothing`** (new) | same | Pass |
| AC2: text/markdown preview limited to the first 256 KB, UTF-8 safe | `markdown preview is cut at 256 KB without splitting a character`, `a text file of exactly 256 KB is not truncated and one byte more is`, `an empty text file previews as empty text`, `html is previewed as plain text and invalid UTF-8 is replaced`, `text preview returns the whole of a short file as UTF-8 text`, `json, yaml and csv are previewed as plain text and markdown with parameters stays markdown`, `a text preview is never sniffed as html by the browser`, PreviewTextTests (4) | same | Pass |
| AC2: PDF/audio/video served inline with the correct mime type | `pdf, audio, video and images are served inline with their mime type and range support`, `video is served inline with byte range support`, `an unsatisfiable range on an inline preview is 416`, `gif and webp images are served inline with their own mime type`, `a non-ascii file name gets an RFC 8187 inline content disposition`, `audio and video files with a malformed stored mime type have no preview instead of a server error` | same | Pass |
| AC2: other types 415; trashed or unknown files 404 | `preview rejects types without a preview`, `octet-stream and other unknown types have no preview`, `previews of trashed files are not found` | same | Pass |
| AC3: `./gradlew test --tests 'ch.sonensei.cockpit.files.*'` passes (Testcontainers Postgres) | all 16 files test classes, 223 tests | same | Pass (223 tests, 0 skipped, 0 failures, 0 errors) |
| AC4: OpenApiContractTest passes without the flag | `OpenApiContractTest`; `FilesApiEdgeCaseWebTests` path count 14 | `cd server && ./gradlew test --tests '*OpenApiContractTest'` | Pass |
| AC4 consequence: generated web client matches `api/openapi.yaml` | web `npm run check` (api:gen drift check) | `sh scripts/check.sh` | **Fail**: see TW-1 |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.ThumbnailStoreTests'`, then
`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.files.*' jacocoTestReport`

Result:

Pass. ThumbnailStoreTests: 10 tests, 0 failures. The files package: 223 tests in 16 classes, 0 skipped, 0 failures,
0 errors, including the Testcontainers-backed PreviewWebTests (12), PreviewEdgeCaseWebTests (18) and the other web and
service suites. ktlint passed.

## Full test execution

Command:

`sh scripts/check.sh` (`.taskcheck`), which runs the TFP gate tests, then `cd server && ./gradlew --quiet check`
(the full server suite with ktlint and detekt), then the web check

Result:

Server: Pass. The TFP gate tests and the server `check` stage both succeeded.
Web: Fail (TW-1). The script stops at this stage (`set -eu`), so the Android stage did not run.
`cd server && ./gradlew test --tests '*OpenApiContractTest'`: Pass.

## .taskcheck

Result:

Fail. The web stage reports `src/app/api/generated is out of date with ../api/openapi.yaml` (TW-1).

## Bugs found

### Generated web API client not regenerated after the OpenAPI change

Finding ID:

`TW-1`

Test:

`.taskcheck` web stage (`cd web && npm run check`, generated-client drift check)

Production source:

`web/src/app/api/generated/` (generated from `api/openapi.yaml`, which commit ac0a87e3 changed)

Requirement:

AGENTS.md "API changes: server first, regenerate `api/openapi.yaml`, then clients". `tasks.py done` runs `.taskcheck`,
which must pass.

Expected behavior:

`web/src/app/api/generated` matches `api/openapi.yaml`, and `.taskcheck` passes.

Actual behavior:

The check reports:
`missing: fn/files/get-file-thumbnail-jpeg.ts`, `missing: fn/files/get-file-thumbnail-png.ts`,
`missing: fn/files/preview-file.ts`, `changed: functions.ts`, `changed: index.ts` -
"Run `npm run api:gen` and commit the result." The task's `touches` list does not include
`web/src/app/api/generated`. The parent decides whether regenerating it belongs to this task. It is a generated
artifact, so it must not be edited by hand.

Severity:

High (it blocks `tasks.py done`)

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.` (run `cd web && npm run api:gen` and commit)

No functional defect was found in the preview or thumbnail code.

## Coverage

Status: verified (fresh run on the final test snapshot; the server code is unchanged since 054a2d82)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured separately. Added/modified lines come from `git diff -U0 bd3d17b4..HEAD`, intersected
with the JaCoCo per-line entries (executable lines only, removed lines not counted): **152/156 lines (97.4%)**.
Branch counters on the changed lines: 70/76. This is the JaCoCo branch total of those lines, not a separate
changed-branch metric. Uncovered changed lines: `ThumbnailStore.kt` 70, 71, 111, 112.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `files-preview`; base
  `bd3d17b4f5a2766e739d479452de8f2db86f5436` (from the parent); HEAD `054a2d823bb8a1a1852fb76dd5df5eb4b726c50d`; task
  commits ac0a87e3, 8aaa677c, 4144ca25, 054a2d82 (from the parent, matching `git log bd3d17b4..HEAD`); file scope from
  `git diff --name-only bd3d17b4..HEAD -- server/src/main web/src android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 054a2d82 plus the uncommitted
  `server/src/test/kotlin/ch/sonensei/cockpit/files/ThumbnailStoreTests.kt`, sha256
  `15ce884340aaf1ee8f1866b0184e097c8005a213835065a26790ea3d9276fff9`. Same hash before and after the run, with no
  other changes in the working tree.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/files/api/FilesWebSupport.kt`,
  `.../files/api/PreviewController.kt`, `.../files/domain/FilesService.kt`, `.../files/domain/PreviewService.kt`,
  `.../files/infra/ThumbnailStore.kt`. No renames or deletions. Excluded: `api/openapi.yaml` (generated spec),
  `server/build.gradle.kts` (build config), `docs/ARCHITECTURE.md`, `reviews/*`, test files. All 5 files appear in the
  report; none are missing.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (Gradle
  9.8.1); `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.files.*' jacocoTestReport` run in `server/`;
  selection `ch.sonensei.cockpit.files.*` (16 classes, 223 tests).
- Run start/completion time and exit status: 2026-10-21T01:39:29+02:00 to about 01:40:44+02:00; BUILD SUCCESSFUL
  (exit 0). The `test` task re-executed because the test sources changed.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (mtime 2026-10-21 01:40:43.97 +0200, sha256 `306b5cb6a128fe55aef5079355936cb86bc031642b900941a666b1f5cb96564c`).
  Earlier baseline run before the new test: report sha256 `3a72d270e09417856ff69700768fd90fc833e365ac40a08ce6878b0aff35588e`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report mtime is after the run start, and the
  snapshot hash was unchanged after the run.
- Final run after latest test additions and production fixes: yes. No production code changed after 054a2d82.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements (JaCoCo instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/files/api/FilesWebSupport.kt` | 229/229 (100%) | 30/30 (100%) | 7/7 (100%) | 20/20 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/files/api/PreviewController.kt` | 169/169 (100%) | 4/4 (100%) | 6/6 (100%) | 26/26 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt` | 1535/1537 (99.9%) | 107/110 (97.3%) | 50/50 (100%) | 217/217 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/PreviewService.kt` | 452/469 (96.4%) | 46/50 (92.0%) | 24/24 (100%) | 64/64 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/files/infra/ThumbnailStore.kt` | 424/455 (93.2%) | 20/22 (90.9%) | 16/16 (100%) | 57/61 (93.4%) |
| **Aggregate (summed counters, 5 of 5 files measured, none missing)** | 2809/2859 (98.3%) | 207/216 (95.8%) | 103/103 (100%) | 384/388 (99.0%) |

JaCoCo has no separate statement counter. Instructions are reported in the Statements column.
Effect of the new test on `ThumbnailStore.kt`: lines 55/61 to 57/61, instructions 416/455 to 424/455.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ThumbnailStore.kt:127-128` (header IOException) | Undecodable image -> 422 | Covered now by `an image with a valid signature but a truncated header is undecodable and caches nothing` |
| `ThumbnailStore.kt:69-71` (blob purged during the render, CR-3 fix) | No orphaned thumbnail after a purge race | Justified gap. A deterministic test would need a hook between render and move, and none exists without changing production code (also noted in fixes.md). The logic is 3 straight-line statements. |
| `ThumbnailStore.kt:111-112` (IllegalArgumentException from Thumbnailator) | Defensive mapping to 422 | Justified gap. No known input reaches it after `dimensions()` succeeded. |
| `ThumbnailStore.kt:118` (`createImageInputStream` returns null, e.g. a blob file missing on disk) | Defensive; inconsistent state (row without blob) | Justified gap, low risk. It maps to 422 instead of a 500. |
| `ThumbnailStore.kt:45` (5 instructions) | Startup cleanup of `thumbnails/tmp` | Behavior covered by the startup-cleanup test. The missed instructions are the compiler-generated exception path of `use`. |
| `PreviewService.kt:68` (`require` failure) | Defensive; the controller validates `size` first (400 tested) | Not reachable over HTTP; no test added. |
| `PreviewService.kt:121` (wildcard-type branch) | Wildcard subtype is tested (`audio/*`). A wildcard main type cannot reach this line, because `isInline` requires `audio/`/`video/`. | Unreachable branch combination. |
| `PreviewService.kt:136-137` (`start == 0`) | UTF-8 cut when the limit is 3 bytes or less | Unreachable with `TEXT_LIMIT` = 256 KB; the realistic cases are tested in PreviewTextTests. |
| `PreviewService.kt:94` (5 instructions) | Compiler-generated exception path of `use` | Non-material. |
| `FilesService.kt:71, 228, 310` (partial branches) | Pre-existing code outside this task's changed lines (the task changed only `deleteBlob` and its KDoc, both covered) | Out of task scope. |

## Unable to test

- The purge race in `ThumbnailStore.thumbnail` (lines 69-71, the CR-3 fix) cannot be tested deterministically
  without a production seam. Follow-up is optional, low priority.
- The Android stage of `.taskcheck` did not run because the web stage failed first (TW-1). After the fix, run
  `.taskcheck` again.

## Summary

Tests added; one production/artifact defect (TW-1: the generated web API client is out of date with
`api/openapi.yaml`, so `.taskcheck` fails) was discovered and must be passed to the `fixer` / parent before task
completion. The preview and thumbnail behavior itself is well covered. Its tests pass, and no functional defects were
found.
