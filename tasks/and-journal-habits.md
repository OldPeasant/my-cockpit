---
title: Android journal and habits
status: in_progress
owner: dell26/agent-1
deps: [and-shell,journal-api,habits-api,and-login]
touches: [android/app/src/main/java/ch/sonensei/cockpit/journal,android/app/src/test/java/ch/sonensei/cockpit/journal,android/app/src/main/java/ch/sonensei/cockpit/habits,android/app/src/test/java/ch/sonensei/cockpit/habits]
prio: 3
lease_until: 2026-10-22T12:18:44Z
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
- ASSUMPTION (2026-10-22 13:38+0200 by dell26/agent-1): Journal reuses the notes feature's MarkdownView, NoteImages loader and NotePhotoFiles (camera/gallery) instead of copying them; strings live in new res/values/strings_journal.xml and strings_habits.xml (outside the listed touches, but new per-feature files)
- ASSUMPTION (2026-10-22 13:46+0200 by dell26/agent-1): Journal date from the device clock (not the server's today): a phone whose date is ahead of the user's server time zone gets 400 on save and shows 'Not saved: the server refused the change'; accepted (review CR-2) as rare, journal API exposes no server today
