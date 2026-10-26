# Code Review

Date: 2026-10-26

Task: mail-attachment-to-files - Save mail attachment to Files (/Attachments/Mail)

Scope: `git diff 94293c2ec344830a9eafe10ac4902a316c4d0239..0296d140e47bffcffdb1b2d05c9fd1a7b96e5ba7` (server mail/api, files domain/infra, api/openapi.yaml, generated web client, web/src/app/features/mail/read, tests). Committed changes only; the working tree was clean.

Requirements:
- tasks/mail-attachment-to-files.md (both acceptance criteria and the two recorded assumptions)
- SPEC.md §7.3 (read view "save to Files"), §8.2 (special folders `/Attachments/Mail`), §4.3 (links)
- docs/ARCHITECTURE.md, AGENTS.md project notes (module boundaries, Problem Details, generated files, logging)
- Previous review of this task (CR-1..CR-3) and reviews/mail-attachment-to-files/fixes.md
- Existing conventions: MailContentLoader.withRemoteMessage, BlobStore temp-file pattern, FileController/NoteController/JournalEntryController 413 handlers

Git range:
- Branch: main
- HEAD: 0296d140e47bffcffdb1b2d05c9fd1a7b96e5ba7
- Base/start: 94293c2ec344830a9eafe10ac4902a316c4d0239
- Commits reviewed: 3 (079f8a4dd implementation, 03f5e8303 tests, 0296d140e fixes)

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

- CR-1 (413/503 paths and name helpers untested): resolved. `MailMessageControllerTests` covers 201 with Location/ETag, 413 Problem Details, 503 POOL_EXHAUSTED and 502 for the new endpoint; `MailConnectionAdviceWebTests` covers 404/413 and CSRF; `MailAttachmentFilesServiceTests` covers the size boundary (exactly max stored, max+1 is 413 without failing the IMAP block), temp-file cleanup, `fileName` sanitizing/truncation/fallback and `mime`; `FilesServiceTests` covers `freeFileName` (case-insensitive, Trash and other folders ignored, validation).
- CR-2 (local open/close failures reported as mail-server failures): resolved. `MailReadService.copyAttachment` catches an `IOException` from `open` and from `close()` inside the IMAP block and returns it as `AttachmentCopy.failure`; only read failures from the server leave the block. The output is also closed when a server read fails. Regression tests exist in `MailAttachmentFilesServiceTests` ("a buffer file that cannot be opened ...") and `MailAttachmentDownloadTests`.
- CR-3 (duplicated lookup/streaming logic): resolved. Both the download endpoint (`attachment`, `closeOutput = false`) and save-to-files use `copyAttachment`; the duplicate copy loop and outcome type in `MailAttachmentFilesService` are gone. `LimitedOutputStream` stays specific to the upload limit.

## Review conclusion

No material issues found.

Verified and found sound: the endpoint `POST /api/v1/mail/messages/{id}/attachments/{partId}/save-to-files` returns 201 with `FileResponse`, ETag and Location; it matches the regenerated `api/openapi.yaml` (404/413/502/503 Problem Details documented) and the generated `saveMailAttachmentToFiles` client; `MailReadService.saveToFiles` in the web uses it, and the old download-and-reupload path is removed (asserted with `expectNone`). The file goes into `specialFolder(MAIL)`, i.e. `/Attachments/Mail` (asserted in the GreenMail integration test, along with content, mime, size, ` (2)` numbering and the FILE -> EMAIL link). The temp buffer under `cockpit.data-dir/tmp/mail` uses a generated name, so the attachment filename never becomes a path, and it is deleted in `finally`. Storing and linking run in one transaction, and `storeFile` deletes the blob on rollback. No database connection is held while reading from IMAP. The sanitized name always passes `FileNames.validate`. No mail content or filenames are logged. The 413 handler and the use of `files.infra.BlobTooLargeException` follow the existing notes/journal controllers. The page keeps its per-part double-submit guard and its error notification. I did not run any tests or `tasks.py verify` for this review (read-only inspection). The parent worker still has to record criterion evidence: both criteria are unticked in the task file.
