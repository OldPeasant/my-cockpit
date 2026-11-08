---
title: Android compose: recipient autocomplete, attach from Files
status: in_progress
owner: Peter Gürtler
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/compose,android/app/src/test/java/ch/sonensei/cockpit/mail/compose,android/app/src/main/res/values/strings_mail_compose.xml,android/app/src/main/res/values-de/strings_mail_compose.xml]
prio: 2
lease_until: 
extended: 
hold: 
releases: 0
---
## Goal
Android compose has plain recipient fields and can only attach device files; web suggests contacts and recent
correspondents (`recipient-input.ts`, `GET /api/v1/mail/correspondents`) and attaches from cockpit Files.

## Acceptance criteria
- [x] To/Cc/Bcc suggest contacts and recent correspondents while typing; chosen recipients shown as chips
  - verified (2026-11-08 17:00+0100 by rudi/agent-2): cd android && ./gradlew testDebugUnitTest (MailComposeRecipientsFilesTest/EdgeCasesTest/ScreenTest/ScreenEdgeCasesTest/ReviewFixesTest/ReviewFixesScreenTest: debounced GET /contacts/autocomplete per To/Cc/Bcc field, suggestions list, choose -> chip, chips from drafts/replies, close icon removes) -> pass (3871 tests, 0 failures)
- [x] "Attach from Files" opens a cockpit Files picker
  - verified (2026-11-08 17:00+0100 by rudi/agent-2): cd android && ./gradlew testDebugUnitTest (Attach menu -> 'Attach from Files' opens cockpit Files picker, folder navigation via FilesRepository.list, pick -> PUT draft attachments [{fileId}]; Robolectric Compose + ViewModel tests) -> pass
- [x] New strings are in English and German (`res/values/` + `res/values-de/`)
  - verified (2026-11-08 17:00+0100 by rudi/agent-2): MailComposeRecipientsFilesStringsTest (every new key in res/values/strings_mail_compose.xml has a values-de translation) + ./gradlew lintDebug -> pass
- [~] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass
  - skipped (2026-11-08 17:00+0100 by rudi/agent-2): cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug -> pass (3871 tests); sh scripts/check.sh android -> all checks passed; sh scripts/check.sh: tfp+server passed, web step cannot run in this shell (Node v20.9.0, Angular needs >=22.22.3, nvm not usable here); task changes no web file; done re-runs .taskcheck

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 15:37+0100 by rudi/agent-2): Suggestions use GET /api/v1/contacts/autocomplete (contacts + recent correspondents merged server-side), like web recipient-input; /mail/correspondents is not called separately
- ASSUMPTION (2026-11-08 15:37+0100 by rudi/agent-2): Recipient chips are rendered from the existing field strings (text up to the last , or ; outside quotes = chips, rest = typed text) so draft save/parse logic stays unchanged; reply/forward recipients get a trailing ', ' to show as chips
- ASSUMPTION (2026-11-08 15:37+0100 by rudi/agent-2): Attach from Files adds the drive file via PUT draft attachments [{fileId}] (server copies it), browsing with FilesRepository.list; picker shows folders/files only, no search
- extended by rudi/agent-2: Implementation, first test-writer + code-reviewer, and fixer (CR-1..CR-4 + quote bug) are done and committed (f7101796). Left: re-run test-writer and code-reviewer on the final code (fixes changed code, so first review is stale), tasks.py verify #1-#4, run sh scripts/check.sh (fixer saw a flaky unrelated server test PageFetcherSizeLimitTests; full check did not finish within 10 min this session), write+commit reviews/and-mail-compose-plus/quality.json, then done.
- ASSUMPTION (2026-11-08 17:00+0100 by rudi/agent-2): Web part of scripts/check.sh not run by me: this agent shell only has Node 20; the task touches no web file, so the web result is unaffected; done's .taskcheck run is the final gate
- hold by rudi/agent-2: Everything is done and committed (impl, final test-writer + code-reviewer, quality.json, verify #1-#3, #4 skipped). done is REFUSED only because .taskcheck's web step needs Node >=22.22.3/24 and this agent shell has Node v20.9.0 (nvm not usable from the agent sandbox). Task touches no web file. Human: put Node 24 on the agents' PATH (or run done in a Node 24 shell), then unhold so done can finish.
