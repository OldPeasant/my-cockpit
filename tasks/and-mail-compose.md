---
title: Android mail compose
status: todo
owner:
deps: [and-mail-list-read,mail-compose]
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/compose,android/app/src/test/java/ch/sonensei/cockpit/mail/compose]
prio: 2
---
## Goal
Compose on the phone.

## Acceptance criteria
- [ ] Compose/reply/forward (plain text + basic formatting), attachments from device, drafts
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.*'` passes, covering draft save and send states (Robolectric/Compose UI tests for screens)

## Notes
