# Code Review

Date: 2026-11-08

Task: and-mail-attachments - Android mail attachments: open, download, preview, save to Files

Scope: `git diff origin/main...HEAD` (base ee9f70ad150555013844607818b0ca328ec39d10). Task commits: 6e56f8ec (implementation), b81c2614, 90380b84, 58ef7b0b (tests and the earlier review), fa45dcdb (fixes for CR-1 to CR-5). Committed changes only; the working tree was clean when the review started.

Requirements:
- tasks/and-mail-attachments.md (6 acceptance criteria and the two recorded assumptions: moving the file into the picked folder after the save, and a simple in-app preview with no new library)
- SPEC.md section 7.3 (mail attachments) and section 4.2 (ETag/If-Match)
- docs/ARCHITECTURE.md and the AGENTS.md project notes
- reviews/and-mail-attachments/review.md (the earlier review) and fixes.md
- Parent instructions: review the final code fresh, and confirm that CR-1 to CR-5 are resolved

Git range:
- Branch: main
- HEAD: 7fe87f8e2cc1b80b5c1bd7ec357b11020d8cb3e2 (merge of origin/wip/and-mail-attachments)
- Base/start: ee9f70ad150555013844607818b0ca328ec39d10
- Commits reviewed: 5

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Files read in full: `MailAttachmentRepository.kt`, `MailAttachmentsViewModel.kt`, `MailAttachments.kt`, the `MailReadScreen.kt` and `MailReadRepository.kt` diffs, both `strings_mail.xml` diffs, the reused helpers in `files/DeviceFiles.kt` (`OpenedFiles.target/view/launch/safeName`, `openForWriting`, `discardDocument`) and `mailCall` in `mail/list/MailRepository.kt`. I read the test files by test name and spot-read the cancellation and waiting-save tests.

## Earlier findings

- **CR-1 (cancelled downloads kept streaming): resolved.** `copy` calls `checkActive` (the `withContext` scope's `ensureActive()`) before each 64 KiB chunk (`MailAttachmentRepository.kt:88`, `:129`). `mailCall` rethrows `CancellationException`, and the `use` blocks close the target and the response body. The test `a cancelled download stops streaming partway and closes target and body` covers this.
- **CR-2 (eager rendering of 30 PDF pages): resolved.** `PdfDocument` opens the file once and reads only the page ratios. Each `PdfPage` renders its page lazily in its own `produceState`, under a `ReentrantLock`, and recycles the bitmap in `finally`. If a page hits OOM or a renderer error, it recycles its partial bitmap and shows a note for that page only. `close()` from the main thread does not block, and a page that is still rendering closes the document when it finishes (`MailAttachments.kt:457-549`). I checked the lock and `closing` handoff for races and found none. The open-then-close path in `PdfPreview` closes a document that is still opening. A JVM test cannot cover this, because Robolectric has no working `PdfRenderer`, as fixes.md says.
- **CR-3 (cache path ignored the message id): resolved.** `cacheId(mail, attachment)` hashes `mail-attachment/<mail>/<partId>/<name>`. The view model passes the id of the mail it downloads from (`MailReadScreen.kt:639-641`, `MailAttachmentsViewModel.kt:340`). A test covers this.
- **CR-4 (a "save as" result arriving before the mail reloads was dropped silently): resolved.** `PendingSave` stores the mail id, and the saver also restores the older 4-field state. `save()` now waits for the mail to be shown, or discards the document and sets `Failed(SAVE)`. `show()` starts or discards a waiting save, and `onCleared()` discards a waiting save off the main thread. Six view model tests and one route test cover these paths.
- **CR-5 (open from the preview downloaded the file again): resolved.** `openPreviewed()` sends the file that is already in the cache to the open channel. Both a view model test and a route test check that no second download happens.

## Checked and OK

- Acceptance criteria 1 to 5 are implemented. Criterion 1: the list shows the name, the size (`formatSize`) and a type icon based on the content type, then the file extension. Criterion 2: Open goes through the cache and the FileProvider (`OpenedFiles.view` with a read grant), and Save to device uses `CreateDocument`. Criterion 3: the in-app image and PDF preview. Criterion 4: Save to Files uses a folder picker, followed by a PATCH move with `If-Match: "<version>"`, as the assumption describes. Criterion 5: EN and DE both have 28 matching keys.
- Out-of-order results: `show()` cancels the running action and the picker listing. A late `files.list` result is ignored when the picker has moved to another folder. One action runs at a time (`busy()`), and the UI disables the buttons while an action runs.
- Cleanup: a partial cache file or a discarded "save as" document is removed under `NonCancellable + io`, also when the action is cancelled.
- Attachment names from mail go through `OpenedFiles.safeName` before they become a cache path. No secrets or mail content are logged.
- Architecture: the feature stays inside `mail/read` and reuses the Files helpers. It adds no new library, no manifest change and no server or API contract change.
- Tests: there are repository, view model, Compose screen, route (Robolectric, with intents and the activity result) and German-locale tests for the new behaviour. Most tests check results that users or callers can see. The one exception is the lazy rendering of a valid PDF, which needs an instrumented test or a manual check on a device; see the conclusion.

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

CR-1 to CR-5 from the earlier review are resolved in the current code. The parent worker should note the following:
- When I reviewed the task, no `tasks.py verify` evidence was recorded yet: all 6 criteria were still unticked. Criterion 6 needs a real run of `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh`. An earlier note in the task file says that `scripts/check.sh` once did not finish within 10 minutes.
- The lazy, per-page PDF rendering (the CR-2 fix) has no automated test, because Robolectric cannot run `PdfRenderer`. Either record this as a limitation in the criterion 3 and 6 evidence, or check it on a device or emulator.

I did not run Gradle myself, because a test-writer helper was working in the same clone at the same time.
