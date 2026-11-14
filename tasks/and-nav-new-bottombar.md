---
title: Android: New replaces Inbox in the bottom bar (Inbox moves to More)
status: in_progress
owner: dell26/agent-1
deps: []
touches: [android/app/src/main/java/ch/sonensei/cockpit/ui,android/app/src/test/java/ch/sonensei/cockpit/ui]
prio: 2
lease_until: 2026-11-14T10:18:14Z
extended: dell26/agent-1
---
## Goal
The bottom bar (`ui/navigation/CockpitDestination.kt`) holds Dashboard, Inbox, Calendar, Mail, More; New is on the More
screen. New (SPEC.md §5.3) lists the GTD Inbox items and the mail inbox together, so it is the more useful entry point:
give New the bottom-bar slot and move Inbox to the More list.

## Acceptance criteria
- [x] Bottom bar is Dashboard, New, Calendar, Mail, More (in that order), with New's existing label and icon; tapping New opens the New view
  - verified (2026-11-14 11:02+0100 by dell26/agent-1): android/gradlew -p android createDebugUnitTestCoverageReport (full unit suite, 5517 tests, 0 failures) at 04497c289: CockpitDestinationTest (order Dashboard,New,Calendar,Mail,More; New label/icon) and CockpitShellTest.bottomBarSwitchesBetweenAllMainDestinations / routesInboxAndNewStillOpenTheirScreens -> pass
- [x] Inbox is on the More screen (first entry, before Today) and opens the Inbox with processing as before; New is no longer listed on More
  - verified (2026-11-14 11:02+0100 by dell26/agent-1): same suite: CockpitDestinationTest More list (Inbox first before Today, no New) and CockpitShellNavigationTest Inbox-from-More tests (13) -> pass
- [x] Bottom-bar selection: on the New view (and on a task/mail opened from it) New is highlighted; on the Inbox, More is highlighted
  - verified (2026-11-14 11:02+0100 by dell26/agent-1): same suite: CockpitShellSelectionTest 21 tests (New highlighted on New, on task/mail/search opened from it; More on Inbox) + CockpitShellSelectionRestoreTest 3 (after recreation) -> pass
- [x] Unchanged: the Dashboard Inbox card still opens the Inbox; deep links/routes `inbox` and `new` still work; Back from New/Inbox behaves like the other bottom-bar/More destinations
  - verified (2026-11-14 11:02+0100 by dell26/agent-1): same suite: CockpitShellTest/CockpitShellNavigationTest Dashboard Inbox card opens Inbox, routes inbox/new open their screens, system Back from New/Inbox and dashboardTabAfter*Card* tests -> pass
- [x] Tests updated (`CockpitDestinationTest`, shell/navigation tests); `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass
  - verified (2026-11-14 11:02+0100 by dell26/agent-1): sh scripts/check.sh (includes android ktlintCheck testDebugUnitTest lintDebug) at f0ca14608 -> exit 0, 'all checks passed'; android/gradlew -p android createDebugUnitTestCoverageReport ktlintCheck -> 5517 tests pass

## Notes
Requested by the owner 2026-11-14: New is more convenient than Inbox for daily use. Navigation-only change, no new
strings. SPEC.md §12 (web left navigation) is unaffected; record the Android bottom-bar choice with `tasks.py assume`.
- ASSUMPTION (2026-11-14 09:54+0100 by dell26/agent-1): Android bottom bar is Dashboard, New, Calendar, Mail, More; Inbox is the first More entry (owner request 2026-11-14). Web left navigation (SPEC.md §12) unchanged. Stacked screens (task detail, search, opened items) highlight the tab of the nearest destination below them on the back stack, so a task opened from New keeps New selected.
- ASSUMPTION (2026-11-14 10:26+0100 by dell26/agent-1): Re-tapping the selected New/Calendar/Mail tab while a stacked screen (task, search) is open on it restores that stacked screen (standard saveState/restoreState), unlike Dashboard/More which return to their root; the criteria don't define reselect, left as is (review CR-2)
- extended by dell26/agent-1: Implementation, tests, test-writer and code-review rounds done (final delta review at 3ec48717a: no material findings; CR-2/CR-3 optional P3 left open). Left: run android ktlintCheck testDebugUnitTest lintDebug + scripts/check.sh, record verify for criteria 1-5, write quality.json (reviewed_revision = final code commit, CR-2/CR-3 dispositions, fixes.md exists), commit, then tasks.py done.
