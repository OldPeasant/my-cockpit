---
title: Android journal and habits
status: todo
owner:
deps: [and-shell,journal-api,habits-api,and-login]
touches: [android/app/src/main/java/ch/sonensei/cockpit/journal,android/app/src/test/java/ch/sonensei/cockpit/journal,android/app/src/main/java/ch/sonensei/cockpit/habits,android/app/src/test/java/ch/sonensei/cockpit/habits]
prio: 3
---
## Goal
Journal and habits on the phone (SPEC.md §9.2 item 8).

## Acceptance criteria
- [ ] Today's journal entry with photo, habit check-off list
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.journal.*'` passes, covering entry autosave (Robolectric/Compose UI tests for screens); `habits` check-off

## Notes
