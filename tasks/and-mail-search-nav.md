---
title: Android mail search kept on return, previous/next, back to origin
status: in_progress
owner: dell26/agent-1
deps: [and-mail-images]
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/list,android/app/src/test/java/ch/sonensei/cockpit/mail/list,android/app/src/main/java/ch/sonensei/cockpit/mail/read,android/app/src/test/java/ch/sonensei/cockpit/mail/read,android/app/src/main/java/ch/sonensei/cockpit/ui,android/app/src/test/java/ch/sonensei/cockpit/ui,android/app/src/main/res/values/strings_mail.xml,android/app/src/main/res/values-de/strings_mail.xml]
prio: 1
lease_until: 2026-11-08T09:49:18Z
---
## Goal
Same needs as web-mail-search-nav for Android: the search term and results must survive opening a match and
coming back, and the read view needs previous/next through the matches. Back must return to the origin everywhere.

## Acceptance criteria
- [x] Opening a search result and returning (back gesture or toolbar) shows the same query and results (state in the ViewModel/SavedStateHandle; also survives rotation)
  - verified (2026-11-08 10:26+0100 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest --tests '*MailSearchRouteInAppTest' --tests '*MailReadNavigationInAppTest' (Hilt/Robolectric MainActivity, Mail tab) -> pass: system back and toolbar back from a result show the same query + results, search not re-run (coVerify exactly 1), scenario.recreate() on the open result and on the results keeps both (state in MailListViewModel, open id rememberSaveable)
- [x] The read view has previous/next actions stepping through the list it was opened from (search results, or the folder list), disabled at the ends; archive/delete moves to the next one
  - verified (2026-11-08 10:26+0100 by dell26/agent-1): ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.MailReadNavigation*' --tests '*MailReadNavigationInAppTest' -> 7+14+2+11 passed: prev/next through mailbox and search results ('2 of 3'), disabled at ends and while busy, delete/archive/spam open the next mail, last mail closes to the list, last loaded mail fetches the next page (also after a running refresh)
- [x] Back from a mail returns to its origin for every entry point (New, Mail list, search results, global search, dashboard, contact, task links); add a test per entry point that has its own navigation code
  - verified (2026-11-08 10:26+0100 by dell26/agent-1): ./gradlew testDebugUnitTest --tests '*MailBackToOriginInAppTest' --tests '*MailOriginShellTest' --tests '*MailRouteInAppTest' --tests '*MailSearchRouteInAppTest' -> all pass: New (system/toolbar back, delete), global search (system/toolbar back, delete returns to search - fails without the shell close guard), Dashboard->Mail tab->mail->back->list->back->Dashboard, task link item route back->task->origin, Mail list, search results. Contacts open no in-app mail (phone mail app), see assumption
- [~] Manually verified on an emulator or device (or skipped with reason)
  - skipped (2026-11-08 10:26+0100 by dell26/agent-1): no emulator/device or adb on this machine (which adb emulator -> not found); behavior covered by Robolectric in-app tests on the real MainActivity
- [x] New strings are in English and German (`res/values/` + `res/values-de/`)
  - verified (2026-11-08 10:26+0100 by dell26/agent-1): grep mail_read_previous/next/position android/app/src/main/res/values{,-de}/strings_mail.xml -> en 'Previous mail'/'Next mail'/'%1$d of %2$d', de 'Vorherige Mail'/'Nächste Mail'/'%1$d von %2$d'; MailReadNavigationGermanTest 2/2 passed; lintDebug clean (no MissingTranslation)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 10:13+0100 by dell26/agent-1): Previous/next is offered only in the Mail tab (mailbox list or its search results). Mails opened from New, global search or task links show no previous/next: New mixes tasks and mails, the others open one item.
- ASSUMPTION (2026-11-08 10:13+0100 by dell26/agent-1): Archive/delete/spam in the read view shows the next mail of the list; at the end of the list it returns to the list (as web-mail-search-nav: next, else back to the list), not to the previous mail.
- ASSUMPTION (2026-11-08 10:13+0100 by dell26/agent-1): Previous/next sit in a slim row below the read toolbar ('3 of 12' + up/down arrows) because the toolbar already holds 6 icons and has no room on a 320-360 dp phone. Opening the last loaded mail of a mailbox fetches the next page so Next can go on; search results are not paged.
- ASSUMPTION (2026-11-08 10:13+0100 by dell26/agent-1): Entry points: Dashboard has no own mail-opening code (its Inbox card opens the Mail tab), contacts open mail in the phone's mail app (no in-app mail), task links use the shell's item route like global search; each is tested at the code that navigates. Shell item screens now close only if still shown, so deleting a mail opened from global search no longer pops the search screen too (pre-existing double close).
