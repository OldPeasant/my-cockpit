---
title: Android compose: recipient autocomplete, attach from Files
status: in_progress
owner: rudi/agent-2
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/compose,android/app/src/test/java/ch/sonensei/cockpit/mail/compose,android/app/src/main/res/values/strings_mail_compose.xml,android/app/src/main/res/values-de/strings_mail_compose.xml]
prio: 2
lease_until: 2026-11-08T15:21:59Z
---
## Goal
Android compose has plain recipient fields and can only attach device files; web suggests contacts and recent
correspondents (`recipient-input.ts`, `GET /api/v1/mail/correspondents`) and attaches from cockpit Files.

## Acceptance criteria
- [ ] To/Cc/Bcc suggest contacts and recent correspondents while typing; chosen recipients shown as chips
- [ ] "Attach from Files" opens a cockpit Files picker
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 15:37+0100 by rudi/agent-2): Suggestions use GET /api/v1/contacts/autocomplete (contacts + recent correspondents merged server-side), like web recipient-input; /mail/correspondents is not called separately
- ASSUMPTION (2026-11-08 15:37+0100 by rudi/agent-2): Recipient chips are rendered from the existing field strings (text up to the last , or ; outside quotes = chips, rest = typed text) so draft save/parse logic stays unchanged; reply/forward recipients get a trailing ', ' to show as chips
- ASSUMPTION (2026-11-08 15:37+0100 by rudi/agent-2): Attach from Files adds the drive file via PUT draft attachments [{fileId}] (server copies it), browsing with FilesRepository.list; picker shows folders/files only, no search
