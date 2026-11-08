---
title: Android mail search kept on return, previous/next, back to origin
status: in_progress
owner: dell26/agent-1
deps: [and-mail-images]
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/list,android/app/src/test/java/ch/sonensei/cockpit/mail/list,android/app/src/main/java/ch/sonensei/cockpit/mail/read,android/app/src/test/java/ch/sonensei/cockpit/mail/read,android/app/src/main/java/ch/sonensei/cockpit/ui,android/app/src/test/java/ch/sonensei/cockpit/ui,android/app/src/main/res/values/strings_mail.xml,android/app/src/main/res/values-de/strings_mail.xml]
prio: 1
lease_until: 2026-11-08T09:29:08Z
---
## Goal
Same needs as web-mail-search-nav for Android: the search term and results must survive opening a match and
coming back, and the read view needs previous/next through the matches. Back must return to the origin everywhere.

## Acceptance criteria
- [ ] Opening a search result and returning (back gesture or toolbar) shows the same query and results (state in the ViewModel/SavedStateHandle; also survives rotation)
- [ ] The read view has previous/next actions stepping through the list it was opened from (search results, or the folder list), disabled at the ends; archive/delete moves to the next one
- [ ] Back from a mail returns to its origin for every entry point (New, Mail list, search results, global search, dashboard, contact, task links); add a test per entry point that has its own navigation code
- [ ] Manually verified on an emulator or device (or skipped with reason)
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 10:13+0100 by dell26/agent-1): Previous/next is offered only in the Mail tab (mailbox list or its search results). Mails opened from New, global search or task links show no previous/next: New mixes tasks and mails, the others open one item.
