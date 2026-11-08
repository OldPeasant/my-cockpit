---
title: Android contacts: create, edit, delete
status: in_progress
owner: dell26/agent-1
deps: [and-links-panel]
touches: [android/app/src/main/java/ch/sonensei/cockpit/contacts,android/app/src/test/java/ch/sonensei/cockpit/contacts,android/app/src/main/res/values/strings_contacts.xml,android/app/src/main/res/values-de/strings_contacts.xml]
prio: 2
lease_until: 2026-11-08T21:43:40Z
releases: 2
extended: 
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
- release by dell26/agent-1: Implemented: ContactForm model, ContactEditScreen (form with repeatable emails/phones/addresses), repository create/update/delete with If-Match, ContactEditViewModel, New FAB, Edit/Delete + confirm dialog, source line, EN+DE strings. Tests by test-writer committed (112 contacts tests, 3 fail on purpose = TW-1..3). reviews/and-contacts-edit/review.md + tests.md committed. LEFT (run fixer): CR-1 ContactDetailRoute hiltViewModel() for edit VM crashes BirthdayContactTest without Hilt (guard like links VM); CR-3/CR-4/TW-2 shared ContactEditViewModel state leaks (key it per contact/new, don't restart on recreation, clear error on cancel); CR-5 delete conflict: reload contact + delete-specific text; CR-6/TW-1 display-name derivation incl. organization/first email like web; CR-7 show server Problem detail on 400; CR-8 photo id gone; CR-9 notes trimEnd only, German quotes; TW-3 Capture FAB overlaps New: add Contacts to CockpitDestination.withOwnFab (ui/navigation, outside touches - add it or record). Then rerun test-writer + code-reviewer, full testDebugUnitTest/lintDebug, check.sh, quality.json.
- ASSUMPTION (2026-11-08 22:16+0100 by dell26/agent-1): Outside touches: added Contacts to CockpitDestination.withOwnFab (+ its copies in CaptureButtonTest/FabClearanceTest) so the shell's Capture FAB no longer covers the contacts New FAB (TW-3). CR-8 accepted: Android always sends photoFileId; if the photo file is gone the 400 detail is shown.
- release by dell26/agent-1: Fixer done and committed (99ae7faf1, reviews/and-contacts-edit/fixes.md): all CR/TW findings fixed; fixer reports ktlintCheck testDebugUnitTest lintDebug and check.sh pass. LEFT: rerun test-writer + code-reviewer on final code (stale), verify criteria 1-5, quality.json (fixes_required=true), done.
