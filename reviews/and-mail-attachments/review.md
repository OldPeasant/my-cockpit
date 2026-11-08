# Code Review

Date: 2026-11-08

Task: and-mail-attachments - Android mail attachments: open, download, preview, save to Files

Scope: `git diff 843c94d1f..HEAD -- android/` (implementation commit a5ec0a6e7, tests commit 0894f7f89); committed changes only

Requirements:
- tasks/and-mail-attachments.md (acceptance criteria and the two recorded assumptions)
- SPEC.md (section 7.3 mail attachments, section 4.2 API conventions)
- docs/ARCHITECTURE.md, AGENTS.md project conventions
- Server contract: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailMessageController.kt` (download, save-to-files)
- Parent instructions: focus on FileProvider/cache handling, filename sanitizing, PdfRenderer/bitmap leaks, large files/OOM, lifecycle

Git range:
- Branch: main
- HEAD: 0894f7f89aeb994d099ec79aa0f9d98bf3d10f65
- Base/start: 843c94d1f2c80bff56c1095be03686bee56fb977
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 2 P2, 3 P3

Files reviewed in full: `MailAttachmentRepository.kt`, `MailAttachmentsViewModel.kt`, `MailAttachments.kt`, `MailReadScreen.kt`, the `MailReadRepository.kt` diff, both `strings_mail.xml` diffs, `files/DeviceFiles.kt` (the reused `OpenedFiles` and `safeName`), and `mailCall`/`mailBody` in `mail/list/MailRepository.kt`. I checked the test files by test name and spot-read some of them.

Checked and OK:
- Attachment names from mail are made safe by `OpenedFiles.safeName`: path separators and control characters are removed, `.` and `..` are refused, and the name is shortened to a byte limit. The FileProvider only shares the `files-open` cache folder and gives read access to one file.
- `PdfRenderer`, `ParcelFileDescriptor` and each `Page` are closed with `use`. Images are decoded with `inJustDecodeBounds` and a sample size, so the longer side is at most 2048 px.
- Partial cache files and "save as" documents are removed when a download fails or is cancelled (`finally` with `NonCancellable`).
- When the user moves to another mail, `withContext` re-checks for cancellation, so a stale result from the old mail is never shown on the new one.
- The endpoints, the 413 mapping and the If-Match ETag format (`"<version>"`) match the server and the existing `etag()` helper.
- EN and DE have the same 27 `mail_attachment_*` keys. `mail_read_attachments` no longer takes an argument, and no code still passes one.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Cancelled downloads keep streaming the whole attachment in the background**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailAttachmentRepository.kt:76-88`, `:118-136`
    - Category: Correctness
    - Problem: `download` runs a blocking `copy` loop inside `withContext(Dispatchers.IO)`. The loop never checks for cancellation (`ensureActive`) and never closes the body when cancelled. Once the response headers have arrived, cancelling the coroutine has no effect on the transfer. This happens when `show()` cancels `job` because the user moved to another mail, or when the view model is cleared on leaving Mail. `withContext` waits for the blocking block to finish, so the full attachment is still downloaded and written, and only then deleted. Meanwhile `busy()` (`job?.isActive`) is already `false`, so a new download can start next to the old one. The test `after a cancelled download the next mail's attachments can be used at once` relies on this, but its fake download stops at once, which hides the problem.
    - Why it matters: Large attachments (tens of MB) keep using mobile data, disk and a server/IMAP connection after the user has left. Several abandoned downloads can run at the same time.
    - Suggested fix: In `copy`, call `coroutineContext.ensureActive()` (or `yield()`) each time through the loop, or use `runInterruptible`/a cancellation handler that closes the response body when cancelled. Add a test with a body that keeps streaming and is cancelled partway.

