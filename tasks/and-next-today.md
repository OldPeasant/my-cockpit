---
title: Android Next and Today
status: in_progress
owner: dell26/agent-1
deps: [and-gtd-data]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/next,android/app/src/test/java/ch/sonensei/cockpit/gtd/next,android/app/src/main/java/ch/sonensei/cockpit/gtd/today,android/app/src/test/java/ch/sonensei/cockpit/gtd/today]
prio: 1
lease_until: 2026-10-21T09:02:29Z
---
## Goal
Next actions with filters and Today (SPEC.md §5.3).

## Acceptance criteria
- [ ] Next with context/energy/creativity/estimate filters; Today with overdue/due/scheduled; complete with undo snackbar
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.next.*'` passes, covering filters (Robolectric/Compose UI tests for screens); same for `gtd.today`

## Notes
- ASSUMPTION (2026-10-21 10:30+0200 by dell26/agent-1): Next filters are applied on the device to the cached Next list (so they work offline), with server semantics: one context, exact energy/creativity, max estimate only matches estimated tasks. Area/project/due filters and saved presets are left out on Android (criterion names context/energy/creativity/estimate only).
- ASSUMPTION (2026-10-21 10:30+0200 by dell26/agent-1): Today on Android shows task sections only (overdue, due today, Next tasks scheduled for today or earlier, as the web does); today's calendar events and habits are left for the calendar/habits tasks.
- ASSUMPTION (2026-10-21 10:30+0200 by dell26/agent-1): Completing a project's next step does not prompt for a new next step on Android (needsNextStep is ignored); the stalled project shows in Projects. Undo calls /uncomplete with the version /complete returned.
- ASSUMPTION (2026-10-21 10:30+0200 by dell26/agent-1): Strings live in res/values/strings_next_today.xml and the shared completion/row/offline UI in gtd/next (Today imports it); auth/InAppLoginTest and AppStartRoutingTest (outside touches) now init the test WorkManager because the start screen Today loads GTD data under Robolectric.
