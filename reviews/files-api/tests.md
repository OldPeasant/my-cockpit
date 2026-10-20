# Test Report

Date: 2026-10-20

Task: files-api - Files API: upload, download, operations

Scope: `git diff 6e420c3ff144629e2215cb3b3aa86785f6d178a1..4e2decc5e1e137906623b42234ef82331c844da9`, without `tasks/` and `reviews/`. The parent named the base and the task commits: bc1c124c (squashed implementation), b491adfd (TW-1 fix: string schema for the `Range` header, regenerated OpenAPI and web client), and 4e2decc5 (HEAD: web client regenerated after the rebase, plus the test `a partial download keeps the attachment header...`). I measured HEAD 4e2decc5 and added no tests, so this report describes 4e2decc5. There were no uncommitted inputs under `server/` or `api/`. The only working-tree change is `reviews/files-api/review.md`, which the code-reviewer modified, not me.

Requirements:
- `tasks/files-api.md`: acceptance criteria 1-5 and the Notes/ASSUMPTION lines (API layout, resumable-upload rules, max size, downloads, folder moves)
- `SPEC.md` §8.2 (through the task), `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- The previous version of this report (TW-1) and `reviews/files-api/fixes.md`

## Test files changed

None in this run. The test files the task changed (`ChunkStoreTests.kt`, `FilesApiEdgeCaseWebTests.kt`, `FilesApiSupportTests.kt`, `FilesConcurrencyTests.kt`, `FilesMaxSizeWebTests.kt`, `FilesWebTests.kt`, `UploadServiceTests.kt` under `server/src/test/kotlin/ch/sonensei/cockpit/files/`) were already committed at HEAD.

## Tests added or updated

Count: 0

Since the previous report, the only production change is the `schema = Schema(type = "string")` attribute on the `Range` `@Parameter` in `FileController.kt`. Two tests already cover it: `FilesApiEdgeCaseWebTests.every parameter of the files endpoints in the OpenAPI contract has a schema...` (it failed before the fix and passes now) and `OpenApiContractTest` (it checks that the committed `api/openapi.yaml` matches the generated contract). The parent's new test in 4e2decc5 covers the Range edge cases that were still open: a one-byte range, the last byte, one byte past the end (416), multiple ranges (`multipart/byteranges`), and `Content-Disposition` on a 206. I compared every acceptance criterion with the existing tests (table below) and found no real gap, so I added no tests.

## Requirement coverage

Focused command (F): `cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.files.*' --tests '*OpenApiContractTest' ktlintCheck jacocoTestReport` (2026-10-20 23:35:3x-23:36:02 +02:00, exit 0, 175 tests, 0 failures, Postgres through Testcontainers)

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Multipart upload | FilesWebTests `a multipart upload is stored...`, `an upload without the file part is a 400...`, `uploading into a missing folder is a 404`; FilesApiEdgeCaseWebTests `the name parameter overrides...`, `an empty file uploads and downloads`, `a part without a content type...`, `a part without a filename...` | F | Pass |
| AC1 Chunked resumable upload; complete verifies size + sha256 | FilesWebTests `a 60 MB file uploads in chunks...`, `an upload resumes after a missing chunk`, `a wrong sha256 stores nothing...`, `chunks with the wrong length...`, `an aborted upload is gone`; FilesApiEdgeCaseWebTests resumable-upload group (8 tests); UploadServiceTests (9 tests, incl. `complete verifies the size too...`, `a chunk whose stream breaks off...`); ChunkStoreTests (12) | F | Pass |
| AC1 Max size from config (default 5 GB) | FilesMaxSizeWebTests (3 tests, `cockpit.files.max-size=1KB`: multipart 413, declared size 400, usage and Spring multipart limit); FilesWebTests `chunks with the wrong length or number and oversized uploads...` (6 GB rejected); FilesApiEdgeCaseWebTests `upload requests are validated` (exactly 5 GB accepted), `disk usage counts...` | F | Pass |
| AC2 Download with Range | FilesWebTests `downloads support Range requests`; FilesApiEdgeCaseWebTests `a partial download keeps the attachment header, and several ranges come as multipart byteranges` | F | Pass |
| AC2 Content-Disposition (RFC 6266 filename*) | FilesWebTests `a multipart upload is stored...`, `a non-ASCII name gets an ASCII fallback...`; FilesApiEdgeCaseWebTests partial-download test (header on 206); FilesApiSupportTests (encoding tests, incl. a round trip through Spring's parser) | F | Pass |
| AC2 Folder download as streamed zip | FilesWebTests `a folder downloads as a streamed zip with its subtree` (asyncStarted, async timeout -1); FilesApiEdgeCaseWebTests zip group (3) | F | Pass |
| AC3 Rename, move (no cycles) | FilesWebTests `rename and move a file need its ETag`, `a folder cannot be moved into itself or below itself`, `moving a folder next to one with the same name...`; FilesApiEdgeCaseWebTests special-folder, trashed-target and PATCH validation tests; FilesConcurrencyTests `two crossing folder moves cannot create a cycle...`, `a folder move into a folder being trashed...` | F | Pass |
| AC3 Delete (trash), new folder | FilesWebTests `delete moves a folder to the Trash...`; FilesApiEdgeCaseWebTests `a trashed file or folder cannot be changed...`, `a stale ETag on delete is a 412...`; FilesServiceTests / FilesEdgeCaseTests / FilesConcurrencyTests create tests | F | Pass |
| AC3 List folder (cursor) | FilesWebTests `a folder lists folders first, then files, by name, in pages`; FilesApiEdgeCaseWebTests listing group (4) | F | Pass |
| AC3 Disk usage endpoint | FilesWebTests `disk usage counts active and trashed files`; FilesApiEdgeCaseWebTests `usage counts folders not in the Trash`; FilesMaxSizeWebTests usage test | F | Pass |
| AC4 `./gradlew test --tests 'ch.sonensei.cockpit.files.*'` passes, incl. resume after a missing chunk and a 60 MB chunked upload | whole `files` package (174 tests) + OpenApiContractTest (1) | F | Pass (175/175) |
| AC5 `api/openapi.yaml` regenerated, `OpenApiContractTest` passes without the flag | OpenApiContractTest | F | Pass |
| AC5 API documented (a valid contract that clients can generate from; was TW-1) | FilesApiEdgeCaseWebTests `every parameter of the files endpoints in the OpenAPI contract has a schema...` | F | Pass. TW-1 is fixed. Android `:app:openApiGenerate`: Not run, because `.taskcheck` stopped at the server step before Android (see below). |

## Focused test execution

Command:

`cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.files.*' --tests '*OpenApiContractTest' ktlintCheck jacocoTestReport`

Result:

Pass. Exit 0 (BUILD SUCCESSFUL in 28 s), 175 tests and 0 failures by the JUnit XML in `server/build/test-results/test/`. ktlintCheck passes. I used `--rerun` because the parent's identical run had left `:test` up to date. Without it, the coverage would not have come from this run.

## Full test execution

Command:

`sh scripts/check.sh` (the `.taskcheck`), run twice at HEAD 4e2decc5: 23:36:31-23:41:01 and 23:42:5x-23:46:53. Then the web step was run on its own: `npm --prefix web run -s check`.

Result:

Fail, for a reason outside this task:
- tfp completion-gate tests: Pass (both runs).
- server `./gradlew --quiet check`: **Fail** in both runs. 2225 tests, exactly 1 failure, both times in `ch.sonensei.cockpit.core.persistence.ClockConfigurationTests.the application clock is the system clock in UTC` (`2026-10-20T21:45:46.885848Z should be >= 2026-10-20T21:45:46.885848360Z`). Every files test and every other server test passed. Running the class alone (`./gradlew test --rerun --tests '...ClockConfigurationTests'`) passed 4/4, so the failure depends on timing. See "Out-of-scope blocker" below.
- web `npm run -s check` (run on its own, because `set -eu` stopped check.sh after the server step): Pass. `src/app/api/generated is up to date`, lint passes, 45 test files / 757 tests pass, the build succeeds (the only warning is the existing bundle budget warning).
- android `ktlintCheck testDebugUnitTest lintDebug` (includes `:app:openApiGenerate`): Not run. check.sh stopped before this step, and running the Android Gradle command on its own was not approved in this session. So this run does not confirm that the TW-1 fix unblocks the Android generator. The parent must confirm it with a passing `.taskcheck`.

## .taskcheck

Result:

Fail (2 of 2 runs). The cause is the pre-existing, timing-dependent `ClockConfigurationTests` test, not the files-api code. Android was not reached.

### Out-of-scope blocker (not a files-api production bug)

`server/src/test/kotlin/ch/sonensei/cockpit/core/persistence/ClockConfigurationTests.kt:19-22` (from a2de2988, p0-db-conventions, already in the base) reads `before = Instant.now()` at nanosecond precision. It then asserts `clock.instant() >= before`, but the application clock is deliberately truncated to microseconds (the test's own third case asserts this). When both calls fall in the same microsecond, the truncated value is smaller than `before`, and the test fails. Under the full suite this happened in both runs. The test is wrong, not the production clock: the expectation would have to truncate `before` to microseconds (`Instant.now().truncatedTo(ChronoUnit.MICROS)`). I did not change it, because the test is outside this task's scope and `touches`. The parent decides whether to fix it here (it blocks `done`) or in a separate task. It also blocks `.taskcheck` for every agent on main.

## Bugs found

No production defects in the files-api scope. TW-1 from the previous report (the `Range` header parameter had no schema) is fixed in b491adfd. Its regression test passes, `api/openapi.yaml` contains `schema: type: string` for `Range`, and `OpenApiContractTest` passes.

## Coverage

Status: verified (fresh focused run at HEAD 4e2decc5, exit 0).

Metric: whole-file coverage of task-changed production files. The numbers include old code in `FilesService.kt`, `BlobStore.kt`, `FilesRepositories.kt` and `ProblemDetailsAdvice.kt`, and hits from tests that existed before this task.

Changed-line coverage: 522/534 executable added or modified lines (97.8%). Method: JaCoCo XML `line` elements with `mi+ci > 0`, intersected with the `+` lines of `git diff -U0 6e420c3f..HEAD -- server/src/main/kotlin`. A line counts as covered when `ci > 0`. Uncovered changed lines: `FilesDtos.kt` 150-154 and 156-160 (the schema-only classes `FolderPatchRequest`/`FilePatchRequest`) and `UploadService.kt` 181-182. Changed-branch coverage was not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: files-api; base `6e420c3ff144629e2215cb3b3aa86785f6d178a1` and commits bc1c124c, b491adfd, 4e2decc5, all given by the parent and matching `git log 6e420c3f..HEAD` (all "files-api:"). HEAD is `4e2decc5e1e137906623b42234ef82331c844da9`. The scope matches the task `touches`, plus the documented `ProblemDetailsAdvice` addition and `server/build.gradle.kts`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 4e2decc5. `git diff HEAD -- server api | sha256sum` gave `e3b0c442...b855` (empty diff) before and after all runs.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/` `core/web/ProblemDetailsAdvice.kt`, `files/api/{DriveController,FileController,FilesDtos,FilesWebSupport,FolderController,UploadController}.kt`, `files/domain/{DriveService,FilesService,UploadService,UploadSession}.kt`, `files/infra/{BlobStore,ChunkStore,DriveQueries,FilesRepositories}.kt`. There are no renames or deletions. Excluded: `server/src/main/resources/application.properties` (configuration; FilesMaxSizeWebTests and the zip async-timeout assertion check its behavior), `db/migration/files/V202610201900__files_upload_session.sql` (SQL, applied by Flyway in every Testcontainers test), `server/build.gradle.kts` (build configuration), `api/openapi.yaml` and `web/src/app/api/generated/**` (generated), and the tests.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (Gradle 9.8.1, default toolVersion). Command: `./gradlew test --rerun --tests 'ch.sonensei.cockpit.files.*' --tests '*OpenApiContractTest' ktlintCheck jacocoTestReport`, run in `server/`.
- Run start/completion time and exit status: 2026-10-20 about 23:35:35 to 23:36:02 +02:00 (exec written 23:36:00, XML 23:36:01), exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 `1b12017c10dc7f6041e08503877463050599e6eab4603d30ad4fc6409eb46eb0`, unchanged after the later check runs. The later `.taskcheck` runs overwrote `server/build/jacoco/test.exec`, but they did not regenerate the XML. Counters were extracted with `.tmp/tw_cov.py` (Git-ignored).
- Evidence outputs are fresh and inputs stayed unchanged: yes. `:test` was forced with `--rerun`, the XML was regenerated by this run, and the snapshot hash was the same before and after.
- Final run after latest test additions and production fixes: yes. It covers HEAD 4e2decc5, which includes the TW-1 fix and the parent's latest test. I added no tests.

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

`FilesRepositories.kt` holds only Spring Data interfaces. JaCoCo lists it with zero counters (no executable bytecode), so it is measured and N/A, not missing. The counters are identical to the previous report's: the TW-1 fix is an annotation attribute and adds no bytecode instructions.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `UploadService.kt:181-182` | Log line when deleting chunks fails (an I/O error during cleanup) | Not covered. It is defensive logging only, and triggering it needs a failing file system. Justified gap. |
| `ProblemDetailsAdvice.kt` (whole file 40.7%; missed branches at 61, 74, 75, 87, 88, 101, 125) | Only `handleMissingServletRequestPart` belongs to this task, and `an upload without the file part is a 400 naming the part` covers it | The rest is pre-existing core code that core tests outside this selection cover. Not in task scope. |
| `FilesDtos.kt:150-160`; branches 38, 39, 66, 67, 107, 108, 203 | `FolderPatchRequest`/`FilePatchRequest` exist only for the OpenAPI schema (PATCH parses `Map` bodies). The missed branches are the failure branches of `checkNotNull(createdAt/updatedAt)` | Non-material. The patch classes only document the API, and the null branches cannot be reached for persisted entities. |
| `FileController.kt:83,85` (null branches) | `originalFilename == null` / `contentType == null` | The behavior is covered by `a part without a filename needs the name parameter` and `a part without a content type...`. MockMvc and Spring pass `""` or a default type instead of `null`, so the Kotlin null branches are never hit. Justified gap. |
| `FolderController.kt` (10 missed instructions) | Exception paths of `use {}` inside the zip `StreamingResponseBody` | Non-material cleanup code generated by the compiler. The zip contents are checked by 4 tests. |
| `DriveService.kt:80,82,111,147` | A listing row whose entity disappears between the two queries (a race); the `archiveEntries` 404 (the controller checks the folder first); a malformed-cursor sub-branch | Non-material or defensive. Cursor validation is covered by `malformed cursors and limits are 400`, and the zip 404 by `unknown ids are 404...`. |
| `FilesService.kt:69,226,308` (branches) | Path lookup of a vanished parent; a file "move" to its current folder (a no-op); restore of an unlisted file | Defensive or pre-existing (restore is not part of this task). Left uncovered. |

## Unable to test

- Android client generation and Android unit tests/lint: not run. `.taskcheck` stopped at the server step both times (the `ClockConfigurationTests` flake), and running the Android Gradle step on its own was not approved in this session. Follow-up for the parent: get a `.taskcheck` run through the Android step. This confirms TW-1 end to end (`:app:openApiGenerate` accepts the regenerated `api/openapi.yaml`).
- Real Tomcat async timeout for long zip streams: checked only through the MockMvc async context timeout (-1), not with a real server streaming for more than 30 s. This is optional and not required by the criteria.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.

TW-1 is fixed, and all 175 focused tests (files package + OpenApiContractTest) and ktlint pass at HEAD 4e2decc5. `.taskcheck` still fails, but not because of files-api. The pre-existing, timing-dependent test `ClockConfigurationTests.the application clock is the system clock in UTC` failed in both full server runs (and passes alone). As a result, the Android step, which would confirm the TW-1 fix in the Android generator, has not run yet. The parent must resolve that test or get a passing `.taskcheck` before `done`.
