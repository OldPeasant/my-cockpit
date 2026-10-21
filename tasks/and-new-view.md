---
title: Android combined New view
status: todo
owner:
deps: [and-inbox,and-mail-list-read,web-new-view]
touches: [android/app/src/main/java/ch/sonensei/cockpit/newitems,android/app/src/test/java/ch/sonensei/cockpit/newitems,android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt]
prio: 3
---
## Goal
Android counterpart of the web "New" view (SPEC.md §5.3 "New view", §9.2 item 3): GTD Inbox items and
unified-inbox mails in one list, with the same source filter. Additional view only; the Inbox and Mail
screens stay unchanged.

## Acceptance criteria
- [ ] "New" screen in the app navigation: merged list newest first with source marker, filter All / GTD Inbox / Mail (remembered), tap opens the task or the mail read view, mail row offers "→ Inbox task"; shows cached data offline (mail part read-only offline)
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.newitems.*'` passes, covering merge order, each filter, offline state (Robolectric/Compose UI tests for screens); `./gradlew ktlintCheck lintDebug` passes

## Notes
