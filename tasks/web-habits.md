---
title: Habits UI
status: in_progress
owner: dell26/agent-2
deps: [p0-web-shell,habits-api,p0-e2e-harness]
touches: [web/src/app/features/habits]
prio: 3
lease_until: 2026-10-22T01:25:29Z
---
## Goal
Track habits (SPEC.md §8.5).

## Acceptance criteria
- [ ] Habit list with one-tap check, streaks, stats, create/edit
- [ ] Vitest component/service tests cover check toggle and streak display; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-22 02:15+0200 by dell26/agent-2): One-tap check on a habit with targetCount > 1 counts up by one per tap and wraps to 0 after the target; a once-a-day habit toggles 0/1. The check always targets the server's stats.today.
- ASSUMPTION (2026-10-22 02:15+0200 by dell26/agent-2): Habits page has no delete action (archive/unarchive only) and no per-day history grid: SPEC 8.5 asks for list, check, streaks and 30-day completion; delete and history are left for later.
- ASSUMPTION (2026-10-22 02:16+0200 by dell26/agent-2): web/src/locale/messages.xlf not regenerated for the new habits.* ids, like web-cal-sources: left to a dedicated i18n extract (shared file outside touches).
- ASSUMPTION (2026-10-22 03:07+0200 by dell26/agent-2): A quiet reload dropped because a change happened while it was pending is not retried (review P3, round 3): the window is one GET long and the next 409/412 or tab return reloads again; accepted as a known limitation rather than another fix round.
