---
title: Android agenda offline read cache (SPEC 9.3)
status: in_progress
owner: dell26/agent-1
deps: [and-cal-agenda]
touches: [android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda,android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda]
prio: 2
lease_until: 2026-10-26T00:29:53Z
---
## Goal
SPEC.md §9.3 "Read cache": the last-loaded agenda is cached in Room and shown read-only with an
"offline" banner when the server is unreachable.

## Acceptance criteria
- [ ] Every successful agenda load (the first range and each chunk loaded on scrolling) is stored in a Room cache
      together with the calendar list; a new load from today replaces it (Robolectric Room tests).
- [ ] When the server is unreachable and nothing is loaded for the range yet, the agenda (and a day or week view
      inside the cached range) shows the cached events with the offline banner naming when they were loaded; while
      cached data is shown it is read-only (calendar visibility toggles and loading more days are disabled), and the
      next successful load replaces it (view model and Compose tests).
- [ ] Logout clears the cache, and an answer whose load started before the logout is not stored (test).
- [ ] `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes.

## Notes
- ASSUMPTION (2026-10-26 01:10+0100 by dell26/agent-1): The task had no acceptance criteria; I wrote them from SPEC 9.3 (cache the agenda loads in Room, show read-only with offline banner, cleared on logout).
- ASSUMPTION (2026-10-26 01:10+0100 by dell26/agent-1): Only agenda loads are cached (day/week loads are not, they would replace it); offline, a day/week view fully inside the cached days is shown from that cache.
- ASSUMPTION (2026-10-26 01:10+0100 by dell26/agent-1): Read-only while cached data is shown means: calendar visibility chips disabled, no load-more, tapping an event does not open the editor (it needs the server). The editor's FAB (calendar/edit, outside touches) is unchanged.
