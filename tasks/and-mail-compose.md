---
title: Android mail compose
status: in_progress
owner: dell26/agent-2
deps: [and-mail-list-read,mail-compose]
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/compose,android/app/src/test/java/ch/sonensei/cockpit/mail/compose]
prio: 2
lease_until: 2026-10-22T07:18:47Z
---
## Goal
Compose on the phone.

## Acceptance criteria
- [ ] Compose/reply/forward (plain text + basic formatting), attachments from device, drafts
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.*'` passes, covering draft save and send states (Robolectric/Compose UI tests for screens)

## Notes
- ASSUMPTION (2026-10-22 09:01+0200 by dell26/agent-2): Basic formatting on Android = lightweight markup in a plain text field (**bold**, _italic_, '- ' bullet lines) with toolbar buttons; converted to HTML on save, server derives the text alternative. A real rich-text editor on Compose is out of proportion for v1.
- ASSUMPTION (2026-10-22 09:01+0200 by dell26/agent-2): Reply/forward quote: the server-built quoted HTML is kept verbatim below the user's text (shown read-only as a preview); only the user's own text is editable.
- ASSUMPTION (2026-10-22 09:01+0200 by dell26/agent-2): Sending identity: no identity list API exists, so Android uses the account's default identity (reply/forward: server's choice). New mail: account = the one of the open folder, else the first; pickable while no draft exists.
- ASSUMPTION (2026-10-22 09:01+0200 by dell26/agent-2): Resuming a draft from the Drafts folder is not in this task: Drafts rows keep opening the read view; drafts are saved (autosave 10 s, on back) and can be discarded or sent from the compose screen.
