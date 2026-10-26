# Code Review

Date: 2026-10-26

Task: mail-attachment-to-files - Save mail attachment to Files (/Attachments/Mail)

Scope: `git diff ad49eeec5..6aa60b157` (server mail/api, files domain/infra, api/openapi.yaml, generated web client, web/src/app/features/mail/read). HEAD 67abd5bad is the same change rebased; it differs from 6aa60b157 only in `tasks/mail-attachment-to-files.md`.

Requirements:
- tasks/mail-attachment-to-files.md (acceptance criteria and the two recorded assumptions)
- SPEC.md §7.3 (read view "save to Files"), §8.2 (special folders `/Attachments/{...,Mail,...}`), §4.3 links
- docs/ARCHITECTURE.md, AGENTS.md project notes (module boundaries, Problem Details, generated files)
- Existing conventions: MailReadService.attachment, MailGtdService, FilesService.storeFile, FileController/NoteController 413 handlers

Git range:
- Branch: main
- HEAD: 67abd5bad8f83fa7630782bb180f5a1057d9ff51
- Base/start: ad49eeec55b75ccfa8fd5d3d84336819a39dabab
- Commits reviewed: 1 (6aa60b157964a516229f76102f32179fb59b2d70)

Summary: 0 P0, 0 P1, 1 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | 413 and 503 paths and the filename/name-collision helpers have no tests**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailAttachmentToFilesGreenMailTests.kt:1`
    - Category: Tests
    - Problem: The only new test is the GreenMail integration test (201 + link + numbered name, 404, 502). Nothing covers: the 413 path (`LimitedOutputStream`/`Download.TooLarge` -> `BlobTooLargeException` -> `MailMessageController.handleTooLarge`, and that the temp file is deleted and no file row exists); the 503 `POOL_EXHAUSTED` mapping for the new endpoint, which the acceptance criterion names ("Problem Details for 404/502/503"); `MailAttachmentFilesService.fileName` (separators/control chars -> `_`, blank/`.`/`..` -> `attachment`, truncation to 255 keeping the extension) and `mime` (fallback for missing or `application/octet-stream`); `FilesService.freeFileName`/`FileRepository.existsActiveInFolderByName` (case-insensitive match, trashed files ignored). The controller unit tests only got an extra `mockk()` constructor argument.
    - Why it matters: The 413 branch has custom streaming logic that converts an exception into a returned value inside the IMAP block; a regression there would either surface as a 502 and drop a healthy pooled connection or leave large temp files. 503 is an explicitly listed criterion with no evidence for this endpoint. The name sanitizer is the only defence against attachment filenames that `FileNames.validate` would reject with a confusing 400.
    - Suggested fix: Add a standalone `MailMessageControllerTests` case for the new endpoint with a mocked `MailAttachmentFilesService` throwing `MailConnectionException` (pool exhausted -> 503) and `BlobTooLargeException` (-> 413 Problem Details); plain unit tests for `fileName`/`mime`; a GreenMail or service test with a small `cockpit.files.max-size` for the 413 path; a Postgres test for `freeFileName` (case and Trash).

## P3 Low

- [ ] **CR-2 | Local temp-file open/close failures are still reported as mail-server failures**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailAttachmentFilesService.kt:97`
    - Category: Correctness
    - Problem: `download` documents that "a local write failure is not counted as a mail server failure", and `copy` returns `WriteFailed` for write/flush errors. But `Files.newOutputStream(target)` and the `close()` performed by `.use { }` run inside the `withRemoteMessage` block, so an `IOException` from them is caught by `MailContentLoader.withRemoteMessage` (lines 125-127) and becomes `MailConnectionException` -> 502, and the pooled IMAP connection is treated as failed.
    - Why it matters: A local disk problem (permissions, disk full on close) shows up as "the mail server failed" and discards a working connection, contradicting the stated design.
    - Suggested fix: Open the output stream outside the IMAP block (or wrap open/close in the same `try` that maps to `Download.WriteFailed`).

- [ ] **CR-3 | Attachment lookup/streaming logic duplicated from MailReadService.attachment**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailAttachmentFilesService.kt:88`
    - Category: Quality
    - Problem: `saveToFiles`/`download`/`copy` re-implement `MailReadService.attachment` (lines 137-156): `get` + `loader.load` + `firstOrNull { partId }` + `withRemoteMessage` + `findPart` + the "return, do not throw, inside the IMAP block" copy loop with its own sealed outcome type. `LimitedOutputStream` also mirrors the size check in `BlobStore.write`.
    - Why it matters: The subtle IMAP-block error contract now lives in two places in the same package; a fix to one (e.g. CR-2) is easy to miss in the other.
    - Suggested fix: Extract a shared helper in `MailReadService` that streams a located part into a caller-supplied `OutputStream` and returns the outcome, and use it from both the download endpoint and save-to-files.

## Review conclusion

Material issues found; fix before completing the task.

Notes on what was verified and found sound: the endpoint path, method, 201 + `FileResponse` + ETag + Location match the criterion and the regenerated `api/openapi.yaml` and generated client (`saveMailAttachmentToFiles`); `MailReadService.saveToFiles` uses it and the old download/re-upload path is removed; the file goes into `specialFolder(MAIL)` (`/Attachments/Mail`, asserted in the test), taken names get ` (n)`, the file is indexed via `storeFile` and linked FILE -> EMAIL, consistent with `MailGtdService`; storing and linking run in one transaction, and `storeFile` deletes the blob on rollback; the temp file is created under `cockpit.data-dir` with a generated name (the attachment filename is never used as a path) and is deleted in `finally`; no mail content is logged; the 413 handler matches the existing FileController/NoteController pattern; the web page keeps its per-part double-submit guard and error notification.
