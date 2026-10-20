# Code Review

Date: 2026-10-21

Task: files-preview - Thumbnails and previews

Scope: task commits from base e350066c72369020367073624e9272f57b85584f to HEAD (`git diff e350066c..HEAD`), implementation commit 2271fe1e plus the `assume:` commits on `tasks/files-preview.md`

Requirements:
- tasks/files-preview.md acceptance criteria and recorded assumptions
- SPEC.md §8.2 (Files), §4.2 (API conventions), security headers note (§ security)
- docs/ARCHITECTURE.md (stack table, module conventions)
- AGENTS.md project notes (Problem Details, generated openapi.yaml, logging, test data)

Git range:
- Branch: main
- HEAD: 2271fe1e
- Base/start: e350066c72369020367073624e9272f57b85584f
- Commits reviewed: 5 (1 implementation commit, 4 assumption commits)
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 2 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Inline preview answers 500 for a stored mime type that Spring cannot parse**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/api/PreviewController.kt:145`
    - Category: Correctness
    - Problem: `Preview.Inline` calls `MediaType.parseMediaType(preview.mime)` without a guard. The mime comes from the client at upload time and is stored unvalidated (`FilesService.storeFile` only checks `ifBlank`, `UploadService.create` only trims). `PreviewService.preview` accepts any value starting with `audio/` or `video/`, so values like `video/` or `audio/mpeg x` reach `parseMediaType`. It throws `InvalidMediaTypeException`, which only the generic `ProblemDetailsAdvice.handleUnexpected` catches: the result is a 500 and an ERROR log entry. The existing download endpoint guards this case (`FileController.mediaType`, falling back to `application/octet-stream`), so preview is inconsistent with it.
    - Why it matters: a file with slightly malformed metadata breaks its preview with a server error instead of a clear client-facing answer. The ERROR log also hides real failures.
    - Suggested fix: parse the media type in `PreviewService.preview` (or reuse a shared helper like `FileController.mediaType`). When parsing fails, throw `PreviewNotSupportedException` (415). Add a test that uploads with such a mime.

- [ ] **CR-2 | The decompression-bomb guard (MAX_PIXELS -> 422) is not tested**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/infra/ThumbnailStore.kt:110` (test: `server/src/test/kotlin/ch/sonensei/cockpit/files/PreviewWebTests.kt:154`)
    - Category: Tests
    - Problem: the assumption on record says images above 100 MP are rejected with 422, and `dimensions()` puts this into code by reading only the header. No test covers it. `thumbnail rejects other sizes, non-images and undecodable images` covers only non-image bytes. A tiny file can trigger the guard: a PNG whose IHDR declares something like 20000x20000, with a valid CRC and a minimal IDAT. That makes a regression test cheap.
    - Why it matters: this guard is the only protection against an upload that makes the server allocate gigabytes of heap. If a refactor drops it, for example by calling Thumbnailator before `dimensions()`, no test would fail.
    - Suggested fix: add a web test that uploads a small PNG with a header above `MAX_PIXELS`. Assert 422 `application/problem+json`, and assert that no file is left under `thumbnails/`.

## P3 Low

- [ ] **CR-3 | A thumbnail rendered while its blob is purged stays orphaned in the cache**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/infra/ThumbnailStore.kt:52` (with `FilesService.deleteBlob`, `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt:436`)
    - Category: Correctness
    - Problem: `thumbnail()` checks for the cached file, renders it, and then moves it into place with no coordination with `delete()`. Suppose a purge commits while a render is in flight: `deleteBlob` removes the blob and all thumbnails, and then the in-flight render moves its result into `thumbnails/<size>/<key>.<ext>`. Nothing ever deletes that file afterwards. In the same way, temp files under `thumbnails/tmp/` are left behind if the process dies mid-render.
    - Why it matters: this is a small disk leak in a cache the class documentation promises to clean up when the blob is purged. Its impact is low in a single-user app.
    - Suggested fix: after the move, check `Files.exists(source)` and delete the target if the blob is gone. Optionally, clear `thumbnails/tmp` at startup.

## Notes (not findings)

- Inline serving of user-supplied content was checked. Only `application/pdf`, `audio/*`, `video/*` and exact raster image types (jpeg/png/webp/gif) are served inline. SVG and HTML are excluded or downgraded to `text/plain`. Spring Security's default `X-Content-Type-Options: nosniff` stays active (SecurityConfiguration does not disable headers). The filename goes into `Content-Disposition` through the existing escaping helper.
- Path handling was checked. Thumbnail paths are built from `BlobStore.isValidKey`-validated storage keys and a whitelisted size, so no request input reaches the file system path.
- The `UTF-8 boundary` logic, the 256 KB limit, U+FFFD replacement, EXIF orientation, no-upscaling, the cache, deletion on purge, 404 for trashed files, and Range/206 are implemented and covered by `PreviewWebTests` / `PreviewTextTests`.
- At review time `python tasks.py verify files-preview` shows no evidence recorded for criteria 1-4 yet. The parent worker must record the Testcontainers run (criterion 3) and the `OpenApiContractTest` run without the flag (criterion 4) before `done`. The reviewer did not run Gradle.

## Review conclusion

Material issues found; fix before completing the task.
