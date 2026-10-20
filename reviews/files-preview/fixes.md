# Fix Report

Date: 2026-10-21

Task: files-preview - Thumbnails and previews

Scope: current task; findings CR-1, CR-2, CR-3 from `reviews/files-preview/review.md`

## Fixed findings

### CR-1 — Inline preview answers 500 for a stored mime type that Spring cannot parse

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/PreviewService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/files/PreviewEdgeCaseWebTests.kt`

Problem:
`PreviewService.preview` accepted any `audio/...`/`video/...` mime, so malformed stored values such as `video/` or
`audio/mpeg x` reached `MediaType.parseMediaType` in `PreviewController` and caused an unhandled exception (500).
Wildcard subtypes such as `audio/*` would also fail, because `HttpHeaders.setContentType` rejects them.

Fix:
`PreviewService` now serves a file inline only if `MimeTypeUtils.parseMimeType` parses its base mime type and that type
has no wildcard. If it doesn't, the existing `PreviewNotSupportedException` path applies (415 problem+json). The
controller and the API contract are unchanged.
New test: `audio and video files with a malformed stored mime type have no preview instead of a server error` (it
covers `video/`, `audio/mpeg x` and `audio/*` -> 415 `application/problem+json`).

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.files.*'` — Pass

### CR-2 — The decompression-bomb guard (MAX_PIXELS -> 422) is not tested

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/files/PreviewEdgeCaseWebTests.kt`

Problem:
When the review was written, the guard had no test.

Fix:
The test-writer's later commit (8aaa677c) already covers most of this:
- `PreviewEdgeCaseWebTests` has the web test `an image header claiming more than 100 megapixels is rejected with 422`
  (20000x20000 PNG header -> 422 problem+json with the pixel-limit detail).
- `ThumbnailStoreTests` has the unit tests at the limit, just below it, and for Long overflow.

To avoid a duplicate, the existing web test was extended with the one missing assertion from the review: no cached
thumbnail of the file exists afterwards and `thumbnails/tmp` holds no leftover file.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.files.*'` — Pass

### CR-3 — A thumbnail rendered while its blob is purged stays orphaned in the cache

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/files/infra/ThumbnailStore.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/files/ThumbnailStoreTests.kt`

Problem:
A render that finished after `deleteBlob` (blob plus thumbnails) still moved its result into the cache, where nothing
deleted it. Temporary files from renders interrupted by a process stop stayed in `thumbnails/tmp`.

Fix:
- After the atomic move, `thumbnail()` checks that the source blob still exists. If the blob is gone, it deletes the
  thumbnail it just moved into place and throws `NoSuchFileException`, the same exception `BlobStore.open` throws for
  a missing blob.
- The `ThumbnailStore` constructor empties `thumbnails/tmp` at startup.
- New test: `temporary files left by an interrupted render are removed at startup, cached thumbnails are kept`.
- The purge race itself has no deterministic test: the private render step offers no hook to delete the blob in
  mid-render without adding test-only seams. The post-move check was verified by reading the code.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.files.*'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` — Pass (no remaining changes)
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*'` — Pass. All 16 files test classes ran, with
  0 skipped, 0 failures and 0 errors. This includes the Testcontainers-backed `PreviewEdgeCaseWebTests` (18 tests),
  `PreviewWebTests` (12) and `ThumbnailStoreTests` (9).

Full test suite:
- `cd server && ./gradlew check` — Pass (BUILD SUCCESSFUL: ktlint, detekt, all server tests)

Lint/type-check/build:
- Covered by `./gradlew check` — Pass
- `api/openapi.yaml` — not regenerated: no API response changed (the 415 response was already documented)

`.taskcheck`:
- Not run by the fixer (the parent worker runs it before `done`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
