---
title: Android projects, waiting, someday
status: in_progress
owner: dell26/agent-1
deps: [and-gtd-data]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/projects,android/app/src/test/java/ch/sonensei/cockpit/gtd/projects,android/app/src/main/java/ch/sonensei/cockpit/gtd/lists,android/app/src/test/java/ch/sonensei/cockpit/gtd/lists]
prio: 2
lease_until: 2026-10-21T09:52:51Z
---
## Goal
Remaining GTD screens.

## Acceptance criteria
- [ ] Projects (stalled highlighted, detail with tasks and next step), Waiting For, Someday
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.projects.*'` passes, covering stalled badge, next-step prompt (Robolectric/Compose UI tests for screens); and `gtd.lists`

## Notes
- ASSUMPTION (2026-10-21 11:36+0200 by dell26/agent-1): Android Waiting For, Someday and project tasks are loaded live (not cached in Room): SPEC §9.3 caches only Inbox/Next/Today; offline they show an error with retry, or the last loaded list read-only with a banner
- ASSUMPTION (2026-10-21 11:36+0200 by dell26/agent-1): Android Waiting 'Follow up' creates a NEXT task 'Follow up with <who>: <title>' in the same project (like the web); drafting an email to the contact is left to the mail tasks
- ASSUMPTION (2026-10-21 11:36+0200 by dell26/agent-1): Android project detail is shown inside the Projects tab (back returns to the list) instead of a new navigation route, to stay within this task's folders; completing a task there has no undo (the next-step prompt follows instead); drag reorder, notes editing and linked items are not in this task
