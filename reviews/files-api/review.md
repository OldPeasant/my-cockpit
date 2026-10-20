# Code Review

Date: 2026-10-21

Task: files-api - Files API: upload, download, operations

Scope: `git diff e035eaf772ac5e844d8a1ff78df444e59bc6d6ed..a2eb6da697b7ad482b2f06809727b25222c1a95f`, excluding `tasks/` and `reviews/`. This covers the whole task at its final revision. Committed changes only. The working tree was clean when the review started.

How the scope was reviewed:
- `git diff 6e420c3f 4e2decc5` (the revision covered by the previous full review) was compared with `git diff e035eaf7 0ab7100a` (the same commits after the rebase), both excluding `tasks/` and `reviews/`. The two diffs are byte-identical (6061 lines each). So the only code change since the previous full review of all production sources, the configuration, the migration and the build file is commit a2eb6da6.
- a2eb6da6 was read in full: `FilesDtos.kt`, `DriveController.kt`, `UploadController.kt`, `FilesApiEdgeCaseWebTests.kt`, `api/openapi.yaml` and the regenerated web client files. `FilesWebSupport.parseFilesPatch` was re-read to confirm the null semantics the contract now documents. Every `accept:` value in `web/src/app/api/generated/fn/files/` was checked.
- Commands run, all read-only: `git log`, `git show`, `git diff`, `git status`, `grep`. The reviewer did not run any Gradle or npm command, because a test-writer was running at the same time in the same clone. Test results come from the evidence recorded in `tasks/files-api.md`: 175 focused tests passed, `OpenApiContractTest` passed, and `sh scripts/check.sh` passed.

Requirements:
- `tasks/files-api.md`: acceptance criteria 1-5 and the ASSUMPTION notes
- SPEC.md §8.2 and §4.2 (as reflected in the task), `docs/ARCHITECTURE.md`, AGENTS.md project notes
- Previous `reviews/files-api/review.md` (CR-1, CR-2)

Git range:
- Branch: main
- HEAD: a2eb6da697b7ad482b2f06809727b25222c1a95f
- Base/start: e035eaf772ac5e844d8a1ff78df444e59bc6d6ed (merge-base with origin/main)
- Commits reviewed: 5 (95ed455b, 329cdcb9, 6fc159e7, 0ab7100a [reports only], a2eb6da6)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Verification of previous findings

- CR-1 (P2, `null` not allowed for parentId/folderId in the contract): **resolved.** In `FilesDtos.kt`, `FolderPatchRequest.parentId`, `FilePatchRequest.folderId`, `CreateFolderRequest.parentId` and `CreateUploadRequest.folderId` now have `@get:Schema(types = ["string", "null"], format = "uuid")`. This is the pattern `gtd/api/TaskDtos.kt` uses. `api/openapi.yaml` now emits `type: [string, "null"], format: uuid` for all four fields. The generated web models are `(string | null)`. `parseFilesPatch` treats an explicit `null` as "move to the drive root" and an absent field as "no move", which matches the schema descriptions. Android generates its client from the same `api/openapi.yaml`, and the contract already used `"null"` types in other places (35 occurrences), so the generator can handle them. A regression test pins all four fields: `the OpenAPI contract allows null for the folder fields that mean the drive root`. The optional extension from the previous review, nullable response fields, was not done. That is acceptable: other modules do not annotate nullable response fields either.
- CR-2 (P3, 200 bodies listed under `application/problem+json`): **resolved.** `DriveController.list` and `UploadController.get` now declare an explicit `application/json` 200 content. The `application/problem+json` 200 entries are gone from `api/openapi.yaml`. `list-folder.ts` and `get-upload.ts` now send `accept: 'application/json'`. No generated files operation still requests `application/problem+json`. A regression test pins this: `listFolder and getUpload document their 200 body as application json only`.

## Verification against the acceptance criteria

- AC1 (multipart and resumable upload, size and sha256 check, configurable max size): met in code. Unchanged since the previous full review.
- AC2 (Range, RFC 6266 Content-Disposition, streamed folder zip): met in code. Unchanged.
- AC3 (rename, move without cycles, delete to Trash, new folder, cursor listing, usage): met in code. The contract now also documents move-to-root correctly (CR-1).
- AC4 (focused test suite incl. resume and the 60 MB upload): the tests are present. The recorded evidence names the command and the result (175 passed). The reviewer did not re-run them.
- AC5 (OpenAPI regenerated, `OpenApiContractTest` passes): `api/openapi.yaml` agrees with the annotations in a2eb6da6, and the web client was regenerated from it. The reviewer did not re-run the contract test. The parent should confirm that the recorded AC5 evidence was produced on a2eb6da6 or later.

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

CR-1 and CR-2 from the previous review are resolved and covered by regression tests. The rest of the task code is unchanged from the revision that had a full review, which found no correctness, security or data-loss defects.
