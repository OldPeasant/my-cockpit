---
title: Android Next and Today
status: todo
owner:
deps: [and-gtd-data]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/next,android/app/src/test/java/ch/sonensei/cockpit/gtd/next,android/app/src/main/java/ch/sonensei/cockpit/gtd/today,android/app/src/test/java/ch/sonensei/cockpit/gtd/today]
prio: 1
---
## Goal
Next actions with filters and Today (SPEC.md §5.3).

## Acceptance criteria
- [ ] Next with context/energy/creativity/estimate filters; Today with overdue/due/scheduled; complete with undo snackbar
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.next.*'` passes, covering filters (Robolectric/Compose UI tests for screens); same for `gtd.today`

## Notes
