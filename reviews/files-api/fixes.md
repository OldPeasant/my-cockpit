# Fix Report

Date: 2026-10-20 (first round), 2026-10-21 (second round and TW-1)

Task: files-api - Files API: upload, download, operations

Scope: current task; findings CR-1 to CR-4 of `reviews/files-api/review.md` (base 2e447faa, reviewed HEAD 711ebfe4). Changes stay in the files module, its tests and `application.properties`. Second round: findings CR-1 and CR-2 of the second review (`reviews/files-api/review.md` as of commit 01aa873f), recorded here as CR2-1 and CR2-2 so they do not clash with the first-round ids; they were fixed in commit 133a5b3d by a previous worker. Third: TW-1 from `reviews/files-api/tests.md` (test report for fa633bff).

## Fixed findings

### CR-1 (P1) - Streamed folder zip is cut off by the default async request timeout

Files changed:
- `server/src/main/resources/application.properties`
- `server/src/test/kotlin/ch/sonensei/cockpit/files/FilesWebTests.kt`

Problem:
`GET /api/v1/folders/{id}/zip` returns a `StreamingResponseBody`, which runs as an async request. No async timeout was configured, so the container's default applied (30 s on Tomcat). That would cut long zip streams off and leave a corrupt archive.

Fix:
Set `spring.mvc.async.request-timeout=-1` (no timeout), with a comment explaining why. Spring passes the value to the servlet `AsyncContext`, where a value of 0 or less means no timeout. A stream still ends when the client disconnects. The only other async endpoint is the SSE stream in `core/events`, and `SseEventHub` gives each emitter its own timeout (`cockpit.events.stream-timeout`, default 15 min), so the global setting does not affect it. The zip web test now checks `started.request.asyncContext!!.timeout shouldBe -1L`, which pins the effective timeout the zip request gets. The MockMvc default is 10 s, so the test would fail without the setting.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*' ktlintCheck`: Pass (FilesWebTests 17/17)

### CR-2 (P2) - The configured max size has no committed test on the multipart path

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/files/FilesMaxSizeWebTests.kt` (new)

Problem:
The finding is valid. No committed test under `server/src/test/kotlin/ch/sonensei/cockpit/files/` used a non-default `cockpit.files.max-size`: a grep for `TestPropertySource`, `413` and `max-size` found nothing. No test exercised the multipart 413 path either. The only limit test was the default 5 GB on `POST /uploads`. The untracked `FilesMaxSizeConfigWebTests.kt` that the review mentions does not exist in the working tree.

Fix:
Added `FilesMaxSizeWebTests` with `@TestPropertySource(properties = ["cockpit.files.max-size=1KB"])`. It checks:
- a multipart upload of 1025 bytes -> 413, `application/problem+json`, the detail names the 1024-byte limit, and no file row is stored; exactly 1024 bytes -> 201;
- `POST /uploads` declaring 1025 bytes -> 400 with field error `size`; 1024 -> 201;
- `GET /drive/usage` reports `maxFileSize` 1024, and `spring.servlet.multipart.max-file-size` resolves to `1KB` (the placeholder wiring to `cockpit.files.max-size`).

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*' ktlintCheck`: Pass (FilesMaxSizeWebTests 3/3)

### CR-3 (P3) - A chunk PUT that races complete or abort leaves an orphaned chunk directory

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/UploadService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/files/UploadServiceTests.kt`

Problem:
`putChunk` checked that the session exists only before writing. If complete or abort committed and deleted the chunk directory during the write, the chunk was recreated on disk and nothing ever removed it. If the cleanup deleted the temporary file mid-write, the request failed with an unhandled `IOException` (a 500).

Fix:
After the chunk is written, `putChunk` checks again that the session exists (`sessions.existsById`). If the session is gone, it deletes the upload's chunks and throws `NotFoundException` (404 "Upload not found"). If the write itself fails with an `IOException` and the session is gone, it does the same; if the session still exists, the `IOException` is rethrown as before. If complete is still running when the re-check happens, its own after-commit `deleteChunks` removes the chunk, so this case is covered too.

Two regression tests were added:
- the session row is deleted at end-of-stream, after the bytes are read but before the move into place: 404, and no chunk directory is left;
- a real `uploads.abort` runs at end-of-stream and deletes the temporary file: 404 instead of a 500, and no chunk directory is left.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*' ktlintCheck`: Pass (UploadServiceTests 7/7)

### CR-4 (P3) - Unused production helper and logic that duplicates older methods

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/files/api/FilesWebSupport.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/files/FilesApiSupportTests.kt`

Problem:
No production code called `ContentDispositions.inline`. `FilesService.renameFolder` and `renameFile` duplicated `updateFolder` and `updateFile` and had already drifted from them: `renameFolder` had no version check hook and `renameFile` did not flush.

Fix:
- Removed `ContentDispositions.inline` and its test. `attachment` now builds the header itself, and its output is unchanged (the existing attachment tests still pass).
- `renameFolder(id, name)` now delegates to `updateFolder(id, name, null)`, and `renameFile(id, name)` delegates to `updateFile(id, name, null)`. Only one code path is left. The existing test callers (FilesServiceTests, FilesEdgeCaseTests, FilesConcurrencyTests) keep working without changes, so no tests had to move. One observable difference: renaming a special folder through `renameFolder` now fails with the message "Special folders cannot be renamed or moved", the same message as PATCH. No test asserts on the old message.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*' ktlintCheck`: Pass (FilesServiceTests 24/24, FilesEdgeCaseTests 23/23, FilesConcurrencyTests 14/14, FilesApiSupportTests 11/11)

