---
title: Android contacts
status: in_progress
owner: dell26/agent-1
deps: [and-login,contacts-aggregate]
touches: [android/app/src/main/java/ch/sonensei/cockpit/contacts,android/app/src/test/java/ch/sonensei/cockpit/contacts]
prio: 3
lease_until: 2026-10-22T10:34:18Z
---
## Goal
Contacts on the phone.

## Acceptance criteria
- [ ] List/search, detail with call/mail intents and related items
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.contacts.*'` passes, covering intent construction (Robolectric/Compose UI tests for screens)

## Notes
