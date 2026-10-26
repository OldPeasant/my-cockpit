---
title: Android Today: show tasks released to Next today under 'Scheduled for today' (releasedOn / scheduled=today)
status: in_progress
owner: dell26/agent-1
deps: [gtd-scheduled-marker]
touches: [android/app/src/main/java/ch/sonensei/cockpit/gtd/today]
prio: 2
lease_until: 2026-10-26T16:29:47Z
---
## Goal
The server clears `scheduledDate` when it releases a SCHEDULED task to NEXT, so Android's Today view
(`TodayViewModel`, which only counts tasks with a scheduled date) shows nothing under "Scheduled for today".
gtd-scheduled-marker added `TaskResponse.releasedOn` and `GET /api/v1/tasks?scheduled=today`; use them as the web does.

## Acceptance criteria
- [ ] Android Today 'Scheduled for today' shows tasks released to Next today (via `scheduled=today` or `releasedOn`); unit tests cover it; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes

## Notes
- Found by the code review of gtd-scheduled-marker (CR-1).
