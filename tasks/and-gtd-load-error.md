---
title: Android: show first-load errors in GTD lists (TaskRepository reports refresh failure)
status: done
owner: dell26/agent-1
deps: [and-next-today]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/data,android/app/src/test/java/ch/sonensei/cockpit/gtd/data,android/app/src/main/java/ch/sonensei/cockpit/gtd/next,android/app/src/main/java/ch/sonensei/cockpit/gtd/today]
prio: 2
lease_until: 
---
## Goal
If the first load of a GTD list fails for a reason other than being offline (4xx, unparsable body, disk full), Next/Today show "Loading…" forever (review CR-4 of and-next-today). TaskRepository.observe drops its refresh result.

## Acceptance criteria
- [x] gtd/data exposes the last refresh failure; Next and Today show an error with a retry button instead of endless loading
  - verified (2026-10-26 01:59+0100 by dell26/agent-1): Read+tests on eb5e289d0: GtdConnectivity.failure(view) -> Cached.failure; NextViewModel/TodayViewModel loadFailed/firstLoadFailed/retry(); NextScreen/TodayScreen show LoadFailed (TaskListTags.ERROR) + Retry (TaskListTags.RETRY) instead of Loading; covered by NextScreenTest/TodayScreenTest/NextRouteTest/TodayRouteTest (Robolectric compose) -> pass
- [x] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*'` passes, with tests for the failure and retry paths
  - verified (2026-10-26 01:59+0100 by dell26/agent-1): cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*' lintDebug on eb5e289d0 -> BUILD SUCCESSFUL, 450 tests, 0 failures (incl. failure/retry tests in TaskRepositoryTest, GtdRefreshSupportTest, Next/Today VM+screen+route tests); sh scripts/check.sh -> all checks passed

## Notes
- ASSUMPTION (2026-10-26 01:36+0100 by dell26/agent-1): Retry and error UI reuse the existing lists_retry/lists_error_failed strings (no new resources). The error replaces only the never-loaded state; when a cached list exists and a refresh fails, the cached list stays without a stale banner (out of the task's first-load scope). A running load clears the failure so a retry shows Loading. Tests for Next/Today view models and screens live in test/gtd/next and test/gtd/today (beyond the listed test touches).
- done by dell26/agent-1: `sh scripts/check.sh` passed in 63s on bd9d31985; criteria verified 2/2
