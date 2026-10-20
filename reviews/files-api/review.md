# Code Review

Date: 2026-10-20

Task: files-api - Files API: upload, download, operations

Scope: `git diff 6e420c3ff144629e2215cb3b3aa86785f6d178a1..4e2decc5`, excluding `tasks/` and `reviews/`, as given by the parent worker. This is the final revision after the rebase onto main. Committed changes only. The working tree has uncommitted changes only in `reviews/files-api/` (review.md, tests.md), which are outside the reviewed code. All changed production sources, the configuration, the migration and the build file were read in full. Test names and selected test bodies were inspected. The generated contract (`api/openapi.yaml`) and web client were spot-checked against the controllers. The reviewer did not run any build or test command. The parent reports 175 passing focused tests.

Requirements:
- `tasks/files-api.md`: acceptance criteria 1-5 and the ASSUMPTION notes (API layout, resumable-upload rules, max size, downloads, folder moves)
- SPEC.md §8.2 and §4.2 (as reflected in the task), `docs/ARCHITECTURE.md`, AGENTS.md project notes (module packages, Flyway location, Problem Details, ETag/If-Match, generated files)
- Earlier `reviews/files-api/review.md`, `fixes.md` and `tests.md`

Git range:
- Branch: main
- HEAD: 4e2decc5 (reviewed revision)
- Base/start: 6e420c3ff144629e2215cb3b3aa86785f6d178a1 (origin/main)
- Commits reviewed: 3 (bc1c124c, b491adfd, 4e2decc5)

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## Verification against the acceptance criteria

- AC1: Multipart upload is in `FileController.upload`. The chunked flow is in `UploadController`/`UploadService`/`ChunkStore`: `POST /uploads`, `PUT /uploads/{id}/chunks/{n}` with exact lengths and atomic moves, and `POST /uploads/{id}/complete`. Complete runs under a row lock, checks size and SHA-256, and rolls the blob back on a mismatch. The max size comes from `cockpit.files.max-size` (default 5GB). Spring's multipart limit follows it, and the 413 response is tested. Met in code.
- AC2: Download uses `ResponseEntity<Resource>` and Spring's region support (206, multipart byteranges, 416). `ContentDispositions.attachment` produces the ASCII fallback plus `filename*=UTF-8''`. The folder zip is a `StreamingResponseBody` with `spring.mvc.async.request-timeout=-1`. `FileNames.validate` forbids `/`, `\`, `.` and `..`, so zip paths cannot escape. Met in code.
- AC3: Rename and move go through `updateFile`/`updateFolder`. Moves into the folder's own subtree are rejected under `pg_advisory_xact_lock`, and a concurrency test covers it. Delete goes to the Trash with If-Match (412/428). New folder, the cursor listing (keyset on kind, lower(name), id) and `/drive/usage` are present. Met in code.
- AC4: The suite includes `a 60 MB file uploads in chunks and downloads identical` and `an upload resumes after a missing chunk`, plus `@SpringBootTest` persistence tests. The reviewer did not re-run them.
- AC5: `api/openapi.yaml` has the files operations, and the `Range` parameter now has a string schema (`openapi.yaml:1676-1678`). The reviewer did not re-run `OpenApiContractTest`.
- Earlier findings: TW-1 is resolved (Range schema). Fixes CR-1 to CR-4 from `fixes.md` are still in place. The earlier P3 (problem+json Accept) is still open, see CR-2.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | The OpenAPI contract does not allow `null` for `parentId`/`folderId` in the PATCH bodies, so generated clients cannot move an item to the drive root**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/api/FilesDtos.kt:150-160` (contract: `api/openapi.yaml:5545-5555`, `5596-5606`; client: `web/src/app/api/generated/models/folder-patch-request.ts`)
    - Category: Integration
    - Problem: The server treats `"parentId": null` (folders) and `"folderId": null` (files) as "move to the drive root". `parseFilesPatch` does this, and `FilesWebTests.kt:319,331` test it. But `FolderPatchRequest.parentId` and `FilePatchRequest.folderId` have no `types = ["string", "null"]`. The generated OpenAPI 3.1 schema is therefore plain `type: string, format: uuid`, and the generated TypeScript model is `parentId?: string`. In that contract, `null` is an invalid value: strict TS clients and Android generators cannot send it without a cast or hand-written JSON. The project already uses `types = ["string", "null"]` for this purpose in PATCH DTOs (`gtd/api/ProjectDtos.kt:96`, `areaId`; `core/settings/api/SettingsSchemas.kt`). The schema description says `null` is allowed, which contradicts the schema itself.
    - Why it matters: Moving to the root is part of AC3 ("move"). The documented API (AC5, SPEC §4.2 "API changes: server first, regenerate, then clients") does not express it, so the web and Android files features will hit this as soon as they implement "move to root".
    - Suggested fix: Annotate `FolderPatchRequest.parentId` and `FilePatchRequest.folderId` with `@get:Schema(types = ["string", "null"], format = "uuid")`, as `ProjectDtos.kt:96` does. Then regenerate `api/openapi.yaml` (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and the web client (`npm run api:gen`). Do the same for the nullable response fields that are always present (`FolderResponse.parentId`, `FileResponse.folderId`, `UploadResponse.folderId`, `DrivePageResponse.nextCursor`, `special`) if the project wants response schemas to validate the `null` values the server actually sends.

## P3 Low

- [ ] **CR-2 | `listFolder` and `getUpload` document their 200 body under `application/problem+json`, so the generated client requests success responses with `Accept: application/problem+json` (carried over, still open)**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/api/DriveController.kt:31`, `server/src/main/kotlin/ch/sonensei/cockpit/files/api/UploadController.kt:68`
    - Category: Integration
    - Problem: Both methods declare `produces = [APPLICATION_JSON_VALUE, PROBLEM_JSON]` and a 200 `@ApiResponse` without `content`. springdoc lists the return type under both media types, and ng-openapi-gen picks the problem type: `web/src/app/api/generated/fn/files/list-folder.ts:39` and `get-upload.ts:23` send `accept: 'application/problem+json'`. By contrast, `get-file.ts:23` sends `application/json`, because `FileOkResponse` declares the content explicitly. The same pattern exists elsewhere in the codebase, so it is not unique to this task.
    - Why it matters: Successful listings and upload statuses can come back labelled `Content-Type: application/problem+json`. Clients that treat that type as an error can misread them. It is also inconsistent with the other files endpoints.
    - Suggested fix: Declare the 200 content explicitly: `content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = DrivePageResponse::class))]`, and the same with `UploadResponse::class`. Then regenerate the contract and the web client. This can be done together with CR-1.

## Review conclusion

Material issues found; fix before completing the task.

The functional behavior matches the acceptance criteria and is well tested, including races between chunk writes and complete/abort, crossing folder moves, moves into a folder being trashed, and rollback of the blob on a hash mismatch. No security, data-loss or correctness defects were found. The remaining item that matters is CR-1: the published API contract does not allow `null` for the move-to-root fields that the server requires. Fixing it means a small annotation change plus regenerating the contract and the web client. CR-2 is optional cleanup that fits into the same regeneration.
