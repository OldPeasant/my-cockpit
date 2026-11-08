---
title: Android mail attachments: open, download, preview, save to Files
status: in_progress
owner: felix/agent-1
deps: [and-mail-actions]
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/read,android/app/src/test/java/ch/sonensei/cockpit/mail/read,android/app/src/main/res/values/strings_mail.xml,android/app/src/main/res/values-de/strings_mail.xml]
prio: 2
lease_until: 2026-11-08T15:44:00Z
extended: 
releases: 2
---
## Goal
Android only lists attachment names in a header line (`MailReadScreen.kt:290`). Web can download, preview and
save to Files (`mail-read-page.html:219-279`, server `mail-attachment-to-files`).

## Acceptance criteria
- [x] An attachment list with name, size and type icon
  - verified (2026-11-08 15:24+0100 by rudi/agent-1): android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug at 6569b8ae -> pass; covered by MailAttachmentsScreenTest (name, formatted size, type icon incl. video/zip/pdf/image, content-type fallback) and MailReadRepositoryTest attachment mapping (test-writer: 280 mail.read tests pass)
- [x] Open (download to cache, open with another app via FileProvider) and Save to device (system create-document)
  - verified (2026-11-08 15:25+0100 by rudi/agent-1): android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug at 6569b8ae -> pass; MailAttachmentsViewModelTest + MailAttachmentsRouteTest (Robolectric): open downloads to the FileProvider cache and fires ACTION_VIEW with a content:// URI and read grant; save to device launches CreateDocument and copies into the chosen URI, incl. cancellation/restore cases. Not run on a real device (no adb/emulator here)
- [~] In-app preview for images and PDFs (reuse the preview from and-files-preview if done, else a simple one)
  - skipped (2026-11-08 15:25+0100 by rudi/agent-1): Image preview and the unreadable-PDF fallback verified: preview tests in MailAttachmentsScreenTest/ViewModelTest/RouteTest pass in testDebugUnitTest at 6569b8ae. Rendering the pages of a valid PDF (PdfRenderer) cannot run under Robolectric, and no adb/emulator is available here
- [x] "Save to Files" (cockpit Files, folder picker)
  - verified (2026-11-08 15:25+0100 by rudi/agent-1): android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug at 6569b8ae -> pass; MailAttachmentsViewModelTest/RouteTest/GermanTest: Save to Files stores via server into /Attachments/Mail, folder picker, then PATCH folderId to the picked folder; failed move keeps the file and says so
- [x] New strings are in English and German (`res/values/` + `res/values-de/`)
  - verified (2026-11-08 15:25+0100 by rudi/agent-1): android/gradlew -p android lintDebug at 6569b8ae -> pass (MissingTranslation enforced); strings_mail.xml en/de have matching keys; MailAttachmentsGermanTest (12 tests) renders the German texts -> pass
- [x] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass
  - verified (2026-11-08 15:39+0100 by rudi/agent-1): Tests: 280 mail.read tests incl. new MailAttachments*Test (Robolectric/Compose) -> pass; android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug at 6569b8ae -> pass; sh scripts/check.sh (12 min on this machine) -> fails only in server PageFetcherSizeLimitTests (bookmarks, untouched by this task, reproduces on main here; tracked as fix-flaky-pagefetcher-size-test); done re-runs the check

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 13:23+0100 by felix/agent-1): Save to Files folder picker: the server endpoint only stores into /Attachments/Mail, so Android stores there and then moves the file (PATCH /files/{id} folderId) into the picked folder; at the drive root the button saves to /Attachments/Mail (the drive root itself is not offered, a null folderId cannot be sent in the patch). A failed move keeps the file in /Attachments/Mail and says so.
- ASSUMPTION (2026-11-08 13:23+0100 by felix/agent-1): and-files-preview is not done: in-app preview is a simple full-screen dialog (BitmapFactory for png/jpeg/gif/webp/bmp, android PdfRenderer for PDFs, first 30 pages), no new library. Open/preview reuse the Files FileProvider cache (OpenedFiles) so no manifest change was needed.
- extended by felix/agent-1: Implementation + tests committed (a5ec0a6e7, 0894f7f89, b8a5d741a); reviews/and-mail-attachments/review.md has 2 P2 (CR-1 download copy loop ignores cancellation; CR-2 PDF preview renders all 30 pages eagerly, OOM risk) + 3 P3. Left: run fixer for CR-1/CR-2 (P3 as judged), re-run test-writer (tests.md is missing) and code-reviewer on final code, tasks.py verify all 6 criteria, quality.json, done. Note: scripts/check.sh did not finish within 10 min locally (stopped after server ktlint start) - rerun and investigate.
- release by felix/agent-1: Implementation done (d5b34f6d4), tests (27faa46df, 911509093, 890b5f978), review.md CR-1..CR-5 all fixed by fixer (c70302ca2, fixes.md). 'cd android && ./gradlew testDebugUnitTest --tests ch.sonensei.cockpit.mail.read.*' passes at 890b5f978. Left: (1) run test-writer on final code -> reviews/and-mail-attachments/tests.md (missing; include coverage), (2) re-run code-reviewer on final code (review.md is from before the fixes, so stale), (3) ktlintCheck testDebugUnitTest lintDebug + sh scripts/check.sh (an earlier local run did not finish in 10 min: check whether it hangs), (4) tasks.py verify for all 6 criteria, (5) quality.json, commit, done.
- ASSUMPTION (2026-11-08 15:25+0100 by rudi/agent-1): PDF page rendering in the in-app preview (android PdfRenderer, lazy per page) was code-reviewed but never executed: Robolectric lacks PdfRenderer and no device/emulator was available; needs a manual device check
- release by rudi/agent-1: over budget after 2 sessions (~42 min) and a review that decided nothing; still resumable: continue from wip/and-mail-attachments (see git log)
