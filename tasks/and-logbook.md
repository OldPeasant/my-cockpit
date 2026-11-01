---
title: Android: Logbook (completed tasks, reopen)
status: done
owner: dell26/agent-2
deps: [and-projects-lists,and-gtd-data,and-trash]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/lists,android/app/src/main/java/ch/sonensei/cockpit/ui,android/app/src/test/java/ch/sonensei/cockpit/gtd,android/app/src/main/res/values/strings_projects_lists.xml,android/app/src/main/res/values-de/strings_projects_lists.xml]
prio: 2
lease_until: 
---
## Goal
The Logbook (done tasks, SPEC.md §5) exists on the web only. Use `GET /api/v1/tasks?completed=true` and
`POST /api/v1/tasks/{id}/uncomplete`; server unchanged.

## Acceptance criteria
- [x] Logbook is reachable from the More screen (next to Projects/Waiting/Someday) and lists completed tasks newest first, grouped by completion day, with project/context where set; loads more on scroll if the API pages
  - verified (2026-11-01 20:19+0100 by dell26/agent-2): cd android && ./gradlew testDebugUnitTest (LogbookViewModelTest 32 tests, LogbookScreenTest, repository/destination tests: More entry after Someday, newest-first grouped by local completion day, project/context text, paging on scroll) -> pass, 2667 tests 0 failures
- [x] Tapping a task opens it as in the other GTD lists; a "Reopen" action uncompletes it and it leaves the Logbook
  - verified (2026-11-01 20:19+0100 by dell26/agent-2): cd android && ./gradlew testDebugUnitTest: LogbookScreenTest tap opens detail dialog, Reopen row/dialog action; LogbookViewModelTest reopen calls uncomplete with If-Match and removes task, conflict reloads -> pass (no Android task detail view exists; read-only detail dialog, see assumption)
- [x] Loading, empty and error states follow the existing GTD list screens
  - verified (2026-11-01 20:20+0100 by dell26/agent-2): cd android && ./gradlew testDebugUnitTest: LogbookScreenTest/ViewModelTest loading, empty, error+retry, stale states using shared Centered/LoadFailed/StaleBanner -> pass
- [x] All new UI strings exist in English and German (`res/values`, `res/values-de`)
  - verified (2026-11-01 20:20+0100 by dell26/agent-2): cd android && ./gradlew testDebugUnitTest lintDebug: LogbookStringsTest + German Compose tests, lint MissingTranslation clean; 15 strings in values and values-de -> pass
- [x] Unit tests (ViewModel, plus Compose UI tests where the project has them for similar screens) cover the criteria above; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes
  - verified (2026-11-01 20:20+0100 by dell26/agent-2): cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport -> BUILD SUCCESSFUL, 2667 tests 0 failures; ViewModel + Compose UI tests; task coverage 98% lines; sh scripts/check.sh -> all checks passed

## Notes
Requested by the owner (2026-11-01) after trying the Android app on the phone.
- ASSUMPTION (2026-11-01 19:43+0100 by dell26/agent-2): No Android GTD list opens a task on tap (there is no Android task detail view), so tapping a Logbook row opens a read-only detail dialog (completion time, project, contexts, notes) with Reopen/Close; Reopen is also a row button like Follow up/Activate
- ASSUMPTION (2026-11-01 19:43+0100 by dell26/agent-2): Logbook pages 50 tasks per request and reloads (as many pages as shown) on task change events, retry, and after a reopen; it is not cached offline (SPEC 9.3 caches only Inbox/Next/Today)
- ASSUMPTION (2026-11-01 19:43+0100 by dell26/agent-2): Logbook placed in More right after Someday; nav_logbook string lives in strings_projects_lists.xml to stay within the task's touches
- done by dell26/agent-2: `sh scripts/check.sh` passed in 83s on 204d3af37; criteria verified 5/5
