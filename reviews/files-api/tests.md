# Test Report

Date: 2026-10-21

Task: files-api - Files API: upload, download, operations

Scope: `git diff c3b429ed64eceb70609b59999406373f0e3248fd..fa633bff1d6ed3021267e8e438102e9869c3dd1e`, without `tasks/` and `reviews/`. The parent gave the base (merge-base with origin/main) and the task commits 573bc24d, c27b5850, e48373ca, 01aa873f, 133a5b3d, 8184c4d6, merged into HEAD fa633bff. `git log c3b429ed..fa633bff` lists exactly these commits plus the merge, all prefixed "files-api:". Measured revision: fa633bff. While the run was going on, the code-reviewer committed d996a5ec, which changes only `reviews/files-api/review.md`. `git diff fa633bff..d996a5ec -- server api web` is empty, so this report applies to d996a5ec too. The previous version of this report described 4e2decc5 and is replaced here.

Requirements:
- `tasks/files-api.md`: acceptance criteria 1-5 and the Notes/ASSUMPTION lines (API layout, resumable-upload rules, max size, downloads, folder moves)
- `SPEC.md` §8.2 (through the task), `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`
- `reviews/files-api/review.md` / `fixes.md` (CR-1: nullable folder ids in the OpenAPI contract, CR-2: JSON-only 200 bodies)

## Test files changed

None in this run. The task's test files are already committed at HEAD: `ChunkStoreTests.kt`, `FilesApiEdgeCaseWebTests.kt`, `FilesApiSupportTests.kt`, `FilesConcurrencyTests.kt`, `FilesMaxSizeWebTests.kt`, `FilesWebTests.kt` and `UploadServiceTests.kt` under `server/src/test/kotlin/ch/sonensei/cockpit/files/`.

## Tests added or updated

Count: 0

Since the last report (4e2decc5), the production changes are all in 133a5b3d, and all of them are OpenAPI annotations:
- `FilesDtos.kt`: `types = ["string","null"], format = "uuid"` on `CreateFolderRequest.parentId`, `FolderPatchRequest.parentId`, `FilePatchRequest.folderId`, `CreateUploadRequest.folderId`.
- `DriveController.kt` and `UploadController.kt`: the 200 response of `listFolder` / `getUpload` is documented as `application/json` with an explicit schema.

These tests already cover the changes, and all of them ran in this run:
- `FilesApiEdgeCaseWebTests` `the OpenAPI contract allows null for the folder fields that mean the drive root` covers the generated schema of all four fields.
- `FilesApiEdgeCaseWebTests` `listFolder and getUpload document their 200 body as application json only` covers the contract.
- `FilesApiEdgeCaseWebTests` `an explicit null folder id on create means the drive root, as the contract allows` covers runtime behavior for POST folders and uploads. Explicit `null` on PATCH is covered by `FilesWebTests` lines 319/331 (move to the root).
- `FilesApiEdgeCaseWebTests` `listFolder and getUpload answer a client that accepts only application json, also with errors` covers 200 and 404 with `Accept: application/json`.
- `OpenApiContractTest` checks that the committed `api/openapi.yaml` equals the generated contract.

I compared every acceptance criterion with the tests again (table below) and found no meaningful gap, so I added no tests.

## Requirement coverage

