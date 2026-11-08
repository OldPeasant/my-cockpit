---
title: Android contacts: create, edit, delete
status: in_progress
owner: dell26/agent-1
deps: [and-links-panel]
touches: [android/app/src/main/java/ch/sonensei/cockpit/contacts,android/app/src/test/java/ch/sonensei/cockpit/contacts,android/app/src/main/res/values/strings_contacts.xml,android/app/src/main/res/values-de/strings_contacts.xml]
prio: 2
lease_until: 2026-11-08T20:54:14Z
---
## Goal
Android contacts are view-only (SPEC.md §9.2; owner decided on 2026-11-08 to add editing). Web: `contact-form.html`.

## Acceptance criteria
- [ ] Create and edit contacts (names, emails, phones, addresses, organization, birthday, notes), with repeatable fields
- [ ] Delete with confirmation (to Trash)
- [ ] Contacts synced from a read-only source show why they cannot be edited (same rule as web)
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
vCard import/export and CardDAV account setup stay web-only.
