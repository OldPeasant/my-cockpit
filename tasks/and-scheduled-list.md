---
title: Android: Scheduled list screen
status: in_progress
owner: dell26/agent-1
deps: [and-project-tasks,and-mail-search-nav]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/lists,android/app/src/test/java/ch/sonensei/cockpit/gtd/lists,android/app/src/main/java/ch/sonensei/cockpit/ui/navigation,android/app/src/test/java/ch/sonensei/cockpit/ui/navigation,android/app/src/main/res/values/strings_projects_lists.xml,android/app/src/main/res/values-de/strings_projects_lists.xml]
prio: 2
lease_until: 2026-11-08T13:00:44Z
---
## Goal
Web has a Scheduled list (`lists/scheduled-page.html`); Android has no destination for it.

## Acceptance criteria
- [x] A Scheduled screen (reachable like Waiting/Someday) lists scheduled tasks grouped by date, with complete and open detail
  - verified (2026-11-08 13:45+0100 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.lists.*' --tests 'ch.sonensei.cockpit.ui.navigation.*' -> pass: ScheduledInAppTest 1/1 (Robolectric+Hilt MainActivity: More > Scheduled opens real list, not placeholder), ScheduledListTest 11/11 (date headers Today/Tomorrow/Yesterday/full date/No date, checkbox completes, tap opens detail via TaskOpener), ScheduledViewModelTest 9/9 (loads SCHEDULED list, date order, complete/undo), CockpitDestinationTest 11/11
- [x] New strings are in English and German (`res/values/` + `res/values-de/`)
  - verified (2026-11-08 13:45+0100 by dell26/agent-1): 6 strings (nav_scheduled, scheduled_empty/today/tomorrow/yesterday/no_date) added to res/values/strings_projects_lists.xml and res/values-de/strings_projects_lists.xml; ScheduledStringsTest 3/3 + ScheduledListTest German @Config(qualifiers=de) test pass; lintDebug (MissingTranslation) clean
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 13:28+0100 by dell26/agent-1): Scheduled is a More entry after Someday (web nav order: Waiting, Someday, Scheduled, Logbook); adding the enum entry needed a one-line edit of ui/CockpitShell.kt (DestinationContent's exhaustive when), outside touches, plus the CockpitDestinationTest order. Rows complete with the checkbox and open the task detail; no extra row action (web has none). Past dates show as Yesterday/full date, undated tasks under 'No date' last, like web.
