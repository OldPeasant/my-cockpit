---
title: Android mail attachments: open, download, preview, save to Files
status: in_progress
owner: felix/agent-1
deps: [and-mail-actions]
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/read,android/app/src/test/java/ch/sonensei/cockpit/mail/read,android/app/src/main/res/values/strings_mail.xml,android/app/src/main/res/values-de/strings_mail.xml]
prio: 2
lease_until: 2026-11-08T12:47:28Z
---
## Goal
Android only lists attachment names in a header line (`MailReadScreen.kt:290`). Web can download, preview and
save to Files (`mail-read-page.html:219-279`, server `mail-attachment-to-files`).

## Acceptance criteria
- [ ] An attachment list with name, size and type icon
- [ ] Open (download to cache, open with another app via FileProvider) and Save to device (system create-document)
- [ ] In-app preview for images and PDFs (reuse the preview from and-files-preview if done, else a simple one)
- [ ] "Save to Files" (cockpit Files, folder picker)
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 13:23+0100 by felix/agent-1): Save to Files folder picker: the server endpoint only stores into /Attachments/Mail, so Android stores there and then moves the file (PATCH /files/{id} folderId) into the picked folder; at the drive root the button saves to /Attachments/Mail (the drive root itself is not offered, a null folderId cannot be sent in the patch). A failed move keeps the file in /Attachments/Mail and says so.
- ASSUMPTION (2026-11-08 13:23+0100 by felix/agent-1): and-files-preview is not done: in-app preview is a simple full-screen dialog (BitmapFactory for png/jpeg/gif/webp/bmp, android PdfRenderer for PDFs, first 30 pages), no new library. Open/preview reuse the Files FileProvider cache (OpenedFiles) so no manifest change was needed.
