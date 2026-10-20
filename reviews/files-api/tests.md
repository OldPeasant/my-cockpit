# Test Report

Date: 2026-10-20

Task: files-api - Files API: upload, download, operations

Scope: `git diff 857df5c0c9ec96e03a1d04f9299ae2a0f01a92db..749646ed9878b89bc143499adcef04805db18864`, without `tasks/` and `reviews/`. The parent named the base. It handed over HEAD 30203425 (commits 0534a5fd, 7e761d77, 89b447dc, aaa1b6fd, 1d26c431, 30203425). While I was testing, the parent committed 749646ed, which only regenerates the web client (`web/src/app/api/generated/`). The server production code at 749646ed is identical to 30203425. Uncommitted inputs are only the two test files listed below. `reviews/files-api/review.md` is also modified in the working tree, by the concurrent code-reviewer, not by me.

Requirements:
- `tasks/files-api.md`: acceptance criteria 1-5 and the Notes/ASSUMPTION lines (API layout, resumable-upload rules, max size, downloads, folder moves)
- `SPEC.md` §8.2 (via the task), `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- `reviews/files-api/fixes.md` (CR-1..CR-4, already fixed, with their regression tests)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/files/UploadServiceTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/files/FilesApiEdgeCaseWebTests.kt`

## Tests added or updated

Count: 5 added, none changed or removed.

- `UploadServiceTests.complete verifies the size too and reports it with a hash mismatch, storing nothing` (AC1, "verifies size": the `size` field error had no test. It is reachable only through a damaged chunk on disk, so the test writes one directly to the ChunkStore.)
- `UploadServiceTests.a chunk whose stream breaks off is not stored and the upload stays open for a resend` (AC1 resume: an I/O error while the session exists is rethrown, no chunk is kept, and a resend works. This is the remaining branch of the CR-3 fix.)
- `FilesApiEdgeCaseWebTests.a part without a content type gets the mime type guessed from its name` (ASSUMPTION "mime from the part's content type, else guessed from the name")
- `FilesApiEdgeCaseWebTests.a part without a filename needs the name parameter` (multipart name resolution: 400 on `name`, nothing stored; with `name` it is stored)
- `FilesApiEdgeCaseWebTests.every parameter of the files endpoints in the OpenAPI contract has a schema, so client generators accept it` (AC5; **fails**, see TW-1)

## Requirement coverage

