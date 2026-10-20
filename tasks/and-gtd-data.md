---
title: Android GTD repositories and read cache
status: todo
owner:
deps: [and-login,gtd-projects-api]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/data,android/app/src/test/java/ch/sonensei/cockpit/gtd/data]
prio: 1
---
## Goal
Data layer for GTD screens with offline read cache (SPEC.md §9.3).

## Acceptance criteria
- [ ] Repositories for tasks/projects/contexts; last loaded Inbox/Next/Today/Projects cached in Room and served read-only with an 'offline' flag when the server is unreachable
- [ ] Periodic sync (WorkManager, 15 min) + foreground SSE refresh
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.data.*'` passes, covering cache fallback when offline, refresh replaces cache (Robolectric/Compose UI tests for screens)

## Notes
