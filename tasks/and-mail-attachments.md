---
title: Android mail attachments: open, download, preview, save to Files
status: in_progress
owner: felix/agent-1
deps: [and-mail-actions]
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/read,android/app/src/test/java/ch/sonensei/cockpit/mail/read,android/app/src/main/res/values/strings_mail.xml,android/app/src/main/res/values-de/strings_mail.xml]
prio: 2
lease_until: 2026-11-08T12:36:16Z
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