### CR2-1 (P2, second-round review CR-1) - The OpenAPI contract does not allow `null` for `parentId`/`folderId`

Fixed in commit 133a5b3d (previous worker); documented here from that commit and the review history.

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/files/api/FilesDtos.kt`
- `api/openapi.yaml` (regenerated)
- `web/src/app/api/generated/models/create-folder-request.ts`, `create-upload-request.ts`, `file-patch-request.ts`, `folder-patch-request.ts` (regenerated)
- `server/src/test/kotlin/ch/sonensei/cockpit/files/FilesApiEdgeCaseWebTests.kt`

Problem:
The server treats `"parentId": null` (folders) and `"folderId": null` (files, uploads) as "the drive root", but the generated OpenAPI 3.1 schema typed these fields as plain `type: string, format: uuid`. Generated clients (`parentId?: string`) could therefore not send `null` to move an item to the drive root, and the schema contradicted its own description.

Fix:
`FolderPatchRequest.parentId`, `FilePatchRequest.folderId`, `CreateFolderRequest.parentId` and `CreateUploadRequest.folderId` are annotated with `@get:Schema(types = ["string", "null"], format = "uuid")`, the pattern already used in `gtd/api/ProjectDtos.kt`. The contract and the web client were regenerated. A new web test reads `/v3/api-docs` and asserts that each of the four fields has type `["string", "null"]` and format `uuid`. The review's optional extension to nullable response fields was not taken up.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*' ktlintCheck`: Pass (re-run 2026-10-21 on the current code, see TW-1)

### CR2-2 (P3, second-round review CR-2) - `listFolder` and `getUpload` documented their 200 body under `application/problem+json`

Fixed in commit 133a5b3d (previous worker); documented here from that commit and the review history.

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/files/api/DriveController.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/files/api/UploadController.kt`
- `api/openapi.yaml` (regenerated)
- `web/src/app/api/generated/fn/files/list-folder.ts`, `get-upload.ts` (regenerated)
- `server/src/test/kotlin/ch/sonensei/cockpit/files/FilesApiEdgeCaseWebTests.kt`

Problem:
Both endpoints declare `produces = [application/json, application/problem+json]` and had a 200 `@ApiResponse` without `content`, so springdoc listed the success body under both media types and ng-openapi-gen generated clients that request success responses with `Accept: application/problem+json`.

Fix:
The 200 responses now declare their content explicitly as `application/json` with `DrivePageResponse` and `UploadResponse`. The contract and web client were regenerated (the problem+json 200 entries are gone). A new web test asserts that the 200 content of both operations lists only `application/json`.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*' ktlintCheck`: Pass (re-run 2026-10-21 on the current code, see TW-1)

### TW-1 (P3) - Unnecessary non-null assertion in `DriveQueries.totals`

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/files/infra/DriveQueries.kt`

Problem:
The Kotlin compiler warned "Unnecessary non-null assertion (!!) on a non-null receiver of type `DriveQueries.Totals`" at `DriveQueries.kt:64`: the `queryForObject` call with a row-mapper lambda already returns the non-null `Totals`. AGENTS.md requires fixing warnings rather than leaving them.

Fix:
Removed the trailing `!!`. Behavior is unchanged (`queryForObject` throws when there is no row; the aggregate query always returns exactly one).

Validation:
- `cd server && ./gradlew compileKotlin --rerun-tasks`: Pass; no warning is reported for `DriveQueries.kt` any more. The three remaining main-source warnings are in `core/oauth` and `mail/sync`, outside this task.
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*' ktlintCheck`: Pass (178 tests in 12 classes, 0 failures, 0 errors, 0 skipped)

## Not fixed

None.

## Validation summary

First round (2026-10-20):

Focused tests:
- `cd server && ./gradlew ktlintFormat`: no further changes
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*' ktlintCheck`: BUILD SUCCESSFUL. 168 tests in 12 classes, 0 failures, 0 errors (Postgres via Testcontainers).
- `detekt` is not a task of this project (Gradle: "Task 'detekt' not found"), so it was not run.

Full test suite:
- `cd server && ./gradlew check`: FAIL after 3m29s with `OutOfMemoryError: Java heap space` in Gradle Test Executor 22. This is the same problem the task notes already record from before these fixes ("server :test 'Java heap space' after 4m34s"; likely cause: the 60 MB chunked upload and large multipart tests holding byte arrays in MockMvc). It is not caused by CR-1 to CR-4 and is not addressed here. Note: the new `FilesMaxSizeWebTests` uses its own property set, so it adds one more cached Spring context to the test JVM.

Lint/type-check/build:
- `ktlintCheck` (main and test sources): Pass. Compilation of main and test sources: Pass.

`.taskcheck`:
- Not run. It runs `./gradlew check` for the server, which fails with the heap problem described above.

TW-1 round (2026-10-21):
- `cd server && ./gradlew compileKotlin --rerun-tasks`: Pass, no warning left in the files module.
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*' ktlintCheck`: BUILD SUCCESSFUL, 178 tests in 12 classes, 0 failures, 0 errors, 0 skipped.
- Full `./gradlew check` and `.taskcheck`: not re-run in this round; the change only removes a redundant `!!` in one file and is covered by the files suite above.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed. All seven findings (CR-1 to CR-4, CR2-1, CR2-2, TW-1) are fixed, and the focused validation of the files module passes on the current code (178 tests, ktlint clean, no compiler warning in the files module). The full `./gradlew check` and `.taskcheck` were not re-run by the fixer in the TW-1 round; in the first round, `./gradlew check` failed only with the out-of-memory error the task notes already recorded before these fixes, not with a test failure caused by them. The parent worker runs `.taskcheck` before completing the task.
