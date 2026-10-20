---
title: Android contacts
status: todo
owner:
deps: [and-login,contacts-aggregate]
touches: [android/app/src/main/java/ch/sonensei/cockpit/contacts,android/app/src/test/java/ch/sonensei/cockpit/contacts]
prio: 3
---
## Goal
Contacts on the phone.

## Acceptance criteria
- [ ] List/search, detail with call/mail intents and related items
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.contacts.*'` passes, covering intent construction (Robolectric/Compose UI tests for screens)

## Notes
