---
title: Android compose: recipient autocomplete, attach from Files
status: in_progress
owner: rudi/agent-2
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/compose,android/app/src/test/java/ch/sonensei/cockpit/mail/compose,android/app/src/main/res/values/strings_mail_compose.xml,android/app/src/main/res/values-de/strings_mail_compose.xml]
prio: 2
lease_until: 2026-11-08T16:17:29Z
extended: rudi/agent-2
---
## Goal
Android compose has plain recipient fields and can only attach device files; web suggests contacts and recent
correspondents (`recipient-input.ts`, `GET /api/v1/mail/correspondents`) and attaches from cockpit Files.

## Acceptance criteria
- [x] To/Cc/Bcc suggest contacts and recent correspondents while typing; chosen recipients shown as chips
  - verified (2026-11-08 17:00+0100 by rudi/agent-2): cd android && ./gradlew testDebugUnitTest (MailComposeRecipientsFilesTest/EdgeCasesTest/ScreenTest/ScreenEdgeCasesTest/ReviewFixesTest/ReviewFixesScreenTest: debounced GET /contacts/autocomplete per To/Cc/Bcc field, suggestions list, choose -> chip, chips from drafts/replies, close icon removes) -> pass (3871 tests, 0 failures)
- [x] "Attach from Files" opens a cockpit Files picker
  - verified (2026-11-08 17:00+0100 by rudi/agent-2): cd android && ./gradlew testDebugUnitTest (Attach menu -> 'Attach from Files' opens cockpit Files picker, folder navigation via FilesRepository.list, pick -> PUT draft attachments [{fileId}]; Robolectric Compose + ViewModel tests) -> pass
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 15:37+0100 by rudi/agent-2): Suggestions use GET /api/v1/contacts/autocomplete (contacts + recent correspondents merged server-side), like web recipient-input; /mail/correspondents is not called separately
- ASSUMPTION (2026-11-08 15:37+0100 by rudi/agent-2): Recipient chips are rendered from the existing field strings (text up to the last , or ; outside quotes = chips, rest = typed text) so draft save/parse logic stays unchanged; reply/forward recipients get a trailing ', ' to show as chips
- ASSUMPTION (2026-11-08 15:37+0100 by rudi/agent-2): Attach from Files adds the drive file via PUT draft attachments [{fileId}] (server copies it), browsing with FilesRepository.list; picker shows folders/files only, no search
- extended by rudi/agent-2: Implementation, first test-writer + code-reviewer, and fixer (CR-1..CR-4 + quote bug) are done and committed (f7101796). Left: re-run test-writer and code-reviewer on the final code (fixes changed code, so first review is stale), tasks.py verify #1-#4, run sh scripts/check.sh (fixer saw a flaky unrelated server test PageFetcherSizeLimitTests; full check did not finish within 10 min this session), write+commit reviews/and-mail-compose-plus/quality.json, then done.
