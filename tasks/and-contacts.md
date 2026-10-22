---
title: Android contacts
status: in_progress
owner: dell26/agent-1
deps: [and-login,contacts-aggregate]
touches: [android/app/src/main/java/ch/sonensei/cockpit/contacts,android/app/src/test/java/ch/sonensei/cockpit/contacts]
prio: 3
lease_until: 2026-10-22T10:53:02Z
---
## Goal
Contacts on the phone.

## Acceptance criteria
- [x] List/search, detail with call/mail intents and related items
  - verified (2026-10-22 12:36+0200 by dell26/agent-1): Implemented in android/app/src/main/java/ch/sonensei/cockpit/contacts (ContactsListContent with debounced server search q=, paged; ContactDetailContent with Call=ACTION_DIAL tel:, Email=ACTION_SENDTO mailto:, Waiting For/recent mail/upcoming events from GET /contacts/{id}/related + linked items from GET /links?type=CONTACT). Exercised by cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.contacts.*' --rerun -> 46 passed, incl. ContactsRouteInAppTest (Robolectric, real Hilt MainActivity, fake repository: open contact from list, Call starts ACTION_DIAL tel:+41441234567, Email starts ACTION_SENDTO mailto:anna@example.org, no-app toast, back to list) and ContactsScreensTest (search, related items). Not run on a device/emulator against a live server.
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.contacts.*'` passes, covering intent construction (Robolectric/Compose UI tests for screens)

## Notes
- ASSUMPTION (2026-10-22 12:20+0200 by dell26/agent-1): Android contacts are read-only (list/search/detail; create/edit stays on the web): the criteria only name list/search/detail/intents, SPEC §9.2 item 7 says 'Contacts (view, call/mail intents)'.
- ASSUMPTION (2026-10-22 12:20+0200 by dell26/agent-1): Related items (Waiting For, recent mails, upcoming events, linked items) are shown but not tappable: opening them needs cross-feature navigation the shell does not offer yet. Call = ACTION_DIAL (no CALL_PHONE permission, user presses call), mail = ACTION_SENDTO mailto: to an external mail app. Strings live in res/values/strings_contacts.xml (outside touches, same per-feature pattern as other features).
