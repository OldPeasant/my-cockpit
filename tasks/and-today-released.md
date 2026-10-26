---
title: Android Today: show tasks released to Next today under 'Scheduled for today' (releasedOn / scheduled=today)
status: in_progress
owner: dell26/agent-1
deps: [gtd-scheduled-marker]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/today]
prio: 2
lease_until: 2026-10-26T16:39:51Z
---
## Goal
The server clears `scheduledDate` when it releases a SCHEDULED task to NEXT, so Android's Today view
(`TodayViewModel`, which only counts tasks with a scheduled date) shows nothing under "Scheduled for today".
gtd-scheduled-marker added `TaskResponse.releasedOn` and `GET /api/v1/tasks?scheduled=today`; use them as the web does.

## Acceptance criteria
- [x] Android Today 'Scheduled for today' shows tasks released to Next today (via `scheduled=today` or `releasedOn`); unit tests cover it; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes
  - verified (2026-10-26 17:16+0100 by dell26/agent-1): sectionOf puts releasedOn==today tasks under SCHEDULED; releasedOn mapped through TaskResponse->Room cache (v2)->GtdTask and TaskLists; tests TodayViewModelTest (28), TaskRepositoryTest (19), TaskListsRepositoryTest (7) cover it; cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug on 8a3a65622 -> BUILD SUCCESSFUL, 0 failures

## Notes
- Found by the code review of gtd-scheduled-marker (CR-1).
- ASSUMPTION (2026-10-26 17:11+0100 by dell26/agent-1): Used TaskResponse.releasedOn (cached as a new column, GtdCacheDatabase v2 with the existing destructive fallback since it is only a cache) instead of a separate scheduled=today view; this required small edits in gtd/data and gtd/lists outside the task's touches
