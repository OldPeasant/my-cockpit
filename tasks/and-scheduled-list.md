---
title: Android: Scheduled list screen
status: in_progress
owner: dell26/agent-1
deps: [and-project-tasks,and-mail-search-nav]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/lists,android/app/src/test/java/ch/sonensei/cockpit/gtd/lists,android/app/src/main/java/ch/sonensei/cockpit/ui/navigation,android/app/src/test/java/ch/sonensei/cockpit/ui/navigation,android/app/src/main/res/values/strings_projects_lists.xml,android/app/src/main/res/values-de/strings_projects_lists.xml]
prio: 2
lease_until: 2026-11-08T12:45:34Z
---
## Goal
Web has a Scheduled list (`lists/scheduled-page.html`); Android has no destination for it.

## Acceptance criteria
- [ ] A Scheduled screen (reachable like Waiting/Someday) lists scheduled tasks grouped by date, with complete and open detail
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 13:28+0100 by dell26/agent-1): Scheduled is a More entry after Someday (web nav order: Waiting, Someday, Scheduled, Logbook); adding the enum entry needed a one-line edit of ui/CockpitShell.kt (DestinationContent's exhaustive when), outside touches, plus the CockpitDestinationTest order. Rows complete with the checkbox and open the task detail; no extra row action (web has none). Past dates show as Yesterday/full date, undated tasks under 'No date' last, like web.
