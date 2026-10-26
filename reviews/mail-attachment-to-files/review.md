# Code Review

Date: 2026-10-26

Task: mail-attachment-to-files - Save mail attachment to Files (/Attachments/Mail)

Scope: `git diff 6168d6141b6c5567cb2a8ecce615f41f1e644199..abc3e22d26a4bc872653912ac663cf551e3eb0ac -- . ':!tasks' ':!reviews'` (server mail/api, files domain/infra, api/openapi.yaml, generated web client, web/src/app/features/mail/read, tests). Focus: the only code change since the previous review, the test added in abc3e22d2 (`git diff aed1e4680..HEAD`, which touches only `MailAttachmentDownloadTests.kt` outside `reviews/`). Committed changes only; the working tree was clean.

Requirements:
- tasks/mail-attachment-to-files.md (both acceptance criteria and the two recorded assumptions)
- SPEC.md §7.3 (read view "save to Files"), §8.2 (special folders `/Attachments/Mail`), §4.3 (links)
- docs/ARCHITECTURE.md, AGENTS.md project notes (module boundaries, Problem Details, generated files, logging)
- Previous review of this task (CR-1..CR-3) and reviews/mail-attachment-to-files/fixes.md

Git range:
- Branch: main
- HEAD: abc3e22d26a4bc872653912ac663cf551e3eb0ac
- Base/start: 6168d6141b6c5567cb2a8ecce615f41f1e644199
- Commits reviewed: 4 (36e19245d implementation, e037c1875 tests, aed1e4680 fixes CR-2/CR-3, abc3e22d2 new copyAttachment test)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Earlier findings

- CR-1 (413/503 paths and name helpers untested): resolved. `MailMessageControllerTests` covers 201 with Location/ETag, 413 Problem Details, 503 POOL_EXHAUSTED and 502 for the new endpoint; `MailConnectionAdviceWebTests` covers 404/413 and CSRF; `MailAttachmentFilesServiceTests` covers the size boundary, temp-file cleanup, `fileName` sanitizing/truncation/fallback and `mime`; `FilesServiceTests` covers `freeFileName`.
- CR-2 (local open/close failures reported as mail-server failures): resolved. `MailReadService.copyAttachment` (`MailReadService.kt:154-190`) returns an `IOException` from `open`, write/flush and `close()` as `AttachmentCopy.failure` instead of throwing it inside the IMAP block; only read failures from the server leave the block. The output is closed when a server read fails as well.
- CR-3 (duplicated lookup/streaming logic): resolved. The download endpoint (`attachment`, `closeOutput = false`) and save-to-files both use `copyAttachment`; `LimitedOutputStream` stays specific to the upload limit.

## Review conclusion

No material issues found.

Review of the new test `copyAttachment returns the write failure, not a later close failure` (`MailAttachmentDownloadTests.kt:144-168`): the test is correct and worth keeping. It overrides `write(ByteArray, Int, Int)`, which is the overload `copy` calls (`MailReadService.kt:221`), so the write fails on the first chunk. It also overrides `close()` to fail. It checks three things: the stream is still closed (`closeOutput = true`), the returned failure is the write failure (so `failure = failure ?: e` at `MailReadService.kt:180` keeps the first failure), and the IMAP block ended normally. This guards a real dependency. `MailAttachmentFilesService.saveToFiles` maps only `TooLargeSignal`, which `LimitedOutputStream.write` throws, to `BlobTooLargeException`/413 (`MailAttachmentFilesService.kt:69-72`). If a later close failure replaced the write failure, an oversized attachment would turn into a 500 instead of a 413. The test fails if someone changes that line to `failure = e`. The only change since the earlier review is test-only, so the production code reviewed before is unchanged.

Re-checked against both criteria at this HEAD: the endpoint `POST /api/v1/mail/messages/{id}/attachments/{partId}/save-to-files` buffers the part, stores it in `specialFolder(MAIL)` under `freeFileName(...)` and links FILE -> EMAIL in one transaction. It returns 201 with `FileResponse`, ETag and Location, and documents 404/413/502/503 Problem Details. The regenerated `saveMailAttachmentToFiles` client exists, and the web `MailReadService.saveToFiles(id, partId)` calls it in place of the old download-and-reupload path. The GreenMail integration test `MailAttachmentToFilesGreenMailTests` exists. I did not run Gradle, npm or the integration test for this review (read-only inspection). The parent worker must run `cd server && ./gradlew check`, `cd web && npm run check` and the GreenMail integration test, and record them with `tasks.py verify`; both criteria are still unticked in the task file.
