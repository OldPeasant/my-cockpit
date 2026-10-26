---
title: Android combined New view
status: in_progress
owner: dell26/agent-2
deps: [and-inbox,and-mail-list-read,web-new-view]
touches: [android/app/src/main/java/ch/sonensei/cockpit/newitems,android/app/src/test/java/ch/sonensei/cockpit/newitems,android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt]
prio: 3
lease_until: 2026-10-26T10:19:21Z
---
## Goal
Android counterpart of the web "New" view (SPEC.md §5.3 "New view", §9.2 item 3): GTD Inbox items and
unified-inbox mails in one list, with the same source filter. Additional view only; the Inbox and Mail
screens stay unchanged.

## Acceptance criteria
- [ ] "New" screen in the app navigation: merged list newest first with source marker, filter All / GTD Inbox / Mail (remembered), tap opens the task or the mail read view, mail row offers "→ Inbox task"; shows cached data offline (mail part read-only offline)
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.newitems.*'` passes, covering merge order, each filter, offline state (Robolectric/Compose UI tests for screens); `./gradlew ktlintCheck lintDebug` passes

## Notes
- ASSUMPTION (2026-10-26 10:55+0100 by dell26/agent-2): Android has no task detail screen and GtdTask has no createdAt: 'tap opens the task' shows a read-only task view inside the New screen (title, notes, created), and task creation time is read from the UUIDv7 id timestamp (non-v7 ids sort last), so gtd/data stays untouched.
- ASSUMPTION (2026-10-26 10:55+0100 by dell26/agent-2): Mail has no local cache on Android (MailRepository, SPEC 9.3 caches only GTD lists/agenda): 'cached mail offline' = the last loaded unified-inbox page kept in memory (singleton, cleared on logout via SessionCache), shown read-only (no '-> Inbox task') with an offline banner; nothing mail-related is written to disk.
- ASSUMPTION (2026-10-26 10:55+0100 by dell26/agent-2): 'New' is the first entry of the More list (bottom bar stays at 5 items); needed small edits outside touches: CockpitDestination.kt, strings.xml (nav_new), CockpitDestinationTest order, new res/values/strings_new.xml. Filter remembered in SharedPreferences 'new_view' (UI preference, kept across logout).
