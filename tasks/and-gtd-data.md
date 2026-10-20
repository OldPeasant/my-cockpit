---
title: Android GTD repositories and read cache
status: in_progress
owner: dell26/agent-5
deps: [and-login,gtd-projects-api]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/data,android/app/src/test/java/ch/sonensei/cockpit/gtd/data]
prio: 1
lease_until: 2026-10-20T22:22:17Z
---
## Goal
Data layer for GTD screens with offline read cache (SPEC.md §9.3).

## Acceptance criteria
- [ ] Repositories for tasks/projects/contexts; last loaded Inbox/Next/Today/Projects cached in Room and served read-only with an 'offline' flag when the server is unreachable
- [ ] Periodic sync (WorkManager, 15 min) + foreground SSE refresh
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.data.*'` passes, covering cache fallback when offline, refresh replaces cache (Robolectric/Compose UI tests for screens)

## Notes
