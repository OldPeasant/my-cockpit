---
title: Android: show first-load errors in GTD lists (TaskRepository reports refresh failure)
status: in_progress
owner: dell26/agent-1
deps: [and-next-today]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/data,android/app/src/test/java/ch/sonensei/cockpit/gtd/data,android/app/src/main/java/ch/sonensei/cockpit/gtd/next,android/app/src/main/java/ch/sonensei/cockpit/gtd/today]
prio: 2
lease_until: 2026-10-26T01:10:17Z
---
## Goal
If the first load of a GTD list fails for a reason other than being offline (4xx, unparsable body, disk full), Next/Today show "Loading…" forever (review CR-4 of and-next-today). TaskRepository.observe drops its refresh result.

## Acceptance criteria
- [ ] gtd/data exposes the last refresh failure; Next and Today show an error with a retry button instead of endless loading
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*'` passes, with tests for the failure and retry paths

## Notes
- ASSUMPTION (2026-10-26 01:36+0100 by dell26/agent-1): Retry and error UI reuse the existing lists_retry/lists_error_failed strings (no new resources). The error replaces only the never-loaded state; when a cached list exists and a refresh fails, the cached list stays without a stale banner (out of the task's first-load scope). A running load clears the failure so a retry shows Loading. Tests for Next/Today view models and screens live in test/gtd/next and test/gtd/today (beyond the listed test touches).