Focused command (F): `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*' --tests '*OpenApiContractTest' ktlintCheck --continue` (final run, 22:45-22:46, Testcontainers Postgres)

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Multipart upload | FilesWebTests `a multipart upload is stored...`, `an upload without the file part is a 400...`, `uploading into a missing folder is a 404`; FilesApiEdgeCaseWebTests `the name parameter overrides...`, `an empty file uploads and downloads`, `a part without a content type...`, `a part without a filename...` | F | Pass |
| AC1 Chunked resumable upload, complete verifies size + sha256 | FilesWebTests `a 60 MB file uploads in chunks...`, `an upload resumes after a missing chunk`, `a wrong sha256 stores nothing...`, `chunks with the wrong length...`, `an aborted upload is gone`; FilesApiEdgeCaseWebTests resumable-upload group (8 tests); UploadServiceTests (9 tests incl. `complete verifies the size too...`, `a chunk whose stream breaks off...`); ChunkStoreTests (12) | F | Pass |
| AC1 Max size from config (default 5 GB) | FilesMaxSizeWebTests (3, `cockpit.files.max-size=1KB`: 413 multipart, 400 declared size, usage + Spring multipart limit); FilesWebTests `chunks with the wrong length or number and oversized uploads...` (6 GB rejected), FilesApiEdgeCaseWebTests `upload requests are validated` (exactly 5 GB accepted), `disk usage counts...` (maxFileSize 5 GB) | F | Pass |
| AC2 Download with Range | FilesWebTests `downloads support Range requests` (206 with Content-Range, suffix range, 416) | F | Pass |
| AC2 Content-Disposition (RFC 6266 filename*) | FilesWebTests `a multipart upload is stored...`, `a non-ASCII name gets an ASCII fallback...`; FilesApiSupportTests (5 encoding tests incl. Spring's parser round-trip) | F | Pass |
| AC2 Folder download as streamed zip | FilesWebTests `a folder downloads as a streamed zip with its subtree` (asyncStarted, async timeout -1 = CR-1); FilesApiEdgeCaseWebTests zip group (3) | F | Pass |
| AC3 Rename, move (no cycles) | FilesWebTests `rename and move a file need its ETag`, `a folder cannot be moved into itself or below itself`, `moving a folder next to one with the same name...`; FilesApiEdgeCaseWebTests special-folder, trashed-target, PATCH validation tests; FilesConcurrencyTests `two crossing folder moves cannot create a cycle...`, `a folder move into a folder being trashed...` | F | Pass |
| AC3 Delete (trash), new folder | FilesWebTests `delete moves a folder to the Trash...`; FilesApiEdgeCaseWebTests `a trashed file or folder cannot be changed...`, `a stale ETag on delete is a 412...`; FilesServiceTests / FilesEdgeCaseTests | F | Pass |
| AC3 List folder (cursor) | FilesWebTests `a folder lists folders first, then files, by name, in pages`; FilesApiEdgeCaseWebTests listing group (4) | F | Pass |
| AC3 Disk usage endpoint | FilesWebTests `disk usage counts active and trashed files`; FilesApiEdgeCaseWebTests `usage counts folders not in the Trash`; FilesMaxSizeWebTests usage test | F | Pass |
| AC4 `./gradlew test --tests 'ch.sonensei.cockpit.files.*'` passes incl. resume after a missing chunk and a 60 MB chunked upload | whole files package (173 tests) | F | **Fail**: 172 pass, 1 fails (TW-1, the new contract test). The named resume and 60 MB tests pass. Without the new contract test, the same command passed in run 2 (173/173, see Focused test execution). |
| AC5 `api/openapi.yaml` regenerated and `OpenApiContractTest` passes without the flag | OpenApiContractTest | F | Pass |
| AC5 API documented (a valid contract that clients can generate from) | FilesApiEdgeCaseWebTests `every parameter of the files endpoints in the OpenAPI contract has a schema...`; Android `:app:openApiGenerate` inside `.taskcheck` | F; `sh scripts/check.sh` | **Fail** (TW-1) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*' --tests '*OpenApiContractTest' ktlintCheck jacocoTestReport` (run 2, 22:38:05-22:39:06, before the contract test was added)

Result:

Pass. Exit 0, 173 tests, 0 failures: FilesApiEdgeCaseWebTests 28, FilesWebTests 17, FilesServiceTests 24, FilesEdgeCaseTests 23, FilesConcurrencyTests 14, FileNamesTests 16, ChunkStoreTests 12, FilesApiSupportTests 11, FileIconTests 10, UploadServiceTests 9, BlobStoreTests 5, FilesMaxSizeWebTests 3, OpenApiContractTest 1. ktlintCheck passes.

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*' --tests '*OpenApiContractTest' ktlintCheck --continue` (run 3, final, 22:45:24-22:46:33)

Result:

Fail. Exit 1, 174 tests, 1 failed: `FilesApiEdgeCaseWebTests.every parameter of the files endpoints in the OpenAPI contract has a schema...` with "List should be empty but has 1 elements, first being: "get /api/v1/files/{id}/content header Range"" (TW-1). All other tests pass, and ktlintCheck passes.

## Full test execution

Command:

`sh scripts/check.sh` (the `.taskcheck`, run 22:41-22:43 at HEAD 749646ed with my first four new tests, before the contract test)

Result:

Fail. Per step:
- tfp completion-gate tests: Pass (20 tests)
- server `./gradlew --quiet check` (all server tests + ktlint): Pass. The heap fix in 30203425 works, and there was no OutOfMemoryError.
- web `npm run check`: Pass (api:check "up to date", lint, 668 tests, build. The only warning is a pre-existing bundle budget warning.)
- android: **Fail** at `:app:openApiGenerate`: "There were issues with the specification ... -attribute paths.'/api/v1/files/{id}/content'(get).parameters.[Range].content is missing". The Android unit tests and lint did not run because of this. See TW-1.

## .taskcheck

Result:

Fail (Android OpenAPI generation, TW-1). Server and web steps pass.

## Bugs found

### The Range header parameter in the OpenAPI contract has no schema, which breaks the Android client generation

Finding ID:

`TW-1`

Test:

`FilesApiEdgeCaseWebTests.every parameter of the files endpoints in the OpenAPI contract has a schema, so client generators accept it`. `.taskcheck` (Android `:app:openApiGenerate`) fails for the same reason.

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/files/api/FileController.kt:133` (`@Parameter(name = HttpHeaders.RANGE, in = ParameterIn.HEADER, description = ...)` has no `schema`). The resulting `api/openapi.yaml` (around line 1487, `name: Range`) has neither `schema` nor `content`.

Requirement:

AC5 "API documented: `api/openapi.yaml` regenerated". AGENTS.md: "API changes: server first, regenerate `api/openapi.yaml`, then clients", and `.taskcheck` must pass for `done`. OpenAPI 3 requires `schema` or `content` on every parameter.

Expected behavior:

Every parameter of the files endpoints in `/v3/api-docs` has a `schema` (e.g. `type: string` for `Range`), and `api/openapi.yaml` validates, so `cd android && ./gradlew :app:openApiGenerate` (part of `.taskcheck`) succeeds.

Actual behavior:

`GET /api/v1/files/{id}/content` documents the `Range` header without a schema. The Android generator rejects the spec ("parameters.[Range].content is missing"), so the Android step of `.taskcheck` fails before compiling. OpenApiContractTest passes because it only compares the committed and generated contracts; it does not validate them.

Severity:

High: it blocks `.taskcheck` and therefore `tasks.py done`, and it breaks the Android client build.

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.` Then regenerate `api/openapi.yaml` (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`), and the web client if `npm run api:check` asks for it.

## Coverage

Status: verified (run 2, the clean pass, on the snapshot before the contract test). The final run 3 is partial-failed: its test command exited 1 because of TW-1. Its coverage is diagnostic only, but its per-file counters are identical to run 2. The added contract test executes no files-module production code beyond the OpenAPI endpoint.

Metric: whole-file coverage of task-changed production files. The numbers include old code in `FilesService.kt`, `BlobStore.kt`, `FilesRepositories.kt` and `ProblemDetailsAdvice.kt`, and hits from pre-existing tests.

Changed-line coverage: 522/534 executable added/modified lines (97.8%). Method: JaCoCo XML `line` elements with `mi+ci > 0`, intersected with the `+` lines of `git diff -U0 857df5c0..HEAD -- server/src/main/kotlin`. A line counts as covered when `ci > 0`. The production files in the working tree equal HEAD, so the line numbers match. Uncovered changed lines: `FilesDtos.kt` 150-160 (the `FolderPatchRequest`/`FilePatchRequest` schema-only classes) and `UploadService.kt` 181-182. Changed-branch coverage was not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: files-api; base `857df5c0c9ec96e03a1d04f9299ae2a0f01a92db` (given by the parent); HEAD `749646ed9878b89bc143499adcef04805db18864`. Task-owned commits: 0534a5fd, 7e761d77, 89b447dc, aaa1b6fd, 1d26c431, 30203425, 749646ed (`git log 857df5c0..HEAD`, all "files-api:"). The scope matches the task `touches` plus the noted `ProblemDetailsAdvice` addition.
- Source/test snapshot identifier (including uncommitted inputs): production = HEAD 749646ed (`git diff HEAD -- server/src/main api` is empty). Uncommitted test diff: `git diff HEAD -- server | sha256sum` = `2b1cba79...ca02dac` for run 2 (4 new tests) and `cae4caf4...abee814` for run 3 (5 new tests). Per-file test hashes are in `.tmp/tw-snap-before.txt` and `.tmp/tw-snap-final.txt`, and both were verified unchanged after their runs.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/` `core/web/ProblemDetailsAdvice.kt`, `files/api/{DriveController,FileController,FilesDtos,FilesWebSupport,FolderController,UploadController}.kt`, `files/domain/{DriveService,FilesService,UploadService,UploadSession}.kt`, `files/infra/{BlobStore,ChunkStore,DriveQueries,FilesRepositories}.kt`. No renames, no deletions. Excluded: `server/src/main/resources/application.properties` (configuration; its behavior is covered by the CR-1 async-timeout and FilesMaxSizeWebTests assertions), `db/migration/files/V202610201900__files_upload_session.sql` (SQL, applied by Flyway in every Testcontainers test), `server/build.gradle.kts` (build configuration), `api/openapi.yaml` and `web/src/app/api/generated/**` (generated), and tests.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (Gradle 9.8.1, default toolVersion, not pinned in `server/build.gradle.kts`), JDK 21.0.12.1. Run 2: `./gradlew test --tests 'ch.sonensei.cockpit.files.*' --tests '*OpenApiContractTest' ktlintCheck jacocoTestReport` in `server/`. Run 3: the same with `--continue` and without the report task, then `./gradlew jacocoTestReport -x test` on run 3's exec data.
- Run start/completion time and exit status: run 2 2026-10-20T22:38:05+02:00 to 22:39:06, exit 0. Run 3 22:45:24 to 22:46:33, test exit 1 (TW-1), report exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` (and `html/`), exec `server/build/jacoco/test.exec`. Run 2 XML sha256 `6ea41260b2065269f9e18e39b3fd65e0290e4730dde764559e38e0bfcf6b7bff`. Run 3 XML sha256 `e42496fbcd37afc9b6d4a029f27eb755c6209a9a8aed71a9117627ef673e2d40`. A baseline run before my tests (22:35-22:36, XML `165893d9...`) gave UploadService 569/591 instructions and 52/54 branches.
- Evidence outputs are fresh and inputs stayed unchanged: each XML was regenerated by its run (mtime 22:38:59 for run 2; regenerated after run 3). The test snapshot was verified unchanged after both runs, and production code was unchanged throughout. Counters were extracted with `.tmp/tw_cov.py` and `.tmp/tw_changed_lines.py` (both Git-ignored).
- Final run after latest test additions and production fixes: yes for the tests (run 3, partial-failed because of TW-1). TW-1 is not fixed yet: after the fixer's change, coverage and the focused suite must be rerun.

Values below use covered/total (percentage) from run 2 (identical in run 3); zero-denominator or unsupported metrics are N/A. Statements = JaCoCo INSTRUCTION, Functions = METHOD.

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

`FilesRepositories.kt` holds only Spring Data interfaces. JaCoCo lists it with zero counters (no executable bytecode), so it is measured but N/A. It is not missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `UploadService.kt:150` (size mismatch), `:114` (I/O error with the session still present) | AC1 "complete verifies size"; resume after a broken chunk | Added `complete verifies the size too...` and `a chunk whose stream breaks off...`. Both lines are now covered (branches 52/54 -> 54/54). |
| `UploadService.kt:181-182` | The log line when deleting chunks fails (I/O error on cleanup) | Not covered. Defensive logging only; triggering it needs a failing file system. Justified gap. |
| `ProblemDetailsAdvice.kt` (whole file 40.7%) | Only line 117 (`handleMissingServletRequestPart`) belongs to this task, and it is covered (`an upload without the file part is a 400 naming the part`). | The rest is pre-existing core code, covered by core tests outside this selection. Not in task scope. |
| `FilesDtos.kt:150-160`, `:38-39,66-67,107-108,203` (branches) | `FolderPatchRequest`/`FilePatchRequest` exist only for the OpenAPI schema (PATCH parses `Map` bodies); `checkNotNull(createdAt/updatedAt)` failure branches | Non-material. The patch classes are documentation-only, and the null branches cannot be reached for persisted entities. |
| `FileController.kt:83,85` (null branches) | `originalFilename == null` / `contentType == null` | The behavior is covered by the two new multipart tests (no filename -> 400 `name`; no content type -> guessed). MockMvc and Spring deliver `""`/a default type rather than `null`, so the Kotlin null branches stay unhit. Justified gap. |
| `FolderController.kt:146,149` | Exception paths of `use {}` inside the zip `StreamingResponseBody` | Non-material compiler-generated cleanup. The zip contents are verified by 4 tests. |
| `DriveService.kt:80,82,111,147` | A listing row whose entity disappears between the two queries (race); `archiveEntries` 404 (the controller checks the folder first); malformed cursor sub-branch | Non-material or defensive. Cursor validation is covered by `malformed cursors and limits are 400`, and zip 404 by `unknown ids are 404...`. |
| `FilesService.kt:69,226,308` (branches) | Path lookup of a vanished parent; a file "move" to its current folder (no-op); restore of an unlisted file | Defensive or pre-existing (restore is not part of this task). The no-op move has no observable effect. Left uncovered. |

## Unable to test

- Android client generation and Android unit tests/lint: I ran them through `.taskcheck`. They fail at `:app:openApiGenerate` (TW-1), so the Android unit tests and lint never ran. Follow-up: rerun `.taskcheck` after the fix.
- Real Tomcat async timeout for long zip streams (CR-1): checked only through the MockMvc async context timeout (-1), not with a real server streaming for more than 30 s. A real-server run is optional follow-up and not required by the criteria.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion.

TW-1 (High): the `Range` header parameter of `GET /api/v1/files/{id}/content` has no schema in the OpenAPI contract. As a result, the Android `openApiGenerate` step of `.taskcheck` fails, and the new contract test fails. Everything else in the acceptance criteria is covered by passing tests, including the 60 MB chunked upload and resume after a missing chunk. The server `check` passes and no longer runs out of memory, and the web check passes.
