---
title: Android projects, waiting, someday
status: todo
owner:
deps: [and-gtd-data]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/projects,android/app/src/test/java/ch/sonensei/cockpit/gtd/projects,android/app/src/main/java/ch/sonensei/cockpit/gtd/lists,android/app/src/test/java/ch/sonensei/cockpit/gtd/lists]
prio: 2
---
## Goal
Remaining GTD screens.

## Acceptance criteria
- [ ] Projects (stalled highlighted, detail with tasks and next step), Waiting For, Someday
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.projects.*'` passes, covering stalled badge, next-step prompt (Robolectric/Compose UI tests for screens); and `gtd.lists`

## Notes
