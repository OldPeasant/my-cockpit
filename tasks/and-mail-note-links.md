---
title: Android mail: create note and linked items
status: in_progress
owner: dell26/agent-1
deps: [and-links-panel,and-mail-attachments]
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/read,android/app/src/test/java/ch/sonensei/cockpit/mail/read,android/app/src/main/res/values/strings_mail.xml,android/app/src/main/res/values-de/strings_mail.xml]
prio: 2
lease_until: 2026-11-08T20:33:02Z
---
## Goal
Web read view can create a note from a mail and shows the linked-items panel (`mail-read-page.html:62-70,284`);
Android only has To task / Waiting.

## Acceptance criteria
- [ ] "Create note" action from the read view (same server call as web)
- [ ] The read view shows `LinkedItemsPanel` (from and-links-panel) for the mail
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 21:10+0100 by dell26/agent-1): Create note is a 7th toolbar icon (NoteAdd) next to To task/To Waiting, as on the web; MailApi.toNote added in mail/list (one line outside touches, needed for the server call)
- ASSUMPTION (2026-11-08 21:10+0100 by dell26/agent-1): Mail links panel sits below the body, capped at 240dp and scrollable (the WebView body fills the rest); in-app linked items open over the mail via SearchItemScreen, Screen targets (e.g. calendar) are ignored as in Contacts
- ASSUMPTION (2026-11-08 21:17+0100 by dell26/agent-1): Supersedes the toolbar-icon assumption: Create note is the first item of the read view's More menu (review CR-2: 7 icons clip More at 320dp); web shows it next to the GTD buttons
