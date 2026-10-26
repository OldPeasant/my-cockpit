# Fix Report

Date: 2026-10-26

Task: mail-attachment-to-files - Save mail attachment to Files (/Attachments/Mail)

Scope: current task; findings CR-1, CR-2, CR-3 of `reviews/mail-attachment-to-files/review.md`, on top of HEAD cfc8f67f3 (base 0c09f00ec). Changes limited to `server/src/main/kotlin/ch/sonensei/cockpit/mail/api` and its tests. No API contract change (controllers and `api/openapi.yaml` untouched).

## Fixed findings

### CR-2 — Local temp-file open/close failures are still reported as mail-server failures

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailReadService.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailAttachmentFilesService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailAttachmentFilesServiceTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailAttachmentDownloadTests.kt`

Problem:
`Files.newOutputStream(temp)` and the `close()` of `.use { }` ran inside `withRemoteMessage` without being caught, so a local `IOException` there was turned into `MailConnectionException` (502) and the pooled IMAP connection was dropped.

Fix:
The shared helper `MailReadService.copyAttachment` (see CR-3) now catches an `IOException` from opening the output stream, and from closing it (when `closeOutput = true`), inside the IMAP block, and returns it as the output failure together with write/flush failures. Only mail-server read failures leave the block as exceptions. Save-to-files opens the temp file lazily through the helper's `open` lambda (`LimitedOutputStream` now forwards `close()`), and maps any returned failure to `BlobTooLargeException` (size signal) or `UncheckedIOException("Could not buffer the attachment")`, the same local write-failure outcome as before. The stream is also closed when a mail-server read fails, so no file handle leaks.
New regression tests: buffer file that cannot be opened (a directory in its place) -> `UncheckedIOException` and the IMAP block ends normally, temp file removed; helper close failure is returned (not thrown) and the stream is closed; the stream is closed when the server read fails; a client stream that fails to open -> `AttachmentDownloadAbortedException` and the IMAP block ends normally.

Validation:
- `cd server && ./gradlew test --offline --tests 'ch.sonensei.cockpit.mail.api.MailAttachment*' --tests 'ch.sonensei.cockpit.mail.api.MailMessageControllerTests' --tests 'ch.sonensei.cockpit.mail.api.MailConnectionAdviceWebTests'` — Pass (MailAttachmentFilesServiceTests 22, MailAttachmentDownloadTests 9, MailMessageControllerTests 9, MailConnectionAdviceWebTests 5; 0 failures)

### CR-3 — Attachment lookup/streaming logic duplicated from MailReadService.attachment

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailReadService.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailAttachmentFilesService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailAttachmentFilesServiceTests.kt`

Problem:
`MailAttachmentFilesService` re-implemented the message/attachment lookup, `withRemoteMessage` + `findPart`, the "return, do not throw, inside the IMAP block" copy loop and its own outcome type.

Fix:
Extracted `MailReadService.copyAttachment(id, partId, closeOutput, open): AttachmentCopy` (located message, attachment, output failure or `null`; throws `NotFoundException` for an unlisted or missing part). `MailReadService.attachment` (download endpoint) now delegates to it with `closeOutput = false` (the servlet stream stays open, as before) and throws `AttachmentDownloadAbortedException` on an output failure. `MailAttachmentFilesService` uses it with `closeOutput = true`; its duplicate `download`/`copy`/`Download` code and the `MailContentLoader` dependency were removed. `LimitedOutputStream` stays in the save service (it is specific to the upload limit). Behaviour is unchanged except for the CR-2 fix; as a side effect, an `IOException` while obtaining the client response stream in the download is now an aborted download rather than a 502.
`MailAttachmentFilesServiceTests` was adapted to the new constructor: it now uses a real `MailReadService` over a mocked `MailMessageLocator`/`MailContentLoader` (as `MailAttachmentDownloadTests` does). The unknown-message test now stubs `locator.current(unknown)` to `null` instead of mocking `reads.get`. No assertions were weakened.

Validation:
- `cd server && ./gradlew ktlintFormat --offline` — Pass (no changes needed)
- focused unit tests above — Pass
- `cd server && ./gradlew integrationTest --offline --tests 'ch.sonensei.cockpit.mail.api.MailAttachmentToFilesGreenMailTests' --tests 'ch.sonensei.cockpit.mail.api.MailReadGreenMailTests'` — Pass (3 + 7 tests, 0 failures)

## Not fixed

### CR-1 — 413 and 503 paths and the filename/name-collision helpers have no tests

Status:
Invalid (already resolved before this fixer ran)

Reason:
The tests were added in commit cfc8f67f3 (MailAttachmentFilesServiceTests, MailMessageControllerTests, MailConnectionAdviceWebTests, FilesServiceTests). The fixer only adapted MailAttachmentFilesServiceTests to the refactored constructor (CR-3); all of them still pass.

## Validation summary

Focused tests:
- `./gradlew test --offline --tests 'ch.sonensei.cockpit.mail.api.MailAttachment*' --tests '...MailMessageControllerTests' --tests '...MailConnectionAdviceWebTests'` — Pass (45 tests)
- `./gradlew integrationTest --offline --tests '...MailAttachmentToFilesGreenMailTests' --tests '...MailReadGreenMailTests'` — Pass (10 tests)

Full test suite:
- `cd server && ./gradlew check --offline` (ktlint + all server unit tests) — Pass (BUILD SUCCESSFUL in 6m 52s)

Lint/type-check/build:
- `./gradlew ktlintFormat --offline` — Pass; ktlint checks inside `./gradlew check` — Pass

`.taskcheck`:
- Not run (`sh scripts/check.sh` also covers web and android, which this fix does not touch; the parent worker runs it before `done`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