Focused command (F): `cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.files.*' --tests '*OpenApiContractTest' jacocoTestReport ktlintCheck` (2026-10-21, finished 00:31 +02:00, BUILD SUCCESSFUL in 1m 19s, JUnit XML: 179 tests, 0 failures, 0 errors, 0 skipped. Postgres runs through Testcontainers.)

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Multipart upload | FilesWebTests `a multipart upload is stored...`, `an upload without the file part is a 400...`, `uploading into a missing folder is a 404`; FilesApiEdgeCaseWebTests `the name parameter overrides...`, `an empty file uploads and downloads`, `a part without a content type...`, `a part without a filename...` | F | Pass |
| AC1 Chunked resumable upload (`POST /uploads`, `PUT .../chunks/{n}`, `POST .../complete` checks size + sha256) | FilesWebTests `a 60 MB file uploads in chunks...`, `an upload resumes after a missing chunk`, `a wrong sha256 stores nothing...`, `chunks with the wrong length...`, `an aborted upload is gone`; FilesApiEdgeCaseWebTests resumable-upload group, `an explicit null folder id on create...`, `listFolder and getUpload answer a client that accepts only application json...`; UploadServiceTests (9); ChunkStoreTests (12) | F | Pass |
| AC1 Max size from config (default 5 GB) | FilesMaxSizeWebTests (4; `cockpit.files.max-size=1KB`: multipart 413, declared size 400, usage, Spring multipart limit); FilesWebTests 6 GB rejected; FilesApiEdgeCaseWebTests `upload requests are validated` (exactly 5 GB accepted) | F | Pass |
| AC2 Download with Range | FilesWebTests `downloads support Range requests` (206, suffix range, 416); FilesApiEdgeCaseWebTests `a partial download keeps the attachment header, and several ranges come as multipart byteranges` | F | Pass |
| AC2 Content-Disposition (RFC 6266 filename*) | FilesWebTests `a multipart upload is stored...`, `a non-ASCII name gets an ASCII fallback...`; FilesApiEdgeCaseWebTests partial-download test (header on 206); FilesApiSupportTests (11, incl. a round trip through Spring's parser) | F | Pass |
| AC2 Folder download as a streamed zip | FilesWebTests `a folder downloads as a streamed zip with its subtree` (async, timeout -1); FilesApiEdgeCaseWebTests zip group (empty dirs, duplicate names, trashed content excluded) | F | Pass |
| AC3 Rename, move (no cycles) | FilesWebTests `rename and move a file need its ETag`, `a folder cannot be moved into itself or below itself`, `moving a folder next to one with the same name...`; FilesApiEdgeCaseWebTests special-folder, trashed-target and PATCH-validation tests; FilesConcurrencyTests crossing moves / move into a folder being trashed | F | Pass |
| AC3 Delete (trash), new folder | FilesWebTests `delete moves a folder to the Trash...`; FilesApiEdgeCaseWebTests `a trashed file or folder cannot be changed...`, `a stale ETag on delete is a 412...`, `an explicit null folder id on create...` | F | Pass |
| AC3 List folder (cursor) | FilesWebTests `a folder lists folders first, then files, by name, in pages`; FilesApiEdgeCaseWebTests listing group (malformed cursors/limits 400, unknown folder 404, JSON-only Accept) | F | Pass |
| AC3 Disk usage endpoint | FilesWebTests `disk usage counts active and trashed files`; FilesApiEdgeCaseWebTests `usage counts folders not in the Trash`; FilesMaxSizeWebTests usage test | F | Pass |
| AC4 `./gradlew test --tests 'ch.sonensei.cockpit.files.*'` passes (Testcontainers) incl. resume after a missing chunk and a 60 MB chunked upload | whole `ch.sonensei.cockpit.files` package + OpenApiContractTest | F | Pass (179/179) |
| AC5 `api/openapi.yaml` regenerated, `OpenApiContractTest` passes without the flag | OpenApiContractTest (run without `UPDATE_OPENAPI`) | F | Pass |
| AC5 Contract quality (CR-1 nullable folder ids, CR-2 JSON-only 200 bodies, every parameter has a schema) | FilesApiEdgeCaseWebTests `the OpenAPI contract allows null...`, `listFolder and getUpload document their 200 body as application json only`, `every parameter of the files endpoints in the OpenAPI contract has a schema...` | F | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.files.*' --tests '*OpenApiContractTest' jacocoTestReport ktlintCheck`

Result:

Pass. Exit 0, BUILD SUCCESSFUL in 1m 19s. 179 tests, 0 failures/errors/skipped, counted from `server/build/test-results/test/*.xml` (written 2026-10-21 00:31:12 +02:00). ktlintCheck passes for main and test sources. `--rerun` forced `:test` to run, so the coverage comes from this run.

Kotlin compiler warnings (they do not fail the build, and they are not behavior defects):
- `server/src/main/kotlin/ch/sonensei/cockpit/files/infra/DriveQueries.kt:64:99` Unnecessary non-null assertion (`!!`) on a non-null receiver of type `DriveQueries.Totals`. This file is task code. AGENTS.md says "fix warnings, don't suppress them", so the parent may want to remove the `!!`.
- `server/src/test/kotlin/ch/sonensei/cockpit/files/FilesServiceTests.kt:138:19` Unnecessary `!!` on a `String`. This is a pre-existing test outside this task's diff.

## Full test execution

Command:

Not run by me in this session.

Result:

Not run. The parent's notes record `sh scripts/check.sh` passing on 6a697dbb (about 4 min). Production code is unchanged since then, apart from rebase/merge hashes (no diff under `server/` or `api/` against the code that was checked). The parent must run `.taskcheck` again before `done` (`done` does this anyway).

## .taskcheck

Result:

Not run (see above). The parent runs it.

## Bugs found

No production defects in the files-api scope.

Quality note (not a bug, no test needed): the unnecessary `!!` compiler warning in `DriveQueries.kt:64`, reported above.

## Unable to test

- A real Tomcat async timeout for long zip streams is checked only through the MockMvc async context timeout (-1), not with a real server streaming for longer than 30 s. The criteria do not require this. No follow-up needed.
- Android/web client generation from the regenerated contract is not part of this server-only run. The parent's `.taskcheck` covers it (web `api:gen` up-to-date check, Android `openApiGenerate`).

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.

All 5 acceptance criteria are covered by tests that pass at fa633bff: 179/179 focused tests, plus OpenApiContractTest and ktlint. The CR-1/CR-2 changes are only OpenAPI annotations. Their contract and runtime tests exist and pass. The bytecode coverage counters are identical to the previous measurement.

## Coverage

Status: verified (fresh focused run at fa633bff, exit 0).

Metric: whole-file coverage of task-changed production files. The numbers include old code in `FilesService.kt`, `BlobStore.kt`, `FilesRepositories.kt` and `ProblemDetailsAdvice.kt`, and hits from tests that existed before this task.

Changed-line coverage: 522/534 executable added or modified lines (97.8%). Method: JaCoCo XML `line` elements with `mi+ci > 0`, intersected with the `+` lines of `git diff -U0 c3b429ed..HEAD` for each scoped file. A line counts as covered when `ci > 0`. Uncovered changed lines: `FilesDtos.kt` 150-154 and 156-160, and `UploadService.kt` 181-182. Changed-branch coverage was not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: files-api; base `c3b429ed64eceb70609b59999406373f0e3248fd`; measured HEAD `fa633bff1d6ed3021267e8e438102e9869c3dd1e`; task commits 573bc24d, c27b5850, e48373ca, 01aa873f, 133a5b3d, 8184c4d6 (from the parent, matching `git log c3b429ed..fa633bff`). The file scope comes from `git diff --stat c3b429ed..HEAD` and matches the task `touches`, plus the documented `ProblemDetailsAdvice` addition and `server/build.gradle.kts`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD fa633bff with a clean working tree. `git diff HEAD -- server api | sha256sum` gave `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` (empty) before and after the run. There were no untracked inputs.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/` `core/web/ProblemDetailsAdvice.kt`, `files/api/{DriveController,FileController,FilesDtos,FilesWebSupport,FolderController,UploadController}.kt`, `files/domain/{DriveService,FilesService,UploadService,UploadSession}.kt`, `files/infra/{BlobStore,ChunkStore,DriveQueries,FilesRepositories}.kt`. There are no renames or deletions. Excluded:
  - `server/src/main/resources/application.properties`: configuration. FilesMaxSizeWebTests and the zip async-timeout assertion check its behavior.
  - `db/migration/files/V202610201900__files_upload_session.sql`: SQL that Flyway applies in every Testcontainers test.
  - `server/build.gradle.kts`: build configuration.
  - `api/openapi.yaml` and `web/src/app/api/generated/**`: generated files.
  - The test sources.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (Gradle 9.8.1, default toolVersion). The command was `./gradlew test --rerun --tests 'ch.sonensei.cockpit.files.*' --tests '*OpenApiContractTest' jacocoTestReport ktlintCheck`, run in `server/`.
- Run start/completion time and exit status: 2026-10-21, started about 00:29:50 +02:00 and completed 00:31 +02:00 (`test.exec` and XML written at 00:31, `date` 00:31:18), exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 `e9337cd72017d5b1b14c90c122018d6f8ab647d2107f32ee18765f34041ea418`, and `server/build/jacoco/test.exec`. Counters were extracted with `.tmp/tw4_cov.py` (Git-ignored).
- Evidence outputs are fresh and inputs stayed unchanged: yes. `:test` was forced with `--rerun`, both artifacts were rewritten by this run, and the snapshot hash was the same before and after. The concurrent commit d996a5ec only touches `reviews/`.
- Final run after latest test additions and production fixes: yes. It includes the CR-1/CR-2 fixes (133a5b3d) and the latest tests (8184c4d6). I added no tests.

Values below use covered/total (percentage). Zero-denominator or unsupported metrics are N/A. Statements = JaCoCo INSTRUCTION, Functions = METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `core/web/ProblemDetailsAdvice.kt` | 136/334 (40.7%) | 6/24 (25.0%) | 6/12 (50.0%) | 15/36 (41.7%) |
| `files/api/DriveController.kt` | 50/50 (100.0%) | N/A | 3/3 (100.0%) | 7/7 (100.0%) |
| `files/api/FileController.kt` | 269/277 (97.1%) | 13/16 (81.2%) | 13/13 (100.0%) | 38/38 (100.0%) |
| `files/api/FilesDtos.kt` | 571/696 (82.0%) | 9/16 (56.2%) | 73/81 (90.1%) | 126/136 (92.6%) |
| `files/api/FilesWebSupport.kt` | 218/218 (100.0%) | 30/30 (100.0%) | 5/5 (100.0%) | 18/18 (100.0%) |
| `files/api/FolderController.kt` | 233/243 (95.9%) | 2/2 (100.0%) | 11/11 (100.0%) | 35/35 (100.0%) |
| `files/api/UploadController.kt` | 136/136 (100.0%) | N/A | 6/6 (100.0%) | 22/22 (100.0%) |
| `files/domain/DriveService.kt` | 755/776 (97.3%) | 44/48 (91.7%) | 30/32 (93.8%) | 78/78 (100.0%) |
| `files/domain/FilesService.kt` | 1528/1530 (99.9%) | 107/110 (97.3%) | 50/50 (100.0%) | 215/215 (100.0%) |
| `files/domain/UploadService.kt` | 584/591 (98.8%) | 54/54 (100.0%) | 24/24 (100.0%) | 77/79 (97.5%) |
| `files/domain/UploadSession.kt` | 103/103 (100.0%) | 6/6 (100.0%) | 10/10 (100.0%) | 18/18 (100.0%) |
| `files/infra/BlobStore.kt` | 351/351 (100.0%) | 8/8 (100.0%) | 21/21 (100.0%) | 55/55 (100.0%) |
| `files/infra/ChunkStore.kt` | 284/294 (96.6%) | 10/10 (100.0%) | 13/13 (100.0%) | 40/40 (100.0%) |
| `files/infra/DriveQueries.kt` | 203/203 (100.0%) | 10/10 (100.0%) | 15/15 (100.0%) | 25/25 (100.0%) |
| `files/infra/FilesRepositories.kt` | N/A | N/A | N/A | N/A |
| **Aggregate (summed counters, 15 files, none missing)** | 5421/5802 (93.4%) | 299/334 (89.5%) | 280/296 (94.6%) | 769/802 (95.9%) |

`FilesRepositories.kt` holds only Spring Data interfaces. JaCoCo lists it with zero counters, so it is measured and N/A, not missing. The counters are identical to the 4e2decc5 measurement, because the 133a5b3d changes are annotation attributes and add no bytecode instructions.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `UploadService.kt:181-182` | Warn log when deleting the chunks fails (an I/O error during cleanup) | Defensive logging only, and it needs a failing file system to trigger. Justified gap. |
| `ProblemDetailsAdvice.kt` (whole file 40.7%; missed lines 46-64, 85-90, 110, 125-129) | Only `handleMissingServletRequestPart` belongs to this task, and `an upload without the file part is a 400 naming the part` covers it | The rest is pre-existing core code, covered by core tests outside this selection. Not in task scope. |
| `FilesDtos.kt:150-160`; branches 38, 39, 66, 67, 107, 108, 203 | `FolderPatchRequest`/`FilePatchRequest` exist only for the OpenAPI schema (PATCH parses `Map` bodies). The missed branches are the `checkNotNull(createdAt/updatedAt)` failures | Non-material. The schema is checked by `the OpenAPI contract allows null...` and OpenApiContractTest, and the null branches cannot be reached for persisted entities. |
| `FileController.kt:83,85` (null branches) | `originalFilename == null` / `contentType == null` | The behavior is covered by `a part without a filename needs the name parameter` and `a part without a content type...`. Spring passes `""` or a default type, never `null`. Justified gap. |
| `FolderController.kt` (10 missed instructions) | Exception paths of `use {}` inside the zip `StreamingResponseBody` | Compiler-generated cleanup. The zip contents are checked by 4 tests. Non-material. |
| `DriveService.kt:80,82,111,147` (branches) | A listing row that disappears between the two queries (a race); the `archiveEntries` 404 (the controller checks the folder first); a sub-branch of malformed-cursor handling | Defensive. Cursor validation is covered by `malformed cursors and limits are 400`, and the zip 404 by `unknown ids are 404...`. |
| `FilesService.kt:69,226,308` (branches) | Path lookup of a vanished parent; a file move to its current folder (a no-op); restore of an unlisted file | Defensive or pre-existing (restore is not part of this task). Left uncovered. |
