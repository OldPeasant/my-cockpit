---
title: Android: New replaces Inbox in the bottom bar (Inbox moves to More)
status: todo
owner:
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/ui,android/app/src/test/java/ch/sonensei/cockpit/ui]
prio: 2
---
## Goal
The bottom bar (`ui/navigation/CockpitDestination.kt`) holds Dashboard, Inbox, Calendar, Mail, More; New is on the More
screen. New (SPEC.md §5.3) lists the GTD Inbox items and the mail inbox together, so it is the more useful entry point:
give New the bottom-bar slot and move Inbox to the More list.

## Acceptance criteria
- [ ] Bottom bar is Dashboard, New, Calendar, Mail, More (in that order), with New's existing label and icon; tapping New opens the New view
- [ ] Inbox is on the More screen (first entry, before Today) and opens the Inbox with processing as before; New is no longer listed on More
- [ ] Bottom-bar selection: on the New view (and on a task/mail opened from it) New is highlighted; on the Inbox, More is highlighted
- [ ] Unchanged: the Dashboard Inbox card still opens the Inbox; deep links/routes `inbox` and `new` still work; Back from New/Inbox behaves like the other bottom-bar/More destinations
- [ ] Tests updated (`CockpitDestinationTest`, shell/navigation tests); `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Requested by the owner 2026-11-14: New is more convenient than Inbox for daily use. Navigation-only change, no new
strings. SPEC.md §12 (web left navigation) is unaffected; record the Android bottom-bar choice with `tasks.py assume`.
