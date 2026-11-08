# Fix Report

Date: 2026-11-08

Task: and-mail-attachments - Android mail attachments: open, download, preview, save to Files

Scope: current task; findings CR-1 to CR-5 from `reviews/and-mail-attachments/review.md` (base 83b516e05, task commits e434b2dea, 09e735e74, addd8678b)

## Fixed findings

### CR-1 — Cancelled downloads keep streaming the whole attachment in the background

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailAttachmentRepository.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailAttachmentRepositoryTest.kt`

Problem:
The blocking copy loop in `download` never checked for cancellation, so a cancelled download kept streaming and writing the whole attachment.

Fix:
`copy` now takes a `checkActive` callback, and `download` passes the `withContext` scope's `ensureActive()`. It runs before each 64 KiB chunk. On cancellation the `CancellationException` goes through `mailCall`, which rethrows it, and the `use` blocks close both the target and the response body. New test: `a cancelled download stops streaming partway and closes target and body`. It uses a body that streams 1 KiB chunks for 10,000 reads and cancels after 5. Without the fix, every read would run.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.MailAttachment*'` — Pass

### CR-2 — The PDF preview renders up to 30 full-width ARGB pages into memory before showing anything

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailAttachments.kt`

Problem:
`renderPdf` built bitmaps for all of the first 30 pages before the preview showed anything, which could use up to about 530 MB. The rendering could not be cancelled. An `OutOfMemoryError` turned the whole document into "cannot be shown", and bitmaps were leaked if the preview closed while rendering.

Fix:
- A new private `PdfDocument` opens the file once with one `PdfRenderer`. It reads only the page sizes up front, for the first 30 pages as before.
- Pages render one at a time when their `LazyColumn` item is composed. A `ReentrantLock` keeps renderer access serial.
- Each item keeps the page's shape while it renders (`aspectRatio`), so only the pages in view, plus prefetch, are rendered.
- Each page's bitmap is recycled in a `finally` when the item leaves composition or is cancelled.
- A page that fails (OOM or a renderer error) recycles its partial bitmap and shows "This file cannot be shown here." for that page only. The other pages still render.
- `close()` from the main thread never blocks: when a page is rendering, the document is closed as soon as that page is done.
- The document is closed when the preview leaves, even if that happens while it is still opening.
- A file that cannot be opened as a PDF still shows the whole-preview error (`PREVIEW_ERROR`).
- Page height is still capped at 4096 px (about 17.7 MB per page), and page ratios are clamped.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.MailAttachment*'` — Pass. The existing tests for an unreadable PDF preview, the preview route and the image preview still pass.
- Limitation: Robolectric has no working `PdfRenderer` (`NoSuchMethodError` in `ParcelFileDescriptor.detachFd`), so the per-page lazy rendering of a valid PDF cannot be covered by a JVM unit test. It needs an instrumented or manual check on a device.

### CR-3 — The cache path for an opened attachment ignores the message id

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailAttachmentsViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailAttachmentsViewModelTest.kt`

Problem:
`cacheId` hashed only `partId` and `name`, so two mails with the same part and name shared one cache file.

Fix:
`open` and `preview` now take `target: (UUID) -> File`. The view model passes the id of the mail it downloads from, and `cacheId(mail, attachment)` hashes `mail-attachment/<mail>/<partId>/<name>`. New test: `the cache file is chosen for the mail, so two mails never share one`.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.MailAttachment*'` — Pass

### CR-4 — A "save as" result that arrives before the mail has reloaded is dropped without telling the user

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailAttachmentsViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailAttachmentsViewModelTest.kt`

Problem:
After process death, `save()` ran with `messageId == null`, discarded the picked document and said nothing. A notice alone would not have helped, because `show(id)` resets the state when the mail reloads.

Fix:
- The saved pending state (`PendingSave`) now also stores the id of the mail shown when "Save to device" was tapped. Old 4-field saved state still restores, with no mail id.
- `save(attachment, target, mail, discard)` handles four cases:
  - If no mail is shown yet, it keeps the save waiting, and `show(mail)` then starts it.
  - If another mail is shown, or no mail is known at all, it discards the document and sets `AttachmentNotice.Failed(SAVE)`. Only the notice changes, so a running action is not affected.
  - If another action is running, it still discards silently, as before.
  - Otherwise it saves as before.
- A waiting save is discarded on the IO dispatcher when another mail is shown (with a notice), or when the view model is cleared.

New tests:
- `a save answer that arrives before its mail is shown waits for that mail, then saves`
- `a waiting save is discarded and says so when another mail is shown`
- `a save for another mail than the one shown is discarded and says so`
- The existing `a save to device without a mail ...` test now also asserts the `Failed(SAVE)` notice.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.MailAttachment*'` — Pass

### CR-5 — "Open in another app" from the preview downloads the attachment again

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailAttachmentsViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailAttachments.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailAttachmentsScreenTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailAttachmentsViewModelTest.kt`

Problem:
The preview's open button called `closePreview()` and then `open(attachment)`, which downloaded the attachment again.

Fix:
The new `MailAttachmentsViewModel.openPreviewed()` closes the preview and sends `OpenAttachment(preview.file, contentType)` straight to the open channel. The new `MailAttachmentCallbacks.openPreviewed` is wired to the preview's open button.

Tests:
- New: `open from the preview hands over the file already downloaded, without downloading again`, which checks for exactly one download.
- New: `open from the preview without a preview does nothing`.
- Changed: the screen test's expected call list is now `open previewed`, `close preview`. The old expectation encoded the behaviour this finding asked to change.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.MailAttachment*'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.MailAttachment*'` — Pass (after one Robolectric issue was fixed: `PdfDocument.open` now also catches `LinkageError`, as the earlier `runCatching` did)

Full test suite:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest lintDebug` — BUILD SUCCESSFUL. The first run failed on one lint error, a KTX `createBitmap` suggestion, which was fixed by using `androidx.core.graphics.createBitmap`.

Lint/type-check/build:
- Included above: ktlintCheck and lintDebug pass.

`.taskcheck`:
- Not run by the fixer (`scripts/check.sh` covers server, web and Android; the parent worker runs it before `done`).

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
