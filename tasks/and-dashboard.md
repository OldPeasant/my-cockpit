---
title: Android: Dashboard as start screen
status: in_progress
owner: felix/agent-1
deps: [and-shell,and-next-today,web-dashboard,and-logbook]
touches: [android/app/src/main/java/ch/sonensei/cockpit/dashboard,android/app/src/main/java/ch/sonensei/cockpit/ui,android/app/src/test/java/ch/sonensei/cockpit/dashboard,android/app/src/main/res/values/strings_dashboard.xml,android/app/src/main/res/values-de/strings_dashboard.xml]
prio: 2
lease_until: 2026-11-01T21:43:24Z
extended: felix/agent-1
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
- ASSUMPTION (2026-11-01 21:09+0100 by felix/agent-1): Bottom bar keeps 5 items (Material max): Dashboard replaces Today there; Today moves to the top of the More list and the Dashboard's Today card opens it
- ASSUMPTION (2026-11-01 21:09+0100 by felix/agent-1): Unknown reminder item types (OpenItemRequest fallback = CockpitDestination.start) now open the Dashboard instead of Today
- ASSUMPTION (2026-11-01 21:09+0100 by felix/agent-1): Android widgets reload when the screen is shown and on pull to refresh only (no SSE-driven reload as on the web); a failed reload keeps the last data under the card's error line
- ASSUMPTION (2026-11-01 21:09+0100 by felix/agent-1): German label of the Dashboard tab is 'Übersicht'
- extended by felix/agent-1: Implementation done (fd2d43e2) + tests (3cd661df, 0ffbe28e, 71874f72); code-review report reviews/and-dashboard/review.md exists (0 P0-P2, 2 P3) but is for d3998a04-era code. Left: (1) 2 failing unit tests from test-writer wip: DashboardRepositoryTest 'one failing call of today fails the today widget' (ConcurrentModificationException in paths() helper, line 407 - recorded-request list read while calls still run; copy/synchronize) and DashboardInAppTest 'the first configured card is at the top when the app opens' (dashboard-card:inbox not displayed - check whether test or real order/scroll bug); (2) re-run test-writer to write reviews/and-dashboard/tests.md with coverage; re-run code-reviewer on final code; decide P3 findings; (3) quality.json, tasks.py verify #1-#6, .taskcheck, done.
