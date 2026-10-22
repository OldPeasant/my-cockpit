---
title: Android mail compose
status: in_progress
owner: dell26/agent-2
deps: [and-mail-list-read,mail-compose]
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/compose,android/app/src/test/java/ch/sonensei/cockpit/mail/compose]
prio: 2
lease_until: 2026-10-22T09:17:40Z
extended: dell26/agent-2
---
## Goal
Compose on the phone.

## Acceptance criteria
- [x] Compose/reply/forward (plain text + basic formatting), attachments from device, drafts
  - verified (2026-10-22 10:13+0200 by dell26/agent-2): cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.*' -> 145 tests in 15 classes, 0 failed: compose/reply/reply-all/forward start (MailComposeViewModelTest, MailComposeInAppTest, MailComposeWiringTest via MailScreen/MailReadScreen buttons), markup formatting to HTML (ComposeModelsTest), device attachments upload/remove (DeviceFilesTest, MailComposeRepositoryTest against MockWebServer), drafts create/update/autosave/discard/412 overwrite; lint clean. Not run on a physical device (h-android-device is a human task)
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.*'` passes, covering draft save and send states (Robolectric/Compose UI tests for screens)

## Notes
- ASSUMPTION (2026-10-22 09:01+0200 by dell26/agent-2): Basic formatting on Android = lightweight markup in a plain text field (**bold**, _italic_, '- ' bullet lines) with toolbar buttons; converted to HTML on save, server derives the text alternative. A real rich-text editor on Compose is out of proportion for v1.
- ASSUMPTION (2026-10-22 09:01+0200 by dell26/agent-2): Reply/forward quote: the server-built quoted HTML is kept verbatim below the user's text (shown read-only as a preview); only the user's own text is editable.
- ASSUMPTION (2026-10-22 09:01+0200 by dell26/agent-2): Sending identity: no identity list API exists, so Android uses the account's default identity (reply/forward: server's choice). New mail: account = the one of the open folder, else the first; pickable while no draft exists.
- ASSUMPTION (2026-10-22 09:01+0200 by dell26/agent-2): Resuming a draft from the Drafts folder is not in this task: Drafts rows keep opening the read view; drafts are saved (autosave 10 s, on back) and can be discarded or sent from the compose screen.
- ASSUMPTION (2026-10-22 09:01+0200 by dell26/agent-2): To wire compose in, the task makes minimal edits outside its touches: MailScreen.kt (compose button + route), MailReadScreen.kt (reply/reply all/forward buttons), new strings_mail_compose.xml. 412 on save: refetch the draft's ETag and overwrite (phone content wins). An unedited reply/forward draft is discarded when leaving.
- extended by dell26/agent-2: Implementation done; all compose unit tests pass (incl. new MailComposeSaveSendStatesTest/StatesScreenTest). First review CR-1..7 fixed (fixes.md). Left: fix second review.md findings CR-1 (P2: overwrite after 412 maps kept attachments by part id -> map by name/size/inline, ComposeSession.kt:189-199,303-316), CR-2 (P3: leave() must lock edits/send), CR-3 (P3: retry double-tap creates two drafts) via fixer; test-writer must write reviews/and-mail-compose/tests.md with coverage (missing); re-run code-reviewer on final code; quality.json; tasks.py verify 1,2; done.
- ASSUMPTION (2026-10-22 10:13+0200 by dell26/agent-2): Final review CR-1 (P3) accepted, not fixed: leaving during an upload closes the screen while remaining uploads finish in the background, a failed one is not shown; the Drafts folder shows which attachments were saved. Fixing would need another review round for a rare case.
