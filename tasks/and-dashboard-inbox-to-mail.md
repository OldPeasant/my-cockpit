---
title: Android: Dashboard Inbox card opens Mail when the GTD Inbox is empty
status: done
owner: Peter Gürtler
deps: [and-dashboard]
touches: [android/app/src/main/java/ch/sonensei/cockpit/dashboard,android/app/src/test/java/ch/sonensei/cockpit/dashboard]
prio: 2
lease_until: 
---
## Goal
Requested by the owner (2026-11-02) after using the Android app: the Dashboard's Inbox card showed
"0 tasks to process, 26+ unread mail", tapping it opened the empty GTD Inbox. With nothing to process there, the
tap should go where the work is: the unread mail. (The web dashboard already has separate links for tasks and mail.)

## Acceptance criteria
- [x] Tapping the Dashboard's Inbox card opens Mail when its loaded data shows 0 Inbox tasks and at least one unread mail; in every other case (tasks present, no unread mail, data not loaded yet or failed) it opens the GTD Inbox as before
  - verified (2026-11-02 19:45+0100 by Peter Gürtler): cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug on 472f9c23c -> 2883 tests 0 failures; DashboardDestinationTest (9: 0 tasks + 26+/1 unread -> Mail; tasks present, no mail, null/loading/FAILED/OFFLINE without data -> Inbox; kept data after failed refresh follows the data) and DashboardInboxCardTest (9 Compose taps: Inbox(0,26+) -> onOpen(Mail), 3 tasks/0 mail/loading/failed -> onOpen(Inbox)) pass
- [x] The card's icon stays the Inbox icon; the other cards' destinations are unchanged
  - verified (2026-11-02 19:45+0100 by Peter Gürtler): same run on 472f9c23c (2883 pass): DashboardDestinationTest checks the Inbox card icon source stays the Inbox icon (structural: card draws widget.destination.icon, no semantics on Icon) and the other 7 widgets keep their destination, also when given Inbox data (INBOX guard); existing DashboardScreenTest 'each card opens the matching screen' passes
- [x] Unit/Compose tests cover the cases above; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes
  - verified (2026-11-02 19:45+0100 by Peter Gürtler): cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport on 472f9c23c -> BUILD SUCCESSFUL, 2883 tests 0 failures; 18 new tests; changed lines 6/6, branches 8/8 covered; sh scripts/check.sh on 4d1f67f94 -> all checks passed (86s)

## Notes
- ASSUMPTION (2026-11-02 19:13+0100 by Peter Gürtler): Inbox card follows the data it shows: if a refresh fails but the card still shows the last loaded '0 tasks, N unread', the tap opens Mail; only with no loaded data at all does it fall back to the GTD Inbox (code review CR-1)
- done by Peter Gürtler: `sh scripts/check.sh` passed in 70s on d2ddcd842; criteria verified 3/3
