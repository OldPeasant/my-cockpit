---
title: Android: Dashboard as start screen
status: in_progress
owner: dell26/agent-2
deps: [and-shell,and-next-today,web-dashboard,and-logbook]
touches: [android/app/src/main/java/ch/sonensei/cockpit/dashboard,android/app/src/main/java/ch/sonensei/cockpit/ui,android/app/src/test/java/ch/sonensei/cockpit/dashboard,android/app/src/main/res/values/strings_dashboard.xml,android/app/src/main/res/values-de/strings_dashboard.xml]
prio: 2
lease_until: 2026-11-01T19:45:53Z
---
## Goal
The web dashboard (SPEC.md §8.7, `web/src/app/features/dashboard`) has no Android counterpart. The owner wants it
as the Android start screen (2026-11-01). Reuse the endpoints the web dashboard uses; server unchanged.

## Acceptance criteria
- [ ] A Dashboard destination shows the §8.7 widgets the web dashboard shows: Today (tasks + events + habits), Inbox count (tasks + unread mail), Stalled projects, Waiting For overdue, Upcoming events, Read-later queue, Pinned notes, Last weekly review; each card opens the matching Android screen
- [ ] Which widgets are shown and their order follow the user's dashboard configuration from the web (read-only on the phone) if the server stores it; otherwise all widgets in the §8.7 order
- [ ] The app opens on the Dashboard after login and on launch; the bottom navigation includes it
- [ ] One failing widget shows an error in its card without breaking the others; pull to refresh reloads all
- [ ] All new UI strings exist in English and German (`res/values`, `res/values-de`)
- [ ] Unit tests (ViewModel, plus Compose UI tests where the project has them for similar screens) cover the criteria above; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes

## Notes
Requested by the owner (2026-11-01) after trying the Android app on the phone. Changing the start destination touches the shell navigation; keep other tasks' shell changes when merging.
