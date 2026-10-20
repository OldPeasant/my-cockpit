---
title: Android GTD repositories and read cache
status: in_progress
owner: dell26/agent-5
deps: [and-login,gtd-projects-api]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/data,android/app/src/test/java/ch/sonensei/cockpit/gtd/data]
prio: 1
lease_until: 2026-10-20T23:02:41Z
---
## Goal
Data layer for GTD screens with offline read cache (SPEC.md §9.3).

## Acceptance criteria
- [ ] Repositories for tasks/projects/contexts; last loaded Inbox/Next/Today/Projects cached in Room and served read-only with an 'offline' flag when the server is unreachable
- [ ] Periodic sync (WorkManager, 15 min) + foreground SSE refresh
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.data.*'` passes, covering cache fallback when offline, refresh replaces cache (Robolectric/Compose UI tests for screens)

## Notes
- ASSUMPTION (2026-10-21 00:04+0200 by dell26/agent-5): GTD read cache is its own Room database (gtd/data/GtdCacheDatabase, gtd_cache.db) with destructive migration, not a shared app database: it only holds copies of server data and the task's touches are limited to gtd/data; and-capture-queue can keep pending_capture in its own DB.
- ASSUMPTION (2026-10-21 00:04+0200 by dell26/agent-5): Today view = listTasks(due=overdue) followed by listTasks(due=today), de-duplicated (SPEC 5.x Today: due/overdue tasks). Projects cache holds all projects of every status; screens filter.
- ASSUMPTION (2026-10-21 00:04+0200 by dell26/agent-5): Offline flag: network errors and HTTP 5xx (server or proxy down) mark all GTD views offline (read-only); 4xx/unreadable bodies keep the cache but do not set offline. One shared flag, cleared by the next successful load.
- ASSUMPTION (2026-10-21 00:04+0200 by dell26/agent-5): Periodic sync is scheduled lazily (KEEP, 15 min, network constraint) the first time a GTD view is observed and cancelled on logout via SessionCache; the worker gets its dependencies through a Hilt EntryPoint because CockpitApp has no HiltWorkerFactory (outside this task's touches).
- ASSUMPTION (2026-10-21 00:04+0200 by dell26/agent-5): Foreground SSE refresh: repository observe() flows share one /api/v1/events connection while collected (screens collect only while visible), closed 5s after the last collector; reconnect with backoff and reload after each (re)connect since events are not replayed. Hand-written SSE parser on OkHttp, no okhttp-sse dependency.
- ASSUMPTION (2026-10-21 00:05+0200 by dell26/agent-5): Criterion 3's 'Robolectric/Compose UI tests for screens' belongs to the screen tasks (and-inbox, and-next-today, and-projects-lists); this task has no screens in its touches, so its tests are Robolectric data-layer tests.
