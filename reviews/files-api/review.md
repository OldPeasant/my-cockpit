# Code Review

Date: 2026-10-20

Task: files-api - Files API: upload, download, operations

Scope: committed range `857df5c0..30203425` (`git diff 857df5c0c9ec96e03a1d04f9299ae2a0f01a92db..30203425`), excluding `tasks/`. Committed changes only; the working tree was clean when checked. This review replaces the earlier review of the pre-fix code and checks the fixer's changes for the earlier CR-1 to CR-4 (`reviews/files-api/fixes.md`).

Requirements:
- tasks/files-api.md acceptance criteria and the recorded assumptions (Notes)
- SPEC.md §8.2 (Files), §4.2 API conventions
- docs/ARCHITECTURE.md, AGENTS.md project notes (module boundaries, Flyway layout, Problem Details, ETag/If-Match, "API changes: server first, regenerate api/openapi.yaml, then clients")
- api/README.md (regenerating the contract and the clients), scripts/check.sh (`.taskcheck`)

Git range:
- Branch: main
- HEAD: 302034257d75fe191de4dfdaeb47aeb540aed60b
- Base/start: 857df5c0c9ec96e03a1d04f9299ae2a0f01a92db
- Commits reviewed: 6 (0534a5fd, 7e761d77, 89b447dc, aaa1b6fd, 1d26c431, 30203425)

Summary: 0 P0, 1 P1, 0 P2, 0 P3

## Verification of earlier findings

- CR-1 (zip async timeout): fixed. `application.properties` sets `spring.mvc.async.request-timeout=-1`. The setting is passed to the servlet `AsyncContext`, where a value of 0 or less means no timeout. `FilesWebTests` "a folder downloads as a streamed zip" checks `asyncContext.timeout == -1`. SSE emitters set their own timeout, so the global setting does not affect them.
- CR-2 (configured max size not tested): fixed. `FilesMaxSizeWebTests` uses `@TestPropertySource(cockpit.files.max-size=1KB)` and checks four things: multipart 1025 bytes -> 413 Problem Details with no row stored, 1024 bytes -> 201, `POST /uploads` above the limit -> 400 `size`, and that `spring.servlet.multipart.max-file-size` resolves to `1KB`. MockMvc does not enforce the container multipart limit, so the 413 here comes from `BlobTooLargeException`. The property-resolution assertion covers the container wiring. This is adequate.
- CR-3 (orphaned chunk after a race): fixed. `UploadService.putChunk` (`UploadService.kt:108-123`) checks again that the session exists after the write. If the session is gone, it deletes the chunks and returns 404. On an `IOException` it does the same when the session is gone. If `complete` is still holding the row when the check runs, `complete`'s own after-commit cleanup removes the chunk. Two regression tests in `UploadServiceTests` cover the deleted-row case and the real-abort case.
- CR-4 (unused helper, duplicated rename logic): fixed. `ContentDispositions.inline` is removed. `renameFolder` and `renameFile` now delegate to `updateFolder` and `updateFile` (`FilesService.kt:98-101`, `206-209`).
- `server/build.gradle.kts`: the test JVM now gets `maxHeapSize = "1g"`. This is an intentional, commented, one-line change to a shared build file. The reviewer did not run Gradle (as instructed), so this review does not confirm that `./gradlew check` now passes, or how long it takes.

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | `api/openapi.yaml` was regenerated, but the generated web client was not, so `.taskcheck` fails at `npm run api:check`**
    - File: `api/openapi.yaml:1` (the paths `/api/v1/drive`, `/api/v1/files`, `/api/v1/folders`, `/api/v1/uploads` and 11 new schemas were added in this range). The counterpart that is missing is `web/src/app/api/generated/`.
    - Category: Integration
    - Problem: The range adds about 1100 lines to `api/openapi.yaml`: 13 operations tagged `Files` and the schemas `CompleteUploadRequest`, `CreateFolderRequest`, `CreateUploadRequest`, `DriveEntryResponse`, `DrivePageResponse`, `DriveUsageResponse`, `FilePatchRequest`, `FileResponse`, `FolderPatchRequest`, `FolderResponse`, `FolderSummary` and `UploadResponse`. No file under `web/` changed (`git diff --stat 857df5c0..30203425 -- web` is empty). At HEAD, `web/src/app/api/generated/fn/` has no `files/` folder and no files/upload/drive models. `web/ng-openapi-gen.json` generates every tag (no include or exclude filter). `web/scripts/api-gen-check.mjs`, which runs as part of `npm run check`, regenerates the client from `../api/openapi.yaml` and fails on any missing or changed file. `scripts/check.sh` (`.taskcheck`) runs `npm run -s check` for the web part, so the project check fails on this HEAD.
    - Why it matters: AGENTS.md requires "server first, regenerate `api/openapi.yaml`, then clients", and api/README.md says to regenerate the web client after the contract changes. `tasks.py done` runs `.taskcheck` and will refuse the task. The web client also stays out of sync with the server contract that other tasks build on.
    - Suggested fix: Run `cd web && npm run api:gen` and commit the regenerated `web/src/app/api/generated/` together with the contract. Then run `npm run check` (or the full `sh scripts/check.sh`) and record the result. This is a generated-file change outside the task's `touches`, as other API tasks did (e.g. cal-local-api, gtd-projects-api). It is not hand-written web code.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

Material issues found; fix before completing the task.

The server implementation covers the functional criteria:
- multipart upload, and resumable uploads with size and SHA-256 checks on complete, a missing-chunks 409, and a configurable maximum size;
- downloads with `Range` (through Spring resource regions), an RFC 6266 `filename*` header with an ASCII fallback, and a streamed folder zip with no async timeout;
- rename and move with cycle prevention under an advisory lock, delete to the Trash with If-Match, new folder, cursor listing and disk usage;
- consistent Problem Details and ETag handling.

The earlier CR-1 to CR-4 are resolved. The remaining blocker is the stale generated web client, which makes `.taskcheck` fail.

Before `done`, the parent worker must also run and record the evidence the criteria name, because the reviewer did not run Gradle:
- criterion 4: `./gradlew test --tests 'ch.sonensei.cockpit.files.*'`, with Testcontainers, including the 60 MB chunked upload test and the missing-chunk resume test;
- criterion 5: `OpenApiContractTest` without the update flag;
- the full `.taskcheck`. The task notes ask for it to stay under about 2 minutes now that the 60 MB test runs inside it with the 1 GB heap.

No criterion of `tasks/files-api.md` was ticked at review time.
