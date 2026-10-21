---
title: Android projects, waiting, someday
status: in_progress
owner: dell26/agent-1
deps: [and-gtd-data]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/projects,android/app/src/test/java/ch/sonensei/cockpit/gtd/projects,android/app/src/main/java/ch/sonensei/cockpit/gtd/lists,android/app/src/test/java/ch/sonensei/cockpit/gtd/lists]
prio: 2
lease_until: 2026-10-21T11:03:26Z
extended: dell26/agent-1
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
- ASSUMPTION (2026-10-21 11:36+0200 by dell26/agent-1): Android Projects list hides COMPLETED/DROPPED projects behind a 'Finished (n)' chip and sorts stalled projects first within each area group
- extended by dell26/agent-1: Implementation, fixes (CR-1..6) and re-review done (only 2 optional P3s left); .taskcheck passes at 1c1829c9. Left: run test-writer on final code to write reviews/and-projects-lists/tests.md (with coverage), decide P3s (fix or accept), tasks.py verify both criteria, write quality.json, done.
