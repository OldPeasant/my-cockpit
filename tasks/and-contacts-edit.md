---
title: Android contacts: create, edit, delete
status: in_progress
owner: dell26/agent-1
deps: [and-links-panel]
touches: [android/app/src/main/java/ch/sonensei/cockpit/contacts,android/app/src/test/java/ch/sonensei/cockpit/contacts,android/app/src/main/res/values/strings_contacts.xml,android/app/src/main/res/values-de/strings_contacts.xml]
prio: 2
lease_until: 2026-11-08T21:08:24Z
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
- ASSUMPTION (2026-11-08 21:38+0100 by dell26/agent-1): Read-only rule: the web offers Edit/Delete for every contact source (synced ones write back) and shows the sync source; Android does the same (source line 'Kept in the cockpit only'/'Synced with X') and, if the server refuses a change with 403/405, says the contact comes from X and must be changed there.
- ASSUMPTION (2026-11-08 21:38+0100 by dell26/agent-1): Birthday is entered as ISO text (YYYY-MM-DD) instead of a date picker; the form sends all fields (generated client), carrying the photo id through so editing never clears the photo.
