# Code Review

Date: 2026-10-21

Task: files-api - Files API: upload, download, operations

Scope: `git diff c3b429ed64eceb70609b59999406373f0e3248fd..fa633bff`, excluding `tasks/` and `reviews/`. Reviewed revision: fa633bff (merge of `origin/wip/files-api` = 8184c4d6 into c3b429ed). Committed changes only. The working tree was clean at the start of the review.

How the scope was reviewed:
- The merge is clean for the task's code: `git diff 5e0b966c 8184c4d6` (merge-base of the two merge parents to the task tip) and `git diff c3b429ed fa633bff`, both excluding `tasks/` and `reviews/`, are byte-identical (same SHA-1 of the diff text). So fa633bff contains exactly the task's code changes and nothing from the merge itself.
- The previous review covered a2eb6da6. `git diff e035eaf7 a2eb6da6` and `git diff 5e0b966c 133a5b3d` (both excluding `tasks/` and `reviews/`) have the same `git patch-id`. So 133a5b3d is the same code as the reviewed a2eb6da6, and its conclusions (full review of all production sources, configuration, migration, build file, OpenAPI contract and generated web client, plus the CR-1/CR-2 verification) carry over.
- The only code change since then is commit 8184c4d6, which adds two tests to `FilesApiEdgeCaseWebTests.kt` (no production change). It was read in full, and the helpers it uses (`postJson`, `newFolder`, `createUpload`, `DRIVE`, `UPLOADS`, `UNKNOWN`) were confirmed to exist in the same file.
- The final revision was also re-read independently in its central parts: `UploadService.kt`, `ChunkStore.kt`, `FileController.kt`, `FilesWebSupport.kt` and `DriveService.kt`. Checked: size and SHA-256 check on complete with rollback of the stored blob, chunk length enforcement and atomic chunk writes, cleanup of chunks when a write races with complete/abort, lazy expiry of sessions, the RFC 6266 `filename*` encoding (`+` and `*` re-encoded), the PATCH parsing where an explicit `null` means the drive root, cursor parsing and validation, and the zip entries (empty directories, case-insensitive numbering of duplicates, Trash excluded).
- Commands run, all read-only: `git log`, `git show`, `git diff`, `git patch-id`, `sha1sum`, `grep`. No Gradle or npm command was run, because a test-writer was running in the same clone at the same time. Test results come from the evidence recorded in `tasks/files-api.md`.

Requirements:
- `tasks/files-api.md`: acceptance criteria 1-5 and the ASSUMPTION notes
- SPEC.md §8.2 and §4.2 (as reflected in the task), AGENTS.md project notes, `docs/ARCHITECTURE.md`
- Previous `reviews/files-api/review.md` (reviewed a2eb6da6, 0 findings)

Git range:
- Branch: main
- HEAD: fa633bff (Merge remote-tracking branch 'origin/wip/files-api')
- Base/start: c3b429ed64eceb70609b59999406373f0e3248fd (merge-base with origin/main)
- Commits reviewed: 7 (573bc24d, c27b5850, e48373ca, 01aa873f [reports only], 133a5b3d, 8184c4d6, merge fa633bff)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Changes since the previous review

- `an explicit null folder id on create means the drive root, as the contract allows`: sends `"parentId": null` to `POST /folders` and `"folderId": null` to `POST /uploads`, and checks that both end up at the root. This pins the nullable contract from CR-1 for the create requests too, not only for PATCH. The test asserts behavior (status, `parentId`, `path`, `folderId`), not implementation details.
- `listFolder and getUpload answer a client that accepts only application json, also with errors`: sends `Accept: application/json`, like the regenerated web client does, and checks 200 with a JSON body and 404 with a Problem Details body (`status` 404). This covers the risk that came with CR-2: an error must not become 406 when the client accepts only `application/json`. The test is meaningful and correctly scoped.

## Verification against the acceptance criteria

- AC1 (multipart and resumable upload, size and sha256 check, configurable max size): met in code. No production change since the previous full review.
- AC2 (Range, RFC 6266 Content-Disposition, streamed folder zip): met in code. No change.
- AC3 (rename, move without cycles, delete to Trash, new folder, cursor listing, usage): met in code. No change.
- AC4 (focused test suite incl. resume after a missing chunk and a 60 MB chunked upload): the tests exist. The recorded evidence names the command and the result (175 tests, 0 failures). That run predates the two tests added in 8184c4d6. The reviewer did not re-run the suite. The parent should make sure the final evidence (test-writer run or `.taskcheck`) includes 8184c4d6.
- AC5 (OpenAPI regenerated, `OpenApiContractTest` passes): 8184c4d6 changes no annotations, so `api/openapi.yaml` and the generated web client still match the code of 133a5b3d, where they were checked. The reviewer did not re-run the contract test.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.

The task code at fa633bff is the same as the reviewed a2eb6da6 plus two well-targeted tests. The independent re-read of the central upload, download, PATCH and listing code found no correctness, security, integration or data-loss defects.
