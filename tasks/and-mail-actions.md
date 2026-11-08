---
title: Android mail: unread, flag, move, spam, multi-select
status: in_progress
owner: dell26/agent-2
deps: [and-mail-search-nav]
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/list,android/app/src/test/java/ch/sonensei/cockpit/mail/list,android/app/src/main/java/ch/sonensei/cockpit/mail/read,android/app/src/test/java/ch/sonensei/cockpit/mail/read,android/app/src/main/res/values/strings_mail.xml,android/app/src/main/res/values-de/strings_mail.xml]
prio: 2
lease_until: 2026-11-08T10:37:20Z
---
## Goal
Android mail cannot mark read/unread, flag, or move, has spam only in the read view and no multi-select (web:
`mail-list-page.html:36-121`). Decided: mobile versions (long-press multi-select).

## Acceptance criteria
- [ ] Read view menu: mark unread (returns to the list), flag/unflag, move to folder (folder picker of the same account), spam
- [ ] Long-press on a row starts multi-select; bulk archive, delete, spam, read/unread, flag, move
- [ ] Swipe actions stay; optimistic updates with undo where the action can be undone
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
