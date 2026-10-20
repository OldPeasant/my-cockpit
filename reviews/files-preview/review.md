# Code Review

Date: 2026-10-21

Task: files-preview - Thumbnails and previews

Scope: task commits from base 5f362a12ebf152e0d96f83f17af8bbb4939fa79d (`git merge-base HEAD origin/main`) to HEAD f12a5000: implementation, extra tests, the fixes for CR-1..CR-3 of the previous review, the regenerated web API client and the reports. This is a re-review of the final code.

Requirements:
- tasks/files-preview.md acceptance criteria and its recorded assumptions
- SPEC.md §8.2 (Files), §4.2 (API conventions)
- docs/ARCHITECTURE.md (stack table, module conventions)
- AGENTS.md project notes (Problem Details, generated `api/openapi.yaml` and web client, logging)
- Previous review, fixes.md and tests.md in reviews/files-preview/

Git range:
- Branch: main
- HEAD: f12a50005478ebe233110638cd714d207081deef
- Base/start: 5f362a12ebf152e0d96f83f17af8bbb4939fa79d
- Commits reviewed: 5 (7b2230d5, 597198f3, 2ca34eca, 8b6f2cd0, f12a5000)
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Status of the earlier findings

- CR-1 (an unparseable stored audio/video mime type caused a 500): resolved. `PreviewService.isValidMime` (`PreviewService.kt:118-124`) parses the mime with `MimeTypeUtils` and rejects wildcards, so such files now get a 415 Problem Details response. The test `audio and video files with a malformed stored mime type have no preview instead of a server error` covers `video/`, `audio/mpeg x` and `audio/*`.
- CR-2 (no test for the 100 MP guard): resolved. `PreviewEdgeCaseWebTests` `an image header claiming more than 100 megapixels is rejected with 422` asserts the 422 problem+json response and its detail, that no thumbnail was cached and that `thumbnails/tmp` is empty. `ThumbnailStoreTests` also covers the limit, a size just below it and Long overflow.
- CR-3 (a thumbnail rendered during a purge stayed orphaned): resolved. After the atomic move, `ThumbnailStore.thumbnail` (`ThumbnailStore.kt:68-72`) deletes the target when the source blob is gone. `deleteBlob` removes the blob before the thumbnails, so whichever order the two threads run in, the cached file is removed either by `delete()` or by this check. The `init` block clears `thumbnails/tmp` at startup, and a test covers it. The race itself has no deterministic test, which is acceptable given its size. The side effect of the new exception is assessed in CR-1 below.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A purge during a thumbnail or preview request answers 500 with an ERROR log instead of 404**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/infra/ThumbnailStore.kt:71` (and `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/PreviewService.kt:94` for text previews)
    - Category: Correctness
    - Problem: the CR-3 fix throws `java.nio.file.NoSuchFileException` when the blob disappears during a render. No handler in `PreviewController` or `ProblemDetailsAdvice` maps it, so `ProblemDetailsAdvice.handleUnexpected` turns it into a 500 and logs it at ERROR. The same race already led to an unmapped exception on other paths: `blobs.open` in the text preview, and `ImageIO.createImageInputStream` in `dimensions()`, which wraps the missing file in an `IIOException` outside its try block. The window is narrow: the file must be loaded as active, and then it must be purged from the Trash before its blob is read, which in a single-user app takes deliberate concurrent action. No data is lost and the cache stays clean.
    - Why it matters: the client receives a server error and the log reports a fault, but the true outcome is "this file no longer exists". The fix is cheap. The rare frequency is why this is rated P3 and does not block the task.
    - Suggested fix: add `@ExceptionHandler(NoSuchFileException::class)` to `PreviewController` that answers 404 Problem Details, as for a missing file. A test can call `ThumbnailStore.thumbnail` or `PreviewService.preview` on a row whose blob was deleted directly.

- [ ] **CR-2 | The thumbnail `size` query parameter is documented as a string enum, although it is an integer**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/api/PreviewController.kt:75` (output in `api/openapi.yaml`, `/api/v1/files/{id}/thumbnail`, and in `web/src/app/api/generated/fn/files/get-file-thumbnail-*.ts`)
    - Category: Integration
    - Problem: `Schema(allowableValues = ["256", "1024"])` without a type makes springdoc emit `type: string, enum: ["256", "1024"]` with no `default`. The generated web client types the parameter as `'256' | '1024'`, and the Android generator will produce a string enum. The controller binds an `Int` that defaults to 256. Requests still work, because query values are strings on the wire, but the contract misstates the type and drops the default.
    - Why it matters: generated clients get a less accurate model, and the documented default (256) is visible only in the description text.
    - Suggested fix: use `Schema(type = "integer", allowableValues = ["256", "1024"], defaultValue = "256")`. Then regenerate `api/openapi.yaml` and the web client.

## Notes (not findings)

- Security of inline serving was checked again. Only PDF, `audio/*`, `video/*` (now with a parseable concrete mime) and the four raster image types are served inline. Text, including HTML, is served as `text/plain` or `text/markdown` with UTF-8. SVG is excluded. Spring Security's default `nosniff` header stays active, and the filename goes through the existing `ContentDispositions` escaping. Thumbnail paths come only from storage keys validated by `BlobStore.isValidKey` and a whitelisted size.
- The `utf8Boundary` logic was re-checked by hand for 2/3/4-byte sequences and for stray continuation bytes. It is correct, and `PreviewTextTests` covers it.
- `api/openapi.yaml` and `web/src/app/api/generated/` are consistent with each other. tests.md item TW-1 (client drift) is addressed by commit f12a5000. Editing the generated web client outside `touches` is recorded as an assumption.
- The scope hashes in tests.md and fixes.md (for example 8aaa677c, 054a2d82) refer to commits from before a rebase. The code they describe matches the current HEAD.
- `python tasks.py verify files-preview` still shows criteria 1-4 without evidence. Nothing is claimed yet, so this is not a finding, but before `done` the parent worker must record evidence for the Testcontainers run (criterion 3) and for `OpenApiContractTest` passing without the flag (criterion 4). The reviewer did not run Gradle.

## Review conclusion

No material issues found. CR-1 and CR-2 are optional P3 improvements.
