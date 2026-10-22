---
title: Android journal and habits
status: in_progress
owner: dell26/agent-1
deps: [and-shell,journal-api,habits-api,and-login]
touches: [android/app/src/main/java/ch/sonensei/cockpit/journal,android/app/src/test/java/ch/sonensei/cockpit/journal,android/app/src/main/java/ch/sonensei/cockpit/habits,android/app/src/test/java/ch/sonensei/cockpit/habits]
prio: 3
lease_until: 2026-10-22T11:58:32Z
---
## Goal
Journal and habits on the phone (SPEC.md §9.2 item 8).

## Acceptance criteria
- [ ] Today's journal entry with photo, habit check-off list
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.journal.*'` passes, covering entry autosave (Robolectric/Compose UI tests for screens); `habits` check-off

## Notes
- ASSUMPTION (2026-10-22 13:38+0200 by dell26/agent-1): Android journal shows only today's entry (date from the device clock, fixed while the screen is open); no heatmap, on-this-day, mood or tag editing on the phone: §9.2 item 8 asks for 'journal'; mood/tags of an existing entry are kept as is on save
- ASSUMPTION (2026-10-22 13:38+0200 by dell26/agent-1): Android journal shows only today's entry (date from the device clock, fixed while the screen is open); no heatmap, on-this-day, mood or tag editing on the phone: §9.2 item 8 asks for 'journal'; mood/tags of an existing entry are kept as is on save
- ASSUMPTION (2026-10-22 13:38+0200 by dell26/agent-1): Android habits screen only lists active habits and checks them off for the server's today (stats.today): one tap sends targetCount, tapping a done habit sends 0; creating/editing habits stays on the web