- [ ] **CR-2 | The PDF preview renders up to 30 full-width ARGB pages into memory before showing anything**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailAttachments.kt:345-353`, `:390-413`
    - Category: Correctness
    - Problem: `renderPdf` builds the bitmaps for all of the first 30 pages before the dialog shows anything. Each page is 1080 px wide and up to 4096 px tall, ARGB_8888. An A4 page is about 1080x1528x4 = 6.6 MB, so 30 pages is about 200 MB. Tall pages can reach about 17.7 MB each, up to about 530 MB. If `createBitmap` throws `OutOfMemoryError`, `runCatching` turns it into an empty list, so the user sees "This file cannot be shown here." for a valid PDF. Rendering is blocking and cannot be cancelled. If the dialog is closed during rendering, the bitmaps are never set as `value`, so `awaitDispose` cannot recycle them.
    - Why it matters: On low-memory devices a normal 20-30 page PDF can fail to preview or push the process toward being killed. Users also wait for all pages before seeing the first one.
    - Suggested fix: Render pages lazily, one per `LazyColumn` item, using a single open `PdfRenderer` (accessed serially) and recycling pages that leave the screen. Or at least cap the total pixel budget, check for cancellation between pages, and recycle any pages already rendered on failure or cancellation.

## P3 Low

- [ ] **CR-3 | The cache path for an opened attachment ignores the message id, so two mails can share one file**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt:673-674`
    - Category: Correctness
    - Problem: `cacheId` hashes only `partId` and `name`. Part ids such as `2` repeat across mails, and recurring mails like a monthly `invoice.pdf` share both values. Opening the same-named attachment from another mail reuses and truncates the same cache file. A viewer app still holding the earlier URI then sees the other mail's content. Together with CR-1, the old job's `finally { cache?.delete() }` can delete the file the new job just finished.
    - Why it matters: This is an unlikely race, but it can show the wrong content or fail to open, with no clear cause.
    - Suggested fix: Include the message id in the hash, e.g. `"mail-attachment/$messageId/${partId}/${name}"`.

- [ ] **CR-4 | A "save as" result that arrives before the mail has reloaded is dropped without telling the user**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailAttachmentsViewModel.kt:168-172`
    - Category: Correctness
    - Problem: The pending attachment survives process death in `rememberSaveable` (`PendingAttachmentSaver`), but the view model's `messageId` does not. After the process is restored, the picker's result is delivered at composition time, before `MailReadViewModel` has reloaded the mail and `show(id)` has run. `save()` then finds `messageId == null`, deletes the document and returns without a notice. The user picked a place and nothing happens.
    - Why it matters: The save silently fails. This only happens after process death while the system picker is open, which is uncommon but does happen on low-memory devices.
    - Suggested fix: Save the message id together with the pending attachment and pass it to `save()`, or at least set an `AttachmentNotice.Failed(SAVE)` notice when the save is dropped.

- [ ] **CR-5 | "Open in another app" from the preview downloads the attachment again**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailAttachments.kt:314-318`
    - Category: Quality
    - Problem: The preview's open button calls `closePreview()` and then `open(attachment)`, which streams the whole attachment from the server again into the same cache path. `preview.file` already holds the complete download.
    - Why it matters: It wastes data and time on large PDFs and images.
    - Suggested fix: Add a view model method that sends `OpenAttachment(preview.file, attachment.contentType)` to `openChannel` directly.

## Review conclusion

Material issues found; fix before completing the task.

All acceptance criteria are implemented: the list with name, size and type icon; Open through the FileProvider; Save to device with the system "save as" picker; the in-app image/PDF preview; Save to Files with a folder picker, as the recorded assumption describes; EN and DE strings; and unit/Compose tests for the view model, repository, screen, route and German strings. There are no P0 or P1 findings. CR-1 and CR-2 are resilience defects with large attachments and PDFs and should be fixed or explicitly accepted by the parent worker. CR-3 to CR-5 are minor. No `tasks.py verify` evidence was recorded in the task file when I reviewed it. The parent worker must record it, including the `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` results, before running `done`.
