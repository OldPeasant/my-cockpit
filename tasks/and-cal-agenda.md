---
title: Android calendar agenda and day/week
status: in_progress
owner: dell26/agent-1
deps: [and-login,cal-local-api]
touches: [android/app/src/main/java/ch/sonensei/cockpit/calendar/agenda,android/app/src/test/java/ch/sonensei/cockpit/calendar/agenda]
prio: 2
lease_until: 2026-10-21T15:41:00Z
releases: 1
extended: 
---
## Goal
Calendar on the phone (SPEC.md §9.2 item 4).

## Acceptance criteria
- [x] Agenda list (infinite scroll by range) and day/week views; calendar visibility
  - verified (2026-10-21 17:21+0200 by dell26/agent-1): cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' lintDebug on 1cb4dd73 -> pass; agenda infinite scroll (CalendarAgendaInfiniteScrollTest 5), day/week views (CalendarScreensTest, CalendarViewModelTest), calendar visibility PATCH+If-Match (CalendarRepositoryTest, CalendarViewModelEdgeCasesTest) all green
- [x] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*'` passes, covering range loading and grouping by day (Robolectric/Compose UI tests for screens)
  - verified (2026-10-21 17:21+0200 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' on 1cb4dd73 -> BUILD SUCCESSFUL, 13 classes, 98 tests, 0 failures/errors/skipped; range loading (CalendarViewModel*/CalendarRepository*), grouping by day (AgendaGrouping* 21), Robolectric/Compose screen tests (CalendarScreens*, CalendarScreenInAppTest, Position/Start/Resume tests)

## Notes
- ASSUMPTION (2026-10-21 15:35+0200 by dell26/agent-1): Agenda offline Room cache (SPEC 9.3) is not in this task's acceptance criteria; deferred to new task and-cal-cache. Offline, the agenda shows the offline banner with what is already loaded in memory.
- release by dell26/agent-1: Implementation complete (agenda infinite scroll by range, day/week views, calendar visibility with pending-write handling); first code review + fixer done (reviews/and-cal-agenda/review.md, fixes.md; CR-2 offline cache deferred to and-cal-cache). ktlintCheck + testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.agenda.*' pass at 9b979438. Left: run test-writer (no tests.md yet, needs coverage numbers) and code-reviewer again on final code (9b979438), fix anything valid, tasks.py verify #1 and #2, write quality.json, run .taskcheck, done.
