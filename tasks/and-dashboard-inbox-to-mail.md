---
title: Android: Dashboard Inbox card opens Mail when the GTD Inbox is empty
status: in_progress
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
- [ ] Tapping the Dashboard's Inbox card opens Mail when its loaded data shows 0 Inbox tasks and at least one unread mail; in every other case (tasks present, no unread mail, data not loaded yet or failed) it opens the GTD Inbox as before
- [ ] The card's icon stays the Inbox icon; the other cards' destinations are unchanged
- [ ] Unit/Compose tests cover the cases above; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes

## Notes
