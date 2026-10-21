---
title: Android: show first-load errors in GTD lists (TaskRepository reports refresh failure)
status: todo
owner:
deps: [and-next-today]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/data,android/app/src/test/java/ch/sonensei/cockpit/gtd/data,android/app/src/main/java/ch/sonensei/cockpit/gtd/next,android/app/src/main/java/ch/sonensei/cockpit/gtd/today]
prio: 2
---
## Goal
If the first load of a GTD list fails for a reason other than being offline (4xx, unparsable body, disk full), Next/Today show "Loading…" forever (review CR-4 of and-next-today). TaskRepository.observe drops its refresh result.

## Acceptance criteria
- [ ] gtd/data exposes the last refresh failure; Next and Today show an error with a retry button instead of endless loading
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*'` passes, with tests for the failure and retry paths

## Notes
